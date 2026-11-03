"""
StethoScope capture firmware - Raspberry Pi Pico (RP2040), MicroPython.

Samples the analog stethoscope signal and streams it to the Android app over the
USB CDC serial link.

--------------------------------------------------------------------------------
WIRING ASSUMPTION
--------------------------------------------------------------------------------
The microphone signal reaches ADC_PIN (default GP26 = ADC0) *after* the analog
bandpass filter, biased to mid-rail (~1.65 V) so it swings within the ADC's
0-3.3 V window. The RP2040 ADC is unipolar: a signal centred on 0 V would clip
its entire negative half. If you see only positive-going half-waves, the bias
network is the first thing to check.

--------------------------------------------------------------------------------
WHY DMA AND NOT A PYTHON LOOP
--------------------------------------------------------------------------------
No Python code runs in the sample path. The ADC free-runs at a rate set by its
own clock divider and pushes results into a hardware FIFO; two DMA channels
chained to each other drain that FIFO into alternating buffers. Python only ever
touches a buffer that DMA has already finished with.

A `while True: adc.read_u16()` loop would appear to work but would jitter by tens
of microseconds and, worse, stall for milliseconds whenever the garbage collector
ran - producing timing artefacts that look exactly like an irregular heartbeat.

--------------------------------------------------------------------------------
WIRE FORMAT
--------------------------------------------------------------------------------
Little-endian throughout. One frame per DMA buffer:

    offset  size   field
    0       4      magic       "STH1"
    4       2      seq         uint16, increments per frame, wraps
    6       2      nsamples    uint16
    8       4      sampleRate  uint32, Hz
    12      2      flags       uint16, bit0 = ADC FIFO overrun since last frame
    14      2      reserved    uint16, zero
    16      n*2    samples     uint16, RIGHT-ALIGNED 12-bit, range 0..4095

Samples are sent exactly as the ADC produced them - unsigned, 0..4095, centred
near 2048. No scaling or centring happens here, deliberately: it keeps this loop
a pure DMA-to-USB pass-through with no per-sample Python, and the app already
corrects DC offset and scale exactly (see SignalConditioner.kt). The `seq` field
lets the app detect frames lost to USB backpressure, and `flags` reports samples
lost inside the ADC FIFO.

--------------------------------------------------------------------------------
COMMANDS (single ASCII byte on the serial link)
--------------------------------------------------------------------------------
    ?   identify - prints a human-readable banner line, including FW_VERSION
    S   start streaming frames
    X   stop streaming, return to idle
    T   stream a synthetic 100 Hz test tone instead of the ADC, so the phone
        side can be verified with no analog hardware attached
    Q   quit to the MicroPython REPL

Install as main.py on the board so it runs at boot. It idles until told to start,
which keeps the REPL usable and stops it filling buffers when nothing is
listening.

--------------------------------------------------------------------------------
VERSIONING - DO NOT BREAK THIS
--------------------------------------------------------------------------------
The Android app checks this file's version every time the board is plugged in and
reflashes it over the MicroPython raw REPL when it does not match (see
usb/StethoscopeFirmware.kt). Two things make that work:

  * FW_VERSION below is parsed out of the *source text* by the app, which ships
    this exact file as an asset. Keep it on its own line as a plain string
    literal, and bump it whenever anything in here changes.
  * The `?` banner must keep printing "fw=<FW_VERSION>", because that is what the
    app compares the asset against.

The app's copy is synced from this file by the Gradle build, so there is one
source of truth and the two cannot drift.
"""

import gc
import struct
import sys
import time

import machine
import micropython
import rp2
import select
from machine import ADC, Pin, mem32

# ------------------------------------------------------------------ configuration

# Bump on every change to this file. The app reflashes the board when the version
# it finds does not match the copy it ships. See VERSIONING above.
FW_VERSION = "2.0.0"

# GP26/27/28 map to ADC channels 0/1/2.
ADC_PIN = 26

# Samples per second. Must be 16000: the app's signal chain is specified as
# "capture at 16 kHz, zero-phase lowpass at 600 Hz, decimate by 8 to 2 kHz"
# (PcgSpec.CAPTURE_SAMPLE_RATE), and the divide-by-8 is exact.
#
# Capturing 8x faster than the 2 kHz output is not waste, it is free oversampling:
# the decimation filter averages the extra samples optimally, which buys real
# resolution back from the RP2040's noisy ADC (about 9 effective bits, not 12).
#
# The app still reads the rate out of each frame header rather than assuming, so a
# mismatch degrades quality instead of corrupting timing - but it will also
# reflash this file to fix it.
SAMPLE_RATE = 16000

