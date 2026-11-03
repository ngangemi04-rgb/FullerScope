"""
Record the stethoscope board straight into a WAV file, from a PC.

The board is a USB CDC device (VID 0x2E8A) running firmware/main.py, which
streams STH1 frames of raw 12-bit ADC samples at 16 kHz. This script does on a
laptop exactly what the Android app does on a phone:

    STH1 frames -> (raw - 2048) << 4 -> raw 16 kHz WAV
                                     -> decimate to 2 kHz (anti-alias only)
                                     -> PhysioNet-compatible PCG WAV

Nothing filters, normalizes or removes DC on the way through: the algorithm team
does its own conditioning, so the samples handed over are the ADC's own.

Two files come out. `_raw16k.wav` is what the ADC actually saw, kept because it
is the only artifact that can still answer a question about the analog front end.
`_pcg2k.wav` is the file to hand to the algorithm or compare against training
data - 2000 Hz, 16-bit, mono, canonical 44-byte header.

Usage:
    python tools/record_pcg.py --seconds 30 --out captures/subject1
    python tools/record_pcg.py --port COM5 --seconds 10 --tone
    python tools/record_pcg.py --list

Requires pyserial (`pip install pyserial`). Everything else is standard library.
"""

import argparse
import os
import struct
import sys
import time

import serial
from serial.tools import list_ports

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pcgkit  # noqa: E402

MAGIC = b"STH1"
HEADER_LEN = 16
FLAG_OVERRUN = 1
MID_SCALE = 2048
RP2040_VID = 0x2E8A
BAUD = 115200  # ignored by a CDC device, but pyserial wants a number


def find_port():
    """First RP2040-class CDC port, or None. Matching on VID beats guessing COM5."""
    for p in list_ports.comports():
        if p.vid == RP2040_VID:
            return p.device
    return None


def describe_ports():
    ports = list(list_ports.comports())
    if not ports:
        print("No serial ports found.")
        return
    print("Serial ports:")
    for p in ports:
        marker = "  <-- stethoscope board" if p.vid == RP2040_VID else ""
        vid = "%04X" % p.vid if p.vid is not None else "----"
        pid = "%04X" % p.pid if p.pid is not None else "----"
        print("  %-8s VID_%s PID_%s  %s%s" % (p.device, vid, pid, p.description, marker))


class FrameReader:
    """Incremental STH1 decoder - the host twin of StethoscopeFrameParser.kt."""

    def __init__(self):
        self.buf = bytearray()
        self.samples = []
        self.frames = 0
        self.dropped = 0        # frames missing, inferred from sequence gaps
        self.overruns = 0       # frames the firmware flagged as ADC overrun
        self.rate = None
        self.last_seq = None
        self.elapsed = 0.0        # seconds spent in the capture loop, set by capture()
        self.streamed = 0         # samples that had arrived when the window closed
        self.stream_seconds = 0.0  # first frame to last frame, excluding start-up
        self.stream_samples = 0    # samples delivered in that window

    def feed(self, chunk):
        if chunk:
            self.buf += chunk
        while True:
            i = self.buf.find(MAGIC)
            if i < 0 or len(self.buf) - i < HEADER_LEN:
                # Keep a partial magic at the tail so a split header still matches.
                if i < 0 and len(self.buf) > 3:
                    del self.buf[:-3]
                break
            seq, n, rate, flags, _ = struct.unpack_from("<HHIHH", self.buf, i + 4)
            need = HEADER_LEN + n * 2
            if n == 0 or n > 4096 or not (500 <= rate <= 192000):
                # Not a real header, just the magic appearing inside noise.
                del self.buf[: i + 4]
                continue
            if len(self.buf) - i < need:
                break
            body = self.buf[i + HEADER_LEN: i + need]
            self.samples.extend(struct.unpack("<%dH" % n, body))
            self.frames += 1
            self.rate = rate
            if flags & FLAG_OVERRUN:
                self.overruns += 1
            if self.last_seq is not None:
                gap = (seq - self.last_seq) & 0xFFFF
                if gap != 1:
                    self.dropped += max(gap - 1, 0)
            self.last_seq = seq
            del self.buf[: i + need]


def open_board(port, quiet=False):
    """
    Opens the port, gets the board into the firmware's command loop, and returns
    (serial, banner).

    Tries three times because the board can be in any of several states when you
    connect: idle at the command loop, still streaming from an interrupted run, or
    at the bare REPL. Ctrl-C then Ctrl-D covers all three, and asking for the
    banner with '?' covers the case where the reset banner was emitted before the
    port was listening.
    """
    sp = serial.Serial(port, BAUD, timeout=0.3)
    # MicroPython silently discards stdout unless DTR is asserted - the device
    # looks alive and sends nothing. This one line cost a day on the app side.
    sp.dtr = True

    banner = ""
    for attempt in range(3):
        if attempt == 0:
            sp.write(b"X")        # stop any stream left running by a previous run
            time.sleep(0.2)
            sp.reset_input_buffer()
            sp.write(b"\x03")     # Ctrl-C, break out of the REPL if that is where we are
            time.sleep(0.2)
            sp.write(b"\x04")     # Ctrl-D soft reset, which runs main.py
            time.sleep(1.5)
        else:
            sp.write(b"?")        # just ask; the firmware reprints its banner
            time.sleep(0.5)
        banner += sp.read(8192).decode("utf8", "replace")
        if "STETHOSCOPE" in banner:
            break

    if not quiet:
        for line in banner.splitlines():
            if "STETHOSCOPE" in line:
                print("  board: %s" % line.strip())
    if "STETHOSCOPE" not in banner:
        print("  WARNING: no STETHOSCOPE banner after three tries. The board may have no")
        print("           firmware on it - flash it with:")
        print("           python -m mpremote connect %s fs cp firmware/main.py :main.py" % port)
    return sp, banner


