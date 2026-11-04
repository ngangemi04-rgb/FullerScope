# Murmur classifier — port package

Four-feature logistic regression. Takes the audio you already feed the
segmenter, plus the beat boundaries the segmenter produces, and returns one
score per recording.

This plugs onto the end of `segmentHeartSound`. It needs no new preprocessing
and no new audio handling.

## Files

| File | What it is |
|---|---|
| `classifyMurmur.m` | the reference implementation. No toolboxes. If a port disagrees with this, this one is right |
| `murmurModel.json` | every constant: four weights, four centers, four scales, intercept, threshold, band limits |
| `testvectors.json` | what a correct port must reproduce, in three stages |
| `verifyPort.m` | runs the reference against the vectors |

## Inputs

```
score = classifyMurmur(x, Fs, seg, model)
```

- **`x`** — audio already bandpass filtered 25–400 Hz and amplitude
  normalized. Exactly the signal `segmentHeartSound` works on. Not raw audio.
- **`Fs`** — 2000 Hz for everything this was trained on.
- **`seg`** — M×5 matrix of sample indices, one row per complete heartbeat:

  ```
  [s1start  sysStart  s2start  diaStart  diaEnd]
  ```

  S1 runs `s1start .. sysStart-1`, systole `sysStart .. s2start-1`,
  S2 `s2start .. diaStart-1`, diastole `diaStart .. diaEnd-1`.

  Note this is **five** boundaries, not four. Four cannot separate S2 from
  diastole, and an earlier version of our own code got that wrong — the
  "diastole" window silently contained S2.

## Output

- `ok` — false when the recording cannot be measured. On the phone this is
  **"unable to analyze"**, which is a third outcome, not a negative. It
  happens when fewer than three usable beats are found.
