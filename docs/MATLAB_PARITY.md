> **Obsolete (2026-10-20).** This describes the removed `classifyHeartSound.m` port. The
> current algorithm is verified by `SegmenterParityTest` against the team's
> `testvectors.json` — see INTEGRATION.md.

# Validating the Kotlin port against `classifyHeartSound.m`

The app's classifier is a Kotlin port of the team's MATLAB. It reimplements an FFT, an
8th-order IIR bandpass, a Hilbert transform, MATLAB's kurtosis convention, and a windowed
periodogram. **A mistake in any of those does not crash — it shifts the probability.** So
the port is not trustworthy until its 15 feature values have been diffed against MATLAB's
on the same recording.

The unit tests establish that the primitives are individually correct (the bandpass matches
the analytic Butterworth response to 1e-6, the FFT matches a naive DFT, kurtosis matches
MATLAB's documented `kurtosis([1 2 3 4 5]) == 1.7`). They cannot establish that the *whole
chain* assembles those pieces the same way MATLAB does. Only this comparison can.

## Step 1 — get a recording out of the app

Record on the phone, open the recording, and let it analyse. Then:

```bash
adb shell run-as com.capstone.stethoscope ls cache/share
```

Use **Send via WhatsApp** or **Export audio (WAV)** to get the `.wav` onto a computer. That
file is 2 kHz mono 16-bit — the exact input the classifier saw.

> Note it is **not filtered at all** (since 2026-09-23). The app resamples to 2 kHz and
> does nothing else — no lowpass, no highpass, no DC removal, no normalization. The
> classifier still applies the MATLAB's own causal 25–400 Hz filter internally, which is
> now the only filter between the ADC and the features.
>
> Two consequences for parity work. Baseline wander and any DC offset are present in the
> exported WAV, so a MATLAB script that assumes a centred signal will disagree — centre it
> yourself, the way `HeartSoundFeatures` does. And the export is no longer peak-normalized,
> so amplitude-sensitive features will differ from a run against a normalized file; if a
> diff shows up there, check the level before suspecting the port.
>
> **Testing mode** (*Settings → Testing mode*) shows the signal and its spectrum at each
> stage plus a **Chain response** plot, so "did the app leave this alone" can be answered
> by looking: the trace should sit on 0 dB until the 1 kHz fold.

## Step 2 — get the app's numbers

On the phone: **Settings → Hardware diagnostics**, scroll to *Last analysis*. It lists all
15 features, the linear score, and the probability. Use *Share report* to send yourself the
text.

## Step 3 — get MATLAB's numbers

`classifyHeartSound.m` does not print the feature vector, so call the extractor directly.
Save this next to it and run it on the same WAV:

```matlab
function dumpFeatures(wavFilePath)
% Prints the 15 features so they can be diffed against the Kotlin port.
[featVec, featNames, nCycles] = extractFeatures15(wavFilePath);
if isempty(featVec)
    fprintf('Could not segment: %s\n', wavFilePath);
    return;
end

% Same trained values as classifyHeartSound.m
Bfinal = [0.3198665089, 0.3292575969, 0.05547848596, 0.2244341872, 0.07216835399, ...
    -0.1707131275, -0.3331966926, -0.1576021743, -0.3258372478, -0.2257892076, ...
    -0.3673123656, -0.2435962659, -0.07197655289, 0.345947688, 0.0711081641];
finalIntercept = -1.897832805;
muX = [0.3030783759, 0.09381626116, 0.467419521, 1.073117122, 5.403650056, ...
    4.015746294, 9.265364492, 2.976364548, 15.95498941, 3.131096465, ...
    7.076362247, 21.3487974, 0.9021058638, 4.57265577, 0.8501064356];
sigmaX = [0.04701300157, 0.05496215268, 0.3022055479, 0.7545141757, 2.350261995, ...
    1.302224558, 19.550045, 5.630110111, 28.24019599, 5.700007141, ...
    12.64676748, 38.05443955, 2.094204162, 13.65578026, 2.252103237];

featVecStd = (featVec - muX) ./ sigmaX;
linScore   = sum(featVecStd .* Bfinal) + finalIntercept;
prob       = 1 / (1 + exp(-linScore));

fprintf('Detected cardiac cycles: %d\n', nCycles);
fprintf('Probability of abnormal: %.6f\n', prob);
fprintf('Linear score: %.6f\n', linScore);
fprintf('--- features ---\n');
for i = 1:numel(featVec)
    fprintf('%-22s %18.10g\n', featNames{i}, featVec(i));
end
end
```

```matlab
dumpFeatures('StethoScope_Heart_....wav')
```

## Step 4 — compare

Line the two dumps up side by side.

| What you see | What it means |
|---|---|
| All 15 features agree to ~6 significant figures | The port is faithful. Ship it. |
| **Cycle count differs** | Segmentation diverged. Suspect the envelope: the Hilbert transform or the order-2 lowpass. Everything downstream is meaningless until this matches. |
| Interval features (1–3) agree, spectral ones (7–15) do not | The bandpass or the periodogram differs. Check the filter first. |
| Kurtosis features (4–6) are off by roughly 3 | Excess-versus-non-excess kurtosis. MATLAB does not subtract 3. |
| Everything off by a constant factor | Amplitude scaling. The band-power features are absolute, not scale-invariant, so the `/32768` conversion to MATLAB's `audioread` range matters. |
| Features agree, probability does not | Transcription error in the coefficients, `muX`, `sigmaX`, or the threshold. |

**Cycle count is the first thing to check.** If it differs, nothing else is comparable —
the features are averages over a different set of cycles.

## Known deviations

Two places where exact agreement is not expected:

- **Floating-point summation order.** Expect agreement to ~10 significant figures, not
  bit-exactness.
- **Peak picking at the envelope's very edges.** Both implementations use an FFT-based
  Hilbert transform at the signal's exact length, so they should agree, but a peak sitting
  within a sample or two of a detection threshold can fall differently. This shows up as a
  cycle count differing by one on borderline recordings — worth distinguishing from a real
  segmentation bug, which changes the count substantially.

## A caveat that has nothing to do with the port

Reported model performance is **AUC 0.81, sensitivity 75.7%, specificity 72.3%**. Roughly
**one abnormal recording in four is missed**, and nearly three in ten normal recordings are
flagged. A NORMAL result is weak evidence of a healthy heart. The app's result screen and
PDF both say so; keep it that way.