def capture(sp, seconds, command):
    """Streams for `seconds`, showing progress, and returns the FrameReader."""
    reader = FrameReader()
    sp.reset_input_buffer()
    sp.write(command)
    start = time.time()
    deadline = start + seconds
    next_tick = start + 1.0
    first_t = None
    first_n = 0
    try:
        while time.time() < deadline:
            reader.feed(sp.read(8192))
            # Note when the stream actually started. Measuring throughput from the
            # moment the command was sent charges the board for the board's own
            # start-up latency, which over a short run is several percent and enough
            # to trip the rate-mismatch warning on a board that is behaving.
            if first_t is None and reader.samples:
                first_t = time.time()
                first_n = len(reader.samples)
            now = time.time()
            if now >= next_tick:
                elapsed = now - start
                got = len(reader.samples)
                sys.stdout.write("\r  recording %4.1f/%.0f s  %d samples" % (elapsed, seconds, got))
                sys.stdout.flush()
                next_tick += 1.0
    finally:
        # Throughput has to be measured on the streaming window alone. Frames that
        # were already in flight when we stopped arrive during the flush below, and
        # counting them against the same elapsed time would report a rate higher
        # than the board can actually sustain.
        stop_t = time.time()
        reader.elapsed = stop_t - start
        reader.streamed = len(reader.samples)
        # ... and the rate is measured first-frame to last-frame, which is the
        # board's delivery rate rather than the board plus its start-up.
        reader.stream_seconds = (stop_t - first_t) if first_t else 0.0
        reader.stream_samples = max(len(reader.samples) - first_n, 0)
        sp.write(b"X")
        time.sleep(0.2)
        reader.feed(sp.read(1 << 20))
        sys.stdout.write("\r" + " " * 60 + "\r")
    return reader


def to_int16(raw12):
    """(raw - 2048) << 4: exact and lossless, maps offset-binary 12-bit onto the
    full signed 16-bit range. 32768 counts therefore equals 1.65 V."""
    return [(v - MID_SCALE) << 4 for v in raw12]