# Samples per frame. 1024 at 16 kHz is a 64 ms budget for Python to send one buffer
# while DMA fills the other - a very comfortable margin over the ~2 ms the USB
# write of 2064 bytes actually takes, and the same margin the 512-at-8-kHz frame
# had before.
FRAME_SAMPLES = 1024

TEST_TONE_HZ = 100

# ------------------------------------------------------------------ RP2040 registers

ADC_BASE = 0x4004C000
ADC_CS = ADC_BASE + 0x00
ADC_RESULT = ADC_BASE + 0x04
ADC_FCS = ADC_BASE + 0x08
ADC_FIFO = ADC_BASE + 0x0C
ADC_DIV = ADC_BASE + 0x10

CS_EN = 1 << 0
CS_START_MANY = 1 << 3
CS_READY = 1 << 8
CS_AINSEL_SHIFT = 12

FCS_EN = 1 << 0
FCS_DREQ_EN = 1 << 3
FCS_EMPTY = 1 << 8
FCS_UNDER = 1 << 10
FCS_OVER = 1 << 11
FCS_THRESH_SHIFT = 24

DREQ_ADC = 36
ADC_CLOCK_HZ = 48_000_000

MAGIC = b"STH1"
HEADER_LEN = 16
FLAG_OVERRUN = 1 << 0

_out = sys.stdout.buffer


# ------------------------------------------------------------------ ADC + DMA