- `score` — 0 to 1
- `isMurmur` — `score >= 0.1653` (read it from `murmurModel.json`, don't hardcode)

## The only dependency

A **real-input FFT**. Everything else is arithmetic — means, logs, square
roots. A 512-point radix-2 FFT is about forty lines and there are many
public-domain Kotlin implementations.

Stage 1 of the test vectors exists to check it in isolation, with no audio and
no model: three short signals and their expected flatness values. White noise
comes out near 1, a pure tone near 0. If those three pass, the hard part works.

## What the four features are

All four are ratios or fractions **within a single heartbeat**. That is
deliberate: it means microphone gain and frequency response divide out, and
it is why this model does not quietly learn to recognize the recording
equipment the way a CNN on raw audio does.

### 1. `diaFlat/S1` — largest weight, and negative

Spectral flatness of diastole divided by spectral flatness of S1, median over
beats.

**Flatness** is the geometric mean of the power spectrum over its arithmetic
mean, computed only between 25 and 400 Hz. Near 1 means every frequency
carries similar power, which is noise, which is turbulent flow. Near 0 means
the power sits in a few peaks.

Only the band matters. Outside it the signal is the bandpass filter's
stopband, and since the geometric mean is a mean of logarithms, those
near-zero bins would swamp everything.

The PSD's overall **scale cancels** in the ratio of the two means, so the port
does not need to match any particular normalization — only the same window,
overlap, transform length and frequency bins.

Welch parameters, per segment of length `n`:

```
w     = clamp(2^floor(log2(n/4)), 64, 512)     window length
nfft  = max(512, 2*w)
overlap = round(w/2)
window  = symmetric Hann:  0.5*(1 - cos(2*pi*k/(w-1))),  k = 0..w-1
```

Subtract the segment mean first. Average `|FFT|²` over segments. Keep bins
where 25 ≤ f ≤ 400 and the value is finite and positive. Need at least 8 bins,
otherwise the feature is undefined for that beat.

The weight is **negative**, which looks backwards until you see why: a loud
murmur raises the flatness of S1 itself, inflating the denominator, so a *low*
diastole-to-S1 ratio indicates a murmur.

### 2. `sysOccupancy`

Split systole into 20 equal bins. Count how many have a higher RMS than
diastole of the same beat. Divide by 20. Median over beats.

A holosystolic murmur fills the interval and scores near 1. A brief
mid-systolic one scores lower. A silent systole scores near 0.

Needs systole to be at least 80 samples.

### 3. `sysFlat/S1`

Same flatness measure as feature 1, systole over S1.

### 4. `sysEnvCorr`

Does systole's envelope repeat from beat to beat? A murmur comes from the same
obstruction every cycle; handling noise and breathing do not.

For each beat: take systole, **trim 12% off each end**, split into 24 equal
bins, take `log(RMS + eps)` of each. Drop beats whose 24 values have zero
variance. Need at least 3 usable beats. Correlate every pair of beats
(Pearson) and take the median of all pairwise correlations.

The trim is not optional. Systole starts just after S1 and ends just before
S2, so its first and last samples carry the tail of one and the onset of the
other. Those arrive identically on every beat and would give a high
correlation for every recording, including silent ones.

## The model

```
z = intercept
  + w[k] * (t[k](feature[k]) - center[k]) / scale[k]      for k = 1..4
```

`t[k]` is `log` for the two ratio features (`diaFlat/S1`, `sysFlat/S1`) and
identity for the other two, which are already bounded and dimensionless. The
`logTransform` array in `murmurModel.json` says which is which — read it
rather than hardcoding.

```
score = 1 / (1 + exp(-z))
```

Every constant is in `murmurModel.json` at full precision. Do not transcribe
them by hand.

## Segment length rules

A segment shorter than **129 samples** is unusable and its beat is skipped.
Specifically, for a segment from `a` to `b`:

```
a = max(1, round(a))
b = min(N, round(b) - 1)
skip the beat if (b - a) < 128
```

Note `b` is the boundary **minus one** — segments are half-open. Off-by-one
here will shift every measurement slightly and the test vectors will catch it.

MATLAB indexes from 1. Kotlin indexes from 0. The boundaries in `seg` are
1-based; subtract 1 when indexing a Kotlin array.

## How to verify

Run `verifyPort` in MATLAB first — it should pass at machine precision, which
confirms the package is intact.

Then work the three stages in order:

1. **Flatness** on the three synthetic signals. No audio, no model.
2. **Features to score** — pure arithmetic, 40 cases.
3. **Full path** — 8 recordings with audio, boundaries and expected output.

Taking them in that order localizes any disagreement to one of three places
rather than leaving it somewhere in the whole chain. Tolerance 1e-6 on every
value, and decisions must match exactly.

## What this does and does not do

It detects **murmurs**. It does not detect coronary artery disease, which is
silent, and it does not identify which valve is involved.

Trained on PhysioNet `training-b` — 459 recordings from 100 patients, 74 with a
murmur from 15 patients, all recorded on one 3M Littmann E4000.

Performance, from five-fold patient-disjoint cross-validation over all 100
patients:

| | |
|---|---|
| AUC | 0.769 |
| Sensitivity | 0.671 |
| Specificity | 0.775 |

The design requirement is sensitivity ≥ 75% and specificity > 80%. **This does
not meet it**, though it is close.

Three things to read alongside those numbers.

They are **cross-validated, not held out**. A 70/30 held-out split of the same
site gives AUC 0.657, but it rests on five patients with murmurs and its
interval runs [0.504, 0.829] — too wide to be useful. Cross-validation over
every patient is the better-powered estimate and the one quoted here.

The **threshold was chosen on the same cross-validated predictions** the
sensitivity and specificity are reported against. The AUC is unaffected, being
threshold-free, but that pair is mildly optimistic.

**One site, one stethoscope, 15 patients with murmurs.** Training on this site
alone rather than on five pooled sites raised cross-validated AUC from 0.70 to
0.77, because the other sites were contributing device variation rather than
signal. The flip side is that performance on any other hardware — including
this device — is unknown and cannot be estimated from it. Treat these numbers
as a development reference, not as a specification of what the device will do.
