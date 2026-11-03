# Bench tools — record the board straight into a WAV, on a PC

Plug the stethoscope board into a laptop's USB port, record, and get a WAV that is
byte-compatible with the PhysioNet/CinC 2016 training data. No phone, no Android
build, no app install.

```bash
pip install pyserial
```

That is the only dependency. Everything else is the Python standard library.

> **Installing the app on phones** is a separate tool: `installer/FullerScope Installer.cmd`
> (PowerShell, no Python needed). `installer/make_package.ps1 [-Build]` packs it with adb
> and the current APK into `dist/FullerScope Installer.zip` for any Windows PC.

## Record

```bash
python tools/record_pcg.py --seconds 30 --out captures/subject1
```

The board is found by USB VID (0x2E8A), so no COM number is needed. Two files come
out:

| File | What it is | Use it for |
| --- | --- | --- |
| `subject1_raw16k.wav` | Exactly what the ADC saw, 16 kHz, unfiltered | Debugging the analog front end — the only artifact that can still answer a question about gain, bias or clipping |
| `subject1_pcg2k.wav` | 2000 Hz, 16-bit, mono, canonical 44-byte header | Handing to the algorithm team, comparing against training data |

Other options:

```bash
python tools/record_pcg.py --list              # show serial ports
python tools/record_pcg.py --port COM5 ...     # skip auto-detection
python tools/record_pcg.py --tone --seconds 5  # firmware's synthetic 100 Hz tone
```

`--tone` is the diagnostic to reach for first when a recording looks wrong: it
replaces the ADC with a known 1900-count sine inside the firmware, so everything
after the ADC gets exercised with no analog hardware in the loop. If the tone comes
through clean and the chest recording does not, the problem is in front of the ADC.

There is no normalization switch, because there is no normalization — see *Why the DSP is
duplicated here* below.

The recorder prints warnings for the failure modes that actually happen — dropped
frames, ADC overruns, a signal sitting on a rail, a swing too small to carry 9 bits,
and a delivered sample rate that does not match the rate in the frame headers. That
last one is worth understanding: the WAV is written at the *declared* rate, so if the
board under-delivers, the timeline stretches and every heart rate read off the file is
wrong by the same percentage.

## Testing mode (the main one)

```bash
python tools/testing_mode.py
```

The bench counterpart of the app's *Settings → Testing mode*, for working on the hardware
without a phone. A tkinter window with:

- a **live phonocardiogram** — scrolling, per-pixel min/max so an S1 transient cannot hide
  between plotted points, one gridline per second so the beat interval reads straight off.
  Timebase 1–20 s, auto-scale, pause;
- a **voltage view** — tick *Voltage view* and the same samples are drawn as absolute
  volts at the ADC pin against the 0–3.3 V rails, with mid-rail marked and the status line
  switching to bias, min/max, swing as a percentage of the rail, headroom and clipped
  count. The axis is **fixed at the full rail and never auto-ranges**: auto-ranging would
  make a 40 mV signal fill the plot and look healthy, which is the exact misreading this
  view exists to prevent. There is deliberately no pass/fail band — nobody has measured
  what a real chest produces through this front end, and a made-up threshold is worse than
  none;
- a **live spectrum** — averaged Welch/Hann, with the 400 Hz heart-sound edge and the
  1 kHz fold of the 2 kHz output marked, and a toggle for the full 8 kHz band;
