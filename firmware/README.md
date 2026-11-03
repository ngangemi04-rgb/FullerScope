# StethoScope firmware — Raspberry Pi Pico

MicroPython firmware that samples the analog stethoscope signal and streams it to the
Android app over USB CDC serial.

## Why serial and not a USB microphone

The board runs MicroPython, whose RP2040 USB stack provides CDC (serial) and MSC only.
There is no USB Audio Class support, so Android's `AudioRecord` can never see this device
— which is exactly what enumeration shows: it appears as a COM port with no audio
endpoint. The app therefore reads it with the USB Host API instead.

That turned out to be an advantage rather than a workaround. With no audio HAL in the
path, nothing can refuse an unusual sample rate or silently resample: the app receives
exactly the samples the ADC produced, at exactly the rate the firmware chose.

## Install

**Normally you do not have to.** The Android app ships this file as an asset and flashes
it itself: on every attach it asks the board for its identify banner, compares the `fw=`
field against the version it carries, and reflashes over the MicroPython raw REPL when
they differ or when the board has no firmware at all. See
`app/src/main/java/com/capstone/stethoscope/audio/usb/FirmwareManager.kt`.

To install by hand anyway — from a PC, or to bootstrap a board the app cannot reach:

```bash
python -m pip install mpremote
python -m mpremote connect COM5 fs cp main.py :main.py
```

Replace `COM5` with the board's port (`python -m mpremote devs` lists them). `main.py`
runs automatically at boot.

### Two rules that keep the app's flashing working

1. **Bump `FW_VERSION` whenever you change this file.** The app parses it out of the
   source text of the copy it ships and compares it against what the board reports. Leave
   it stale and a board keeps running old code that the app believes is current.
2. **Keep `fw=<FW_VERSION>` in the `?` banner.** That field *is* the comparison. A banner
   without it reads as "unknown version", which is safe — the app reflashes — but a banner
   with a *wrong* one is not.

The APK's copy is generated from this file by Gradle (`syncFirmwareAsset` in
`app/build.gradle.kts`), so the two cannot drift.

## Verify

```bash
python firmware/test_stream.py COM5
```

This soft-resets the board, reads the identify banner, then exercises both the synthetic
test tone and the live ADC, reporting frame counts, dropped frames, ADC overruns, and the
analog signal's DC bias and swing. A healthy run looks like:

```
STETHOSCOPE ready fw=2.0.0 proto=STH1 rate=16000 frame=1024 bits=12 align=right pin=GP26

--- live ADC capture ---
  frames        : 31
  seq gaps      : 0
  ADC overruns  : 0
  throughput    : 7936 samples/s over 2.0s
  raw 12-bit    : min 1899  max 2178  mean 2047.7  span 279
```

The numbers that matter: **seq gaps and ADC overruns must both be 0**, throughput should
be close to the declared rate, and `mean` should sit near 2048 (mid-rail). A mean near 0
or 4095 means the bias network is pinning the signal against a rail and half the waveform
is being clipped away.

## Wiring

The microphone signal must reach `ADC_PIN` (default GP26 = ADC0) **after** the analog
bandpass filter, biased to mid-rail (~1.65 V). The RP2040 ADC is unipolar: a signal
centred on 0 V loses its entire negative half.

## Design notes

**No Python in the sample path.** The ADC free-runs at a rate set by its own clock
divider and pushes results into a hardware FIFO; two DMA channels chained to each other
drain that FIFO into alternating buffers. Python only ever touches a buffer DMA has
finished with.

A `while True: adc.read_u16()` loop would appear to work but would jitter by tens of
microseconds and stall for milliseconds whenever garbage collection ran — producing
timing artefacts that look exactly like an irregular heartbeat.

**The clock divider is `48e6 / rate - 1`.** A conversion starts every `1 + INT` cycles of
the 48 MHz ADC clock. Forum posts that divide by 96 as well are wrong; the 96-cycle
conversion time is a floor on how fast the ADC can go, not a divisor of the interval.

**Sample rate is 16 kHz, not the originally specified 4 kHz, and it is no longer a free
choice.** The app's signal chain is specified as *capture at 16 kHz → decimate by 8 to
2 kHz* (`PcgSpec.CAPTURE_SAMPLE_RATE`), and the ÷8 is exact.

Capturing 8× faster than the 2 kHz output is not waste, it is free oversampling: the
decimation filter averages the extra samples optimally, which buys back real resolution
from the RP2040's noisy ADC (roughly 9 effective bits, not 12). At 16 kHz the ADC clock
divider is 2999, comfortably above the 96-cycle floor, and the 32 kB/s that results is
nothing for CDC.

The app still reads the rate out of every frame header rather than assuming, so changing
`SAMPLE_RATE` degrades quality instead of corrupting timing — but the app will also notice
the version mismatch and reflash this file, so changing it locally without bumping
`FW_VERSION` will simply be undone.

**Samples are sent raw and unsigned.** The firmware does no scaling or centring, keeping
the loop a pure DMA-to-USB pass-through with no per-sample Python. The app centres and
scales on arrival (`(raw - 2048) << 4`), and `SignalConditioner` corrects any residual
offset exactly.

## Wire format

Little-endian throughout. One frame per DMA buffer:

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 4 | magic | `"STH1"` |
| 4 | 2 | seq | uint16, increments per frame, wraps |
| 6 | 2 | nsamples | uint16 |
| 8 | 4 | sampleRate | uint32, Hz |
| 12 | 2 | flags | bit0 = ADC FIFO overrun since last frame |
| 14 | 2 | reserved | zero |
| 16 | n×2 | samples | uint16, right-aligned 12-bit, 0–4095 |

`seq` lets the app detect frames lost to USB backpressure; `flags` reports samples lost
inside the ADC FIFO. Both are surfaced on the app's diagnostics screen — silent loss
would read as an arrhythmia.

## Commands

Single ASCII bytes on the serial link:

| Byte | Effect |
|---|---|
| `?` | Print the identify banner |
| `S` | Start streaming |
| `X` | Stop streaming, return to idle |
| `T` | Stream a synthetic 100 Hz tone instead of the ADC |
| `Q` | Quit to the MicroPython REPL |

The firmware idles until told to start, which keeps the REPL usable and stops it filling
buffers when nothing is listening.

`T` is worth knowing about: it exercises the entire phone-side path — USB permission,
framing, resampling, filtering, the classifier — with no analog hardware attached. If the
app shows a clean 100 Hz tone under `T` but nothing under `S`, the problem is upstream of
the firmware.

## Inspecting the board directly

Because it is a serial device, the REPL can be queried from any PC with no extra tooling:

```bash
python -m mpremote connect COM5 fs ls
python -m mpremote connect COM5 exec "import machine; print(machine.freq())"
```

Close Thonny, the Arduino IDE, or any serial monitor first — only one program can hold
the port.

## Possible improvements

- **Firmware-side oversampling.** Averaging several ADC readings per output sample would
  reduce noise further, but averaging in Python cannot keep up at these rates. It would
  need PIO or a C rewrite. Streaming at 16 kHz and letting the app's decimation filter do
  the averaging achieves the same thing for free.
- **Timestamping frames** would let the app detect clock drift between the board and the
  phone over a long recording.