def adc_configure(pin_no, rate):
    """Free-running ADC into its FIFO, with DREQ so DMA can drain it."""
    ADC(Pin(pin_no))  # switches the pad to analog mode
    channel = pin_no - 26

    mem32[ADC_CS] = 0  # stop any previous run
    mem32[ADC_FCS] = 0

    # A conversion starts every (1 + INT + FRAC/256) cycles of the 48 MHz ADC
    # clock. INT lives in bits 23:8. Note this is 48e6/rate - 1, NOT
    # 48e6/(96*rate) - 1: the 96-cycle conversion time is a floor on how fast the
    # ADC can go, not a divisor of the interval.
    div = int(ADC_CLOCK_HZ // rate) - 1
    if div < 96:
        raise ValueError("rate too high for the RP2040 ADC (max ~500 kHz)")
    mem32[ADC_DIV] = div << 8

    # THRESH=1: assert DREQ as soon as one sample is available.
    mem32[ADC_FCS] = FCS_EN | FCS_DREQ_EN | (1 << FCS_THRESH_SHIFT)
    mem32[ADC_CS] = CS_EN | (channel << CS_AINSEL_SHIFT)
    while not (mem32[ADC_CS] & CS_READY):
        pass

    # Drain anything stale so the first frame starts clean.
    while not (mem32[ADC_FCS] & FCS_EMPTY):
        mem32[ADC_FIFO]

    mem32[ADC_CS] = mem32[ADC_CS] | CS_START_MANY


def adc_stop():
    mem32[ADC_CS] = 0
    mem32[ADC_FCS] = 0


def adc_overrun():
    """True if the FIFO overflowed or underflowed; clears the sticky bits."""
    fcs = mem32[ADC_FCS]
    if fcs & (FCS_OVER | FCS_UNDER):
        mem32[ADC_FCS] = fcs  # write-1-to-clear
        return True
    return False


class PingPongCapture:
    """
    Two DMA channels chained to each other, each filling its own buffer.

    While one channel is filling, the other's buffer is safe to read and its
    descriptor is safe to re-arm. Re-arming without triggering means the channel
    simply restarts when the running one chains into it, so capture never stops.
    """

    def __init__(self, samples):
        self.buffers = (bytearray(samples * 2), bytearray(samples * 2))
        self.samples = samples
        self.dma = (rp2.DMA(), rp2.DMA())
        # Each channel hands over to the other on completion.
        self.ctrl = (
            self.dma[0].pack_ctrl(
                size=1, inc_read=False, inc_write=True,
                treq_sel=DREQ_ADC, chain_to=self.dma[1].channel,
            ),
            self.dma[1].pack_ctrl(
                size=1, inc_read=False, inc_write=True,
                treq_sel=DREQ_ADC, chain_to=self.dma[0].channel,
            ),
        )
        self._arm(0)
        self._arm(1)

    def _arm(self, i):
        self.dma[i].config(
            read=ADC_FIFO, write=self.buffers[i], count=self.samples,
            ctrl=self.ctrl[i], trigger=False,
        )

    def start(self):
        self.dma[0].active(1)

    def wait(self, i, deadline_ms=2000):
        """Block until channel i has finished. Returns False on timeout."""
        start = time.ticks_ms()
        while self.dma[i].active():
            if time.ticks_diff(time.ticks_ms(), start) > deadline_ms:
                return False
        return True

    def rearm(self, i):
        self._arm(i)

    def close(self):
        for d in self.dma:
            try:
                d.active(0)
                d.close()
            except Exception:
                pass


# ------------------------------------------------------------------ framing

_header = bytearray(HEADER_LEN)
_header[0:4] = MAGIC


def send_frame(buf, seq, nsamples, rate, flags):
    struct.pack_into("<HHIHH", _header, 4, seq & 0xFFFF, nsamples, rate, flags, 0)
    _out.write(_header)
    _out.write(buf)


# ------------------------------------------------------------------ streaming

def stream_adc():
    """Continuous ADC capture until a stop command arrives."""
    cap = PingPongCapture(FRAME_SAMPLES)
    adc_configure(ADC_PIN, SAMPLE_RATE)
    cap.start()

    # Ctrl-C is 0x03, a perfectly legal byte in binary command traffic; without
    # this a stray 0x03 would raise KeyboardInterrupt mid-stream.
    micropython.kbd_intr(-1)
    seq = 0
    try:
        while True:
            for i in (0, 1):
                if not cap.wait(i):
                    return "timeout waiting for DMA"
                flags = FLAG_OVERRUN if adc_overrun() else 0
                send_frame(cap.buffers[i], seq, FRAME_SAMPLES, SAMPLE_RATE, flags)
                seq += 1
                cap.rearm(i)
                cmd = poll_command()
                if cmd in ("X", "Q"):
                    return cmd
                # Collect deterministically between frames rather than letting a
                # large pause land in the middle of one.
                gc.collect()
    finally:
        adc_stop()
        cap.close()
        micropython.kbd_intr(3)


def stream_test_tone():
    """
    Synthetic 100 Hz sine in place of the ADC.

    Lets the whole phone-side path - USB permission, framing, resampling,
    filtering, the classifier - be exercised with no analog hardware attached. If
    the app shows a clean 100 Hz tone here but nothing from the ADC, the problem
    is upstream of the firmware.
    """
    import math

    period = max(2, SAMPLE_RATE // TEST_TONE_HZ)
    table = bytearray(period * 2)
    for i in range(period):
        v = int(2048 + 1900 * math.sin(2 * math.pi * i / period))
        struct.pack_into("<H", table, i * 2, max(0, min(4095, v)))

    buf = bytearray(FRAME_SAMPLES * 2)
    phase = 0
    seq = 0
    frame_ms = int(1000 * FRAME_SAMPLES / SAMPLE_RATE)

    micropython.kbd_intr(-1)
    try:
        while True:
            for i in range(FRAME_SAMPLES):
                buf[i * 2] = table[phase * 2]
                buf[i * 2 + 1] = table[phase * 2 + 1]
                phase += 1
                if phase >= period:
                    phase = 0
            send_frame(buf, seq, FRAME_SAMPLES, SAMPLE_RATE, 0)
            seq += 1
            time.sleep_ms(frame_ms)
            cmd = poll_command()
            if cmd in ("X", "Q"):
                return cmd
    finally:
        micropython.kbd_intr(3)


# ------------------------------------------------------------------ command loop

_poll = select.poll()
_poll.register(sys.stdin, select.POLLIN)


def poll_command():
    """Next pending command character, or None. Never blocks."""
    if not _poll.poll(0):
        return None
    ch = sys.stdin.read(1)
    return ch.upper() if ch else None


def banner():
    # "fw=" is parsed by the app to decide whether to reflash - keep the field.
    print(
        "STETHOSCOPE ready fw={} proto=STH1 rate={} frame={} bits=12 align=right pin=GP{}".format(
            FW_VERSION, SAMPLE_RATE, FRAME_SAMPLES, ADC_PIN
        )
    )


def main():
    banner()
    while True:
        cmd = poll_command()
        if cmd is None:
            time.sleep_ms(20)
            continue
        if cmd == "?":
            banner()
        elif cmd == "S":
            if stream_adc() == "Q":
                return
            banner()
        elif cmd == "T":
            if stream_test_tone() == "Q":
                return
            banner()
        elif cmd == "Q":
            print("STETHOSCOPE exiting to REPL")
            return
        elif cmd == "X":
            pass  # already idle


if __name__ == "__main__":
    main()