- a **Record** button — 10/30/60 s (60 matches the app's default), writing the same pair
  of files the phone produces: `_raw16k.wav` as captured and `_pcg2k.wav` through the same
  pipeline, unconditioned;
- a **status line** with what decides whether the front end is working: peak in dBFS and
  millivolts at the ADC pin, DC offset, delivered vs declared sample rate, frame count,
  dropped frames, ADC overruns.

```bash
python tools/testing_mode.py --tone             # firmware's synthetic 100 Hz tone
python tools/testing_mode.py --seconds 10       # wider initial timebase
python tools/testing_mode.py --out captures/s1  # where recordings go
```

There is a **desktop shortcut** for it, "StethoScope Testing Mode", pointing at
`tools/launch_testing_mode.cmd`. The launcher sets the working directory to the project
root and deliberately keeps a console window: the tool prints the path of every recording
it writes, and prints why it gave up when no board is found. With `pythonw.exe` both
would vanish, and "nothing happened" is the worst possible response to an unplugged
board. With no board attached the shortcut prints `No RP2040 board found` and waits for a
keypress rather than closing instantly — that is the launcher working, not failing.

To recreate the shortcut (the Desktop here is OneDrive-redirected, so resolve it rather
than assuming `%USERPROFILE%\Desktop`):

```powershell
$s = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) 'StethoScope Testing Mode.lnk')); $s.TargetPath = 'C:\dev\StethoScope\tools\launch_testing_mode.cmd'; $s.WorkingDirectory = 'C:\dev\StethoScope'; $s.IconLocation = 'C:\dev\StethoScope\tools\testing_mode.ico,0'; $s.Save()
```

Auto-scaling, the DC-centred y-axis and the spectrum's dB-relative-to-peak are *display*
choices and touch nothing that gets written. Recordings are unconditioned — no filtering,
no DC removal, no normalization — exactly as the app hands them over.

A recording interrupted by Stop or by closing the window is still written, and labelled
`stopped early — … 2.0 s of 10` rather than `saved`. That distinction is derived from the
sample count at write time, not from a flag passed in by whoever triggered the write, so
the label cannot disagree with the file on disk.

## Listen live

```bash
python tools/listen.py
```

Plays the board through the PC's speakers or headphones as it streams, with a level
meter, until Ctrl-C. Audio goes out through Windows' own winmm (driven by ctypes in
`waveout.py`), so there is nothing to install beyond pyserial.

```bash
python tools/listen.py --gain 40            # fixed playback gain instead of auto
python tools/listen.py --tone               # the firmware's 100 Hz test tone
python tools/listen.py --mute               # meter only, no audio
python tools/listen.py --save captures/s1   # listen AND keep the recording
```

**The playback gain is not in the signal path.** A bench capture is a few dozen ADC
counts and is inaudible without something like 30–100× amplification, so the monitor
auto-ranges. That gain drives the sound card and nothing else: `--save` writes the same
unconditioned samples `record_pcg.py` would, and the app's own recordings are untouched.
Verified by measurement — a capture monitored at ×30 saved with peak 560 counts, exactly
what the ADC produced.

**Watch the meter, not your ears.** Heart sounds are 20–150 Hz and most laptop speakers
roll off well above that, so "I can't hear anything" is not evidence of no signal. The
meter reads dBFS against the ADC's own full scale plus millivolts at the pin, neither of
which includes the playback gain.

A few things it will tell you:

- **`audio ran dry N times`** — the sound card consumed faster than the board delivered.
  On a real capture this is cosmetic. On `--tone` it is constant and expected: the
  firmware synthesises that sine in Python and manages only ~11 kSa/s against the 16 k
  the card wants.
- **`delivered N samples/s against 16000 declared`** — measured first frame to last, so
  it excludes the board's start-up latency. Measured 15.7–16.0 kSa/s in practice.
- With `--save`, a delivery rate more than 5% off the declared rate is called out,
  because the WAV is written at the *declared* rate and a shortfall stretches the
  timeline.

## Compare against training data

```bash
python tools/compare_pcg.py captures/subject1_pcg2k.wav training/a0001.wav
```

Prints format, level, spectral shape by band, and an estimated heart rate for both
files side by side, then a verdict. Anything not already at 2 kHz is run through the
same pipeline first, so the comparison is like for like (`--raw` disables that).

The headline numbers:

- **Log-spectral distance** — one number for "is this the same kind of signal".
  Under 3 dB is a close match, over 8 dB is not. Level-independent by construction, which
  is what makes it the right comparison against a training file: that file's absolute
  level is an artifact of whoever recorded it.
