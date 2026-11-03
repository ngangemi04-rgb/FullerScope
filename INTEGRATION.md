# Analysis Algorithm Integration Guide

The app analyses a heart-sound recording and reports **heart rate**, **rhythm
regularity** and **signal quality**. The production algorithm is the algorithm team's
heart-sound segmenter (2026-10-19 export): a Kotlin port of `segmentHeartSound.m`
living in `analysis/segmenter/`. The team's original package — reference MATLAB, README,
`spec.json`, `verifyPort.m` — is kept unchanged in `algorithm/segmenter/`.

This document describes the contract it satisfies, so a future algorithm can be
dropped in without touching anything else.

## The interface

```kotlin
interface HeartbeatClassifier {
    suspend fun classify(signal: HeartSignal): HeartSoundResult
}
```

Two implementations exist:

| Class | Purpose |
|---|---|
| `analysis/segmenter/HeartSoundSegmenter` | Production. Port of the team's `segmentHeartSound.m`. |
| `analysis/MockHeartbeatClassifier` | Demos with no hardware. Answers from recording *length*, not audio. |

Switching between them is one line in `di/AppContainer.kt`.

## Updating the model

The trained weights ship **verbatim** as `app/src/main/assets/segmenter/model.json` —
the exact file the team exports, no conversion step. To take a retrained model:

1. Replace `app/src/main/assets/segmenter/model.json`.
2. Replace `app/src/test/resources/segmenter/testvectors.json` with the matching vectors.
3. Run `gradlew.bat :app:testDebugUnitTest`. `SegmenterParityTest` checks every stage
   against the vectors, in the order the team's README lists them.

That works as long as the architecture uses the same layer types (conv1d, batchnorm,
relu, maxpool1d, flatten, dropout, dense, softmax). The Viterbi transition
probabilities and bandpass coefficients are constants in `HeartSoundSegmenter` — copy
them from a new `spec.json` if those change too.

## What you receive — `HeartSignal`

| Field | Type | Notes |
|---|---|---|
| `samples` | `ShortArray` | The raw capture: PCM samples, mono 16-bit, at `sampleRate`. Divide by 32768 for MATLAB's `audioread` scale. |
| `audioFile` | `File` | The same raw capture as a WAV, if reading the file directly is preferred. Deleted when `classify` returns. |
| `sampleRate` | `Int` | **Normally 16000** — but read it, do not assume it (see Notes). |
| `durationMs` | `Long` | Recording length in milliseconds. |

The `Recording` row alongside it also carries **`auscultationSite`** (one of AORTIC,
PULMONIC, ERBS, TRICUSPID, MITRAL, or null for "not stated") and a free-text
**`siteNote`**. The same heart produces a measurably different phonocardiogram at each
position, so if you ever train or calibrate per-site, that field is where the position
lives. Null genuinely means nobody recorded it — it is not a default, and should not be
treated as one.

### Guarantees about the audio

**You get the capture exactly as recorded — the app does nothing to it** (changed
2026-09-25). No resampling, no filtering, no DC removal, no normalization:

- **16000 Hz** (normally), mono, 16-bit signed PCM, little-endian, canonical 44-byte header.
- The ADC's own sample values. The USB path's `(raw − 2048) << 4` conversion centres a
  12-bit offset-binary capture, so any offset you see is the analog bias, not encoding.
- Absolute amplitude is preserved: a quiet capture stays quiet, and a front end driven
  too hard arrives clipped at the int16 rail.
- Baseline wander and everything up to 8 kHz reaches you.

**Getting to the rate your model wants is your job.** The segmenter's network learned
absolute durations at 2 kHz, so its first step resamples with
`analysis/pcg/Resampler.resample(x, sampleRate, 2000)` (polyphase, Kaiser-windowed sinc,
linear phase, delay-compensated). The team's MATLAB uses `resample()`; both are
anti-aliased, and `SegmenterParityTest` checks that a 16 kHz copy of the test recording
yields the same beats. **Never decimate by taking every 8th sample**: 16 kHz → 2 kHz
without an anti-alias filter folds everything from 1–8 kHz onto the heart sounds.

The segmenter's own 25–400 Hz zero-phase bandpass and 99.5th-percentile normalisation
are part of the algorithm, not the app.

## What the app shows right now — segmentation only (2026-10-23)