def report_capture(reader):
    raw = reader.samples
    n = len(raw)
    rate = reader.rate or pcgkit.CAPTURE_SAMPLE_RATE
    lo, hi = min(raw), max(raw)
    mean = sum(raw) / n
    measured = (reader.stream_samples / reader.stream_seconds) if reader.stream_seconds else 0.0
    print("Capture")
    print("  frames        : %d  (dropped %d, ADC overruns %d)"
          % (reader.frames, reader.dropped, reader.overruns))
    print("  declared rate : %d Hz" % rate)
    print("  measured rate : %.0f samples/s over %.1f s of streaming"
          % (measured, reader.stream_seconds))
    print("  raw 12-bit    : min %d  max %d  mean %.1f  span %d" % (lo, hi, mean, hi - lo))

    problems = []
    if measured and abs(measured - rate) / rate > 0.05:
        print("  WARNING: the board delivered %.0f samples/s but the frames declare %d Hz."
              % (measured, rate))
        print("           The WAV is written at the declared rate, so the timeline is stretched")
        print("           by %.0f%% and any heart rate read off it is wrong by the same amount."
              % (100 * abs(measured - rate) / rate))
        problems.append("rate mismatch")
    if reader.dropped:
        print("  WARNING: %d frames lost. Gaps in the timeline look like arrhythmia to a"
              % reader.dropped)
        print("           rhythm algorithm - close other serial software and re-record.")
        problems.append("dropped frames")
    if reader.overruns:
        print("  WARNING: %d ADC overruns. The board could not keep up with its own DMA."
              % reader.overruns)
        problems.append("overruns")
    if hi - lo < 20:
        print("  WARNING: the signal is essentially flat (span %d counts). The ADC pin is"
              % (hi - lo))
        print("           seeing a steady voltage - check the mic, the preamp, and that the")
        print("           filter output actually reaches GP26.")
        problems.append("flat signal")
    elif not (700 < mean < 3400):
        print("  WARNING: DC bias sits at %.0f of 4095, close to a rail. Mid-rail is 2048;"
              % mean)
        print("           a signal biased near 0 or 3.3 V loses half its swing to clipping.")
        problems.append("bias near a rail")
    elif hi >= 4090 or lo <= 5:
        print("  WARNING: the signal is hitting the ADC rails - reduce the preamp gain.")
        problems.append("clipping")
    else:
        # 32768 counts = 1.65 V, so one 12-bit count is 3.3/4096 V.
        print("  front end     : bias %.0f counts (2048 is mid-rail), swing %d counts = %.0f mV"
              % (mean, hi - lo, (hi - lo) * 3300.0 / 4096.0))
        if hi - lo < 200:
            print("  NOTE: that swing is only %.1f%% of the ADC range. The RP2040's ADC is worth"
                  % (100.0 * (hi - lo) / 4096.0))
            print("        about 9 effective bits, so a signal this small is being digitised with")
            print("        roughly %d usable levels - raise the preamp gain if this is a chest"
                  % max(int((hi - lo) / 8), 1))
            print("        recording rather than a quiet bench.")
    return problems


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", help="serial port, e.g. COM5 (default: auto-detect VID 2E8A)")
    ap.add_argument("--seconds", type=float, default=30.0, help="recording length (default 30)")
    ap.add_argument("--out", default="captures/capture",
                    help="output path prefix; _raw16k.wav and _pcg2k.wav are appended")
    ap.add_argument("--tone", action="store_true",
                    help="record the firmware's synthetic 100 Hz tone instead of the ADC - "
                         "a known input that exercises everything after the ADC")
    ap.add_argument("--list", action="store_true", help="list serial ports and exit")
    args = ap.parse_args()

    if args.list:
        describe_ports()
        return 0

    port = args.port or find_port()
    if not port:
        print("No RP2040 board found (looking for VID 2E8A).")
        print("Plug the board in, or pass --port explicitly. Ports seen right now:")
        describe_ports()
        return 2

    print("Port %s" % port)
    sp, _ = open_board(port)
    try:
        command = b"T" if args.tone else b"S"
        if args.tone:
            print("Recording the synthetic 100 Hz test tone (not the ADC).")
        reader = capture(sp, args.seconds, command)
    finally:
        try:
            sp.write(b"X")
        except Exception:
            pass
        sp.close()

    if not reader.samples:
        print("NO DATA RECEIVED.")
        print("  - Is another program holding the port (Thonny, mpremote, a serial monitor)?")
        print("  - Does the board have firmware/main.py on it? Check with mpremote.")
        return 1

    problems = report_capture(reader)
    print()

    rate = reader.rate or pcgkit.CAPTURE_SAMPLE_RATE
    # Trim the frames that arrived during the stop flush, so a 30 s request is a
    # 30 s file. Keeping them would be harmless but makes durations unpredictable.
    wanted = int(args.seconds * rate)
    pcm = to_int16(reader.samples[:wanted] if len(reader.samples) > wanted else reader.samples)

    out_dir = os.path.dirname(os.path.abspath(args.out))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    raw_path = args.out + "_raw16k.wav"
    pcg_path = args.out + "_pcg2k.wav"

    pcgkit.write_wav(raw_path, pcm, rate)
    print("Wrote %s  (%d samples at %d Hz)" % (raw_path, len(pcm), rate))

    print("Processing: decimate %d -> %d Hz (anti-alias only; no filtering, no DC removal,"
          % (rate, pcgkit.SAMPLE_RATE))
    print("            no normalization) ...")
    t0 = time.time()
    samples, report = pcgkit.process_samples(pcm, rate)
    pcgkit.write_wav(pcg_path, samples, pcgkit.SAMPLE_RATE)
    print("Wrote %s  (%d samples, %.1f s, %.1f s of CPU)"
          % (pcg_path, len(samples), report["duration_s"], time.time() - t0))
    if report["clipped"]:
        print("  WARNING: %d output samples hit the int16 rail. The front end is clipping;"
              % report["clipped"])
        print("           nothing scales the signal down any more, so this reaches the algorithm.")

    check = pcgkit.verify(pcg_path)
    if check["compliant"]:
        print("Spec check: OK - %d Hz, %d-bit, %d channel, 44-byte header (PhysioNet/CinC 2016)"
              % (check["sample_rate"], check["bits"], check["channels"]))
    else:
        print("Spec check: FAILED")
        for p in check["problems"]:
            print("  - %s" % p)
        return 1

    span = max(reader.samples) - min(reader.samples)
    if args.tone:
        pass
    elif span < 200:
        # With a swing this small the signal is a few ADC codes wide, and the
        # envelope autocorrelation will happily lock onto quantization patterns or
        # mains hum. Reporting a rate here would be inventing one.
        print("Rhythm     : not estimated - the signal is too small (%d counts) for the"
              % span)
        print("             result to mean anything.")
    else:
        bpm, strength = pcgkit.estimate_bpm(samples, pcgkit.SAMPLE_RATE)
        if strength > 0.2:
            print("Rhythm     : about %.0f bpm (periodicity %.2f)" % (bpm, strength))
        else:
            print("Rhythm     : no periodic beat found (periodicity %.2f) - either the placement"
                  % strength)
            print("             is off or the recording is dominated by noise.")

    print()
    print("Compare against training data with:")
    print("  python tools/compare_pcg.py %s <training.wav>" % pcg_path)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
