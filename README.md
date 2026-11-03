# StethoScope

An Android app for **digital heart auscultation**: it records heart sounds from a
USB-C stethoscope, analyses them for **heart rate, rhythm regularity and signal
quality**, and lets a clinician review, store, and send the result over WhatsApp. Patient data is
**encrypted at rest**. Designed for field use in Ghana with **English / Twi**
language switching.

Built with **Kotlin + Jetpack Compose**, MVVM, Room, and a single-Activity /
Navigation-Compose structure.

## Download

**[⬇ Latest FullerScope.apk](https://github.com/ngangemi04-rgb/FullerScope/releases/latest/download/FullerScope.apk)**
· [all versions](https://github.com/ngangemi04-rgb/FullerScope/releases)

Every push to `main` is tested, built and published as a new release automatically
(`.github/workflows/release.yml`). On the phone, allow "Install unknown apps" for your
browser or file manager, open the APK, and tap Install; a newer APK installs over the
old one and keeps your recordings.

> **Capstone research prototype — not a medical device.** Its analysis has not been
> clinically validated and must not be used to make clinical decisions.

---

## App flow

```
Listen ──► record in place (60 s) ──► Review ──► Analyze ──► Result + Send
  │                │                    │                       │
  │                │                    └─ Retake ◄──────────────┤
  │                │                                             ├─ Send via WhatsApp
  └ waveform +     └──────── every recording is saved ───────────┤    (PDF + WAV)
    earbud                        to the Library                 ├─ Export PDF / WAV
    monitoring                                                   └─ Share sheet
```

1. **Listen** (start screen) — Enter optional patient info, then **Start Listening**
   for a live scrolling waveform and real-time audio through connected earbuds
   (passthrough is muted on the speaker to avoid feedback). A banner states which
   source is active, because a recording made from the phone's own microphone is
   otherwise indistinguishable from a real auscultation.
2. **Record** — Happens **on the same screen**, with a countdown bar pinned at the
   top. Auto-stops at 60 s (configurable) or on manual Stop.
3. **Review** — Play back the clip, then **Retake** or **Analyze**.
4. **Analysis** — Shows the **heart rate**, **regular / irregular rhythm**, beats
   detected and a signal-quality score. On a poor signal it withholds rate and rhythm
   and asks for a re-recording.
5. **Send** — **Send via WhatsApp** attaches the PDF report and the WAV and opens the
   contact picker. Both go as *documents*: WhatsApp re-encodes anything sent as an
   audio message to lossy Opus, whereas documents arrive byte-exact. Files are named
   for the patient and date so they are identifiable in a chat thread.
6. **Library** — Every recording is stored (encrypted) with its result.
7. **Settings** — Language, sample rate, input channel, hardware diagnostics, testing mode.
8. **Testing mode** — An engineering scope, off by default. See below.

## Signal chain

**The app does not filter the signal.** (2026-09-23 — the algorithm team asked for the
recording unconditioned; they do it on their side.)

```
  capture at 16 kHz ──► decimate ÷8 ──► int16 ──► canonical 44-byte 2 kHz / 16-bit / mono
                      (anti-alias only)  (clamped)      (PhysioNet CinC 2016 compatible)
```

- **16 kHz** ([`PcgSpec.CAPTURE_SAMPLE_RATE`]) is an exact ÷8 to the 2 kHz output, a rate
  every phone will open, and 8× oversampling of the RP2040's ~9-effective-bit ADC — noise
  the decimation filter averages down into real resolution. The firmware streams 16 kHz
  too, and the app reflashes it if it does not.
- **No lowpass, no highpass, no DC removal, no normalization.** Sample values reaching the
  classifier are the ADC's own. Baseline wander and DC offset reach the output WAV, and
  absolute level is preserved — so two recordings are comparable in loudness, which they
  were not while the pipeline normalized each one to 1 dB below full scale.
- **The one filter that remains is not a choice.** Decimation carries an anti-alias FIR
  (`Resampler`, linear-phase and group-delay compensated, so it adds no phase distortion).
  Removing it would fold everything from 1 kHz to 8 kHz onto the heart sounds
  irreversibly. `AliasProbeTest` asserts it is sufficient on its own now that the 600 Hz
  Butterworth no longer sits in front of it: a 1000-count tone at any frequency that
  would fold into the passband comes out at ≤ 2 counts.
- **The classifier's own 25–400 Hz Butterworth still runs**, inside `HeartSoundFeatures`.
  That is part of the ported MATLAB *feature extractor*, not of the app's signal chain,
  and its coefficients are what the trained weights were fitted against.
- `SignalConditioner` measures but no longer rewrites the capture, for the same reason:
  its DC subtraction and ×16 gain used to land on the very file the classifier reads.
- `Butterworth.kt` still exists and is still tested. Nothing calls it; it is what would be
  reinstated if band limiting is ever wanted back.

## Testing mode

*Settings → Testing mode* adds a scope to the Listen screen's toolbar. Unlike the clinical
waveform — which is deliberately axis-free, because a number on an auto-ranging display
invites being read as a measurement — every axis here carries real units and the x-axis
pinches down to individual samples or individual hertz.

It shows the raw capture and the **PCG (2 kHz)** — byte-for-byte what the classifier is
handed and what the exported report draws — each with its own averaged (Welch/Hann)
spectrum, guide lines at 400 Hz and the 1 kHz fold, and a tap cursor reporting the exact
value under it.

Two more chips answer different questions about the same samples:

- **Voltage** — the raw capture as absolute volts at the ADC pin against the 0–3.3 V
  rails, with mid-rail marked, plus bias, min/max, swing, headroom and clipped count. The
  axis is fixed at the full rail and never auto-ranges, because auto-ranging makes a 40 mV
  signal fill the plot and look healthy. No pass/fail band is drawn: nobody has measured
  what a real chest produces through this front end yet. Only available for a USB capture,
  whose `(raw − 2048) << 4` conversion is exact — a phone-microphone recording has no
  voltage behind it and says so.
- **Chain response** — divides the two spectra to give the measured transfer function of
  everything the app does, against the flat 0 dB line it is now specified to have. That is
  how "the app leaves the recording alone" gets *verified* rather than asserted, and how
  aliasing would show itself.

## Building

Open the folder in **Android Studio** (AI-261 / 2025.2 or newer), or from a terminal:

```bash
gradlew.bat :app:assembleDebug      # build
gradlew.bat :app:testDebugUnitTest  # run the unit tests
```

- compileSdk / targetSdk **36**, minSdk **26**
- Requires the Android SDK (path in `local.properties`) and JDK 17+ (Android Studio's
  bundled JBR works).
- The project lives at `C:\dev\StethoScope`, kept out of OneDrive, which locked
  `app/build/` mid-sync and broke incremental builds.

## Project layout

```
app/src/main/java/com/capstone/stethoscope/
├── analysis/            HeartbeatClassifier contract + mock
│   ├── segmenter/       Production algorithm: port of segmentHeartSound.m (CNN)
│   ├── heartsound/      FFT + MATLAB-convention DSP helpers
│   └── pcg/             Lowpass, decimation, spectrum, PhysioNet-format output
├── audio/               Recorder, LiveMonitor, capture negotiation, 12-bit
│   └── usb/             conditioning, USB diagnostics; CDC transport, frame parser,
│                        MicroPython raw REPL + firmware check/flash
├── data/                Room DB (encrypted entity), Recording model, repository
├── di/                  AppContainer (manual dependency injection)
├── export/              PDF report, filename policy, WhatsApp sharing
├── locale/              Per-app language switching (English / Twi)
├── security/            Crypto — Android Keystore AES-256-GCM at-rest encryption
└── ui/                  Compose screens: listen, review, result, library,
                         settings, diagnostics, testing scope

firmware/                MicroPython capture firmware for the Pico (shipped as an
                         APK asset by the Gradle build, so the app can flash it)
docs/                    Generated documentation + MATLAB parity procedure
```

## Hardware

The stethoscope is a **Raspberry Pi Pico (RP2040)** with a 12-bit ADC, running
MicroPython and connected through a USB-C **OTG** adapter. It presents a **USB CDC
serial** interface — *not* USB Audio Class — so the app reads it with the USB Host
API rather than `AudioRecord`. See **[HARDWARE.md](HARDWARE.md)** and
**[firmware/README.md](firmware/README.md)**.

- **Firmware.** The ADC free-runs into a hardware FIFO drained by ping-pong DMA, so
  no interpreted code sits in the sample path. Samples stream in framed packets
  carrying a sequence number and an overrun flag.
- **Firmware is managed by the app.** `firmware/main.py` is copied into the APK's assets
  by the Gradle build, and on every attach the app asks the board to identify itself and
  compares `fw=` against the version it ships. On a mismatch — or on a board with no
  firmware at all — it enters the MicroPython raw REPL, streams the image across in
  base64 chunks, verifies size and checksum *on the device*, renames it over `main.py`
  only then, soft-reboots, and confirms the new banner. A board carrying an older
  `main.py` does not fail loudly: it streams 8 kHz that the app dutifully resamples,
  producing a plausible-looking recording quietly built on half the intended resolution.
  This removes that failure mode, and the need for anyone in the field to own `mpremote`.
  Progress appears on the Listen screen; *Settings → Hardware diagnostics* has a forced
  reinstall for a board whose version is right but whose behaviour is not.
- **Diagnostics.** *Settings → Hardware diagnostics* dumps what the phone sees on the
  USB port, the rates it will accept, and the last recording's conditioning and
  transport statistics. Open it with the hardware plugged in before debugging.
- **12-bit correction.** `audio/SignalConditioner.kt` removes DC offset and applies an
  exact power-of-two gain so a right-aligned 12-bit source is restored to true 16-bit
  range rather than left 24 dB too quiet.
- **Microphone fallback.** With no stethoscope attached the app records from the phone
  microphone, which makes the whole flow testable without hardware.

## Analysis algorithm

`analysis/segmenter/HeartSoundSegmenter` is a Kotlin port of the algorithm team's
`segmentHeartSound.m` (2026-10-19): resample to 2 kHz, 25–400 Hz zero-phase bandpass,
a 55k-parameter CNN over 4096-sample windows, a Viterbi decoder constrained to
S1 → systole → S2 → diastole, then beat events. The weights ship verbatim as
`assets/segmenter/model.json`. Held-out performance reported by the team: **S1 F1
0.953 on 862 patients**. It measures rate and rhythm; it does **not** detect murmurs.

`SegmenterParityTest` runs the team's `testvectors.json` through every stage and
passes (10 S1 events, 60.3 bpm). `MockHeartbeatClassifier` remains available for demos;
switching is one line in `di/AppContainer.kt`. The contract, thresholds and how to drop
in a retrained model are in **[INTEGRATION.md](INTEGRATION.md)**.

## Encryption

Patient data is encrypted at rest with a hardware-backed **Android Keystore
AES-256-GCM** key (`security/Crypto.kt`, no third-party libraries):

- **Database:** patient name / ID / notes stored as encrypted ciphertext columns; the
  repository decrypts on read.
- **Audio:** WAV files stored as `*.wav.enc`, decrypted to a temporary cache file only
  when played, analyzed, or exported, then deleted.

Encryption has been observed working on a real device — an exported file came out
exactly 28 bytes smaller than its encrypted form, the 12-byte IV plus 16-byte
authentication tag. Surviving an app restart and device reboot is still unconfirmed.

## Localization

English and **Twi** (`tw`), switchable in Settings. The Twi strings in
`res/values-tw/strings.xml` are **AI-generated best-effort and must be verified by a
fluent Twi speaker** before clinical use — especially the result wording.
Untranslated keys fall back to English.

## Status

Runs end to end on target hardware (Moto G7 Power, Android 10) with the real
stethoscope attached: capture over USB CDC, preprocessing, classification, PDF
generation and WhatsApp delivery all verified. Outstanding work is listed in
[HARDWARE.md](HARDWARE.md) and the parity procedure above; the largest items are
characterising the analogue front end against a real chest and confirming the
classifier port matches MATLAB numerically.