- **Power below 200 Hz** — where S1 and S2 live. A recording with much less of it
  than the reference is picking up handling noise or has a poor seal.
- **Periodicity strength** — the envelope autocorrelation peak measured above its own
  background, so band-limited noise scores near zero instead of the 0.2–0.3 a raw
  correlation would give it. Below 0.2 means nothing in the recording repeats like a
  heartbeat.

What `compare_pcg.py` does **not** do is run the classifier. That lives in Kotlin
(`analysis/heartsound/MatlabHeartSoundClassifier.kt`) and its weights were fitted
against that exact feature extractor; a second Python implementation would be a new
model, not a check on this one.

## Why the DSP is duplicated here

`pcgkit.py` is a deliberate port of four Kotlin files:

| Kotlin | Python |
| --- | --- |
| `analysis/pcg/PcgSpec.kt` | `SAMPLE_RATE`, `verify()` |
| `analysis/pcg/Butterworth.kt` | `lowpass_zero_phase()` — kept, not used |
| `analysis/pcg/Resampler.kt` | `resample()` |
| `analysis/pcg/PcgPreprocessor.kt` | `process_samples()` |

The app runs on Android and cannot be called from a bench script, but the entire
point of recording on a PC is to get a file the algorithm team can treat as
equivalent to a phone capture. So the chain is the same one:

```
raw at 16 kHz ─► decimate to 2 kHz ─► int16 ─► canonical 44-byte 2 kHz mono WAV
                 (anti-alias only)   (clamped)
```

**Nothing filters, removes DC, or normalizes** — changed 2026-09-23, when the algorithm
team asked for the signal unconditioned. Two things follow that are worth knowing at the
bench:

- **Levels are real.** Two of your own recordings are comparable in loudness. A quiet
  capture stays quiet, so `record_pcg.py`'s swing warning is now the only thing standing
  between a badly-gained preamp and a quiet file nobody notices.
- **DC offset survives into the output.** `compare_pcg.py` reports it on its own row and
  subtracts it before measuring spectra, so a 2048-count bias does not swamp the band
  fractions.

**If you change the signal chain in Kotlin, change it here too.** `selftest.py` is the
guard: every case in it is a translation of a test in `PcgPreprocessorTest.kt`, with
the same thresholds.

```bash
python tools/selftest.py
```

Most of it asserts the *absence* of processing: that a tone comes out at the amplitude it
went in at, that a DC offset survives, that a quiet recording stays quiet, that 800 Hz is
**not** attenuated (the regression guard for the old 600 Hz lowpass), and that 5 Hz
baseline wander is left in. It also checks the things that must still happen: an exact ÷8,
1500 Hz rejected by the anti-alias filter, no timing shift, and clipping that clamps
rather than wraps.

## Verified

Run against the real board on 2026-09-23 (COM3, firmware 2.0.0, 16 kHz):
banner detected, 0 dropped frames, 0 ADC overruns, 15.7–16.0 kSa/s delivered against
16000 declared, spec-compliant output. The heart-rate estimator recovers 72, 78 and
64 bpm exactly from synthetic PCGs and scores pure noise at 0.11.

`listen.py` streamed the same board for 8 s with 0 dropped frames and no audio
starvation. On `--tone` the meter read **−0.7 dBFS / 1531 mV**, which is the firmware's
`2048 + 1900·sin` to within a count — an end-to-end check of the `(raw−2048) << 4`
conversion and the 3.3 V calibration, not just of the audio path.

`testing_mode.py` (2026-09-25) was verified against a **synthetic board** — the real one
was unplugged — that speaks the STH1 protocol with a 72 bpm heartbeat. Every control
exercised with no exceptions; recordings came out spec-compliant with DC and level
preserved, and the heart rate recovered as exactly 72 bpm. Its three write paths
(timer, Stop button, window closed mid-recording) were each checked for the right label.
**Still untested against real hardware.**

Not yet verified: a recording off a real chest. Bench swing was 40–60 ADC counts,
which is a quiet room, not a signal.