The Analysis screen and the PDF currently show **only the segmented plot**: the
segmenter's input signal shaded by phase (S1 / systole / S2 / diastole), from
`HeartSoundResult.segmentation`. Heart rate, rhythm, beat count and signal quality are
still computed and stored on each recording but **not displayed**, until that analysis
is ready to show. Bringing them back is a UI change in `ResultScreen`, `LibraryScreen`
and `ReportExporter` only.

## What you return — `HeartSoundResult`

```kotlin
HeartSoundResult(
    quality = SignalQuality.GOOD,       // GOOD | POOR
    rhythm = HeartRhythm.REGULAR,       // REGULAR | IRREGULAR | UNKNOWN
    heartRateBpm = 72f,                 // null when quality is POOR
    beatCount = 14,                     // S1 events detected
    confidence = 0.95f,                 // segmenter's mean top probability
    bpmVariability = 0.03f,             // CV of S1-to-S1 intervals; null when POOR
    segmentation = HeartStates(...),    // phase of every frame — what the plot draws
    note = "optional detail for the report",
)
```

How `HeartSoundSegmenter.toResult` decides:

- **Signal quality** is GOOD when `confidence ≥ 0.80` and at least two S1 events were
  found. The team's README says clean recordings score ~0.95 and that at 0.6 the
  segmentation "won't be trustworthy". **0.80 is our pick between the two — confirm it
  with the algorithm team.** On a POOR signal no heart rate or rhythm is shown, because
  the Viterbi decoder always emits a tidy-looking cycle, even from noise.
- **Rhythm** is IRREGULAR when the S1-interval CV is above 0.20 (the README: ≈0.03
  regular, >0.2 irregular), and needs at least 4 beats; otherwise UNKNOWN.
- There is deliberately **no normal/abnormal result**. The murmur screen below looks for
  one finding only.

## The murmur classifier (2026-10-31)

`analysis/murmur/MurmurClassifier.kt` ports the team's `classifyMurmur.m` (package in
`algorithm/murmur/`, constants verbatim in `assets/murmur/model.json`). As their README
says it should, it runs on the end of the segmenter with no preprocessing of its own:
`HeartSoundSegmenter.classify` hands it the segmenter's normalised 2 kHz signal and the
beat table from `MurmurClassifier.beatBoundaries(states, hop)`, and stores
`HeartSoundResult.murmur` (DETECTED / NOT_DETECTED / UNABLE + score).

- **Beat table from frame states — our convention, confirm with the team.** A beat is a
  full S1 → systole → S2 → diastole run followed by the next S1. Each phase starts at the
  centre (`1 + i*hop`) of its first frame; diastole ends at the centre of its *last* frame,
  which matches every row of their test vectors (diaEnd is one frame before the next S1).
  Their vectors sit on a 128-sample grid; the app segments at hop 32.
- **UNABLE** when fewer than three beats can be measured (theirs) **or the segmenter's
  quality is POOR** (ours — the score is only as good as the boundaries). The score is
  still stored on a POOR signal for the testing readout.
- `MurmurParityTest` runs all three stages of their vectors (flatness, full path,
  features → score) at their 1e-6 tolerance, plus the segmenter → murmur path end to end.
- Their measured performance on unseen patients is AUC 0.70, sensitivity 0.57,
  specificity 0.69 — below the ≥75 % / >80 % requirement. The screen and PDF say so
  next to every result.

`classify()` is a `suspend` function run off the main thread. The segmenter spreads the
CNN windows over all cores. Throw to signal failure; the UI shows the message and offers
Retry.

## Verifying a port

`HeartSoundSegmenter.segment()` returns every intermediate the team's `dbg` output has —
resampled, after-bandpass, percentile, normalised, frame centres, posteriors, decoded
states, S1 times. `SegmenterParityTest` checks each against `testvectors.json`. After any
analysis on the phone, Settings → Hardware diagnostics shows the last run's numbers
(frames, signal quality, S1 times, heart rate, state durations) for comparing a real
recording against the MATLAB.

```bash
gradlew.bat :app:testDebugUnitTest
```

## Notes

- Recordings are stored **encrypted**; the app decrypts to a temporary file, calls you,
  and deletes it afterwards. You never deal with encryption.
- Capture is specified at **16 kHz** — the firmware streams it — but a phone that
  refuses 16 kHz on the microphone fallback may negotiate something else. **Always
  resample from `signal.sampleRate`**, never from a hard-coded 16000.
- The PDF report's phonocardiogram trace shows the same raw signal you receive.
- The previous placeholder algorithm (`classifyHeartSound.m` port: normal/abnormal via
  logistic regression) was removed on 2026-10-20.
