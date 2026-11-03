# Heart sound segmentation — port package

Everything needed to reimplement the CNN segmenter in Kotlin (or anywhere else).

**Send Nick this whole folder.**

| File | What it is |
|---|---|
| `segmentHeartSound.m` | **The specification.** One self-contained function, no toolboxes, no dependencies on the rest of the project. Port it top to bottom. |
| `model.json` | 55,316 weights, biases and batch-norm statistics. 721 KB. |
| `testvectors.json` | A real recording with the expected output of *every* stage. 1.7 MB. |
| `verifyPort.m` | Proves the reference matches the trained network. Already passing. |
| `spec.json` | The same constants as machine-readable JSON, if that's easier than reading the .m. |

## Start here

`segmentHeartSound.m` is written to be ported, not to be fast. It deliberately
avoids the Deep Learning Toolbox — the convolutions, batch-norm, pooling, dense
layers and softmax are all written out explicitly so each has an obvious Kotlin
equivalent. Read it in order; the four stages are marked with banner comments.

```matlab
out = segmentHeartSound(audio, Fs);
% out.s1Times      S1 onsets, seconds
% out.bpm          heart rate
% out.states       1=S1 2=systole 3=S2 4=diastole, per frame
% out.frameTimes   seconds, matches out.states
% out.confidence   mean top probability (see below)
```

## The four stages

**1. Preprocessing.** Resample to 2 kHz → 4th-order Butterworth bandpass
25–400 Hz, **zero phase** → divide by the 99.5th percentile of |x| and clip to ±8.

**2. Sliding window.** Reflect-pad by 2048, then take 4096-sample windows every
32 samples. Each window goes through the network and produces 4 probabilities.

**3. Viterbi decode.** The heart only moves S1 → systole → S2 → diastole → S1.
Each frame may stay or advance; nothing else is legal.

**4. Events.** Each run of consecutive frames in one state becomes **one**
detection, at the most confident frame in that run. BPM from S1-to-S1 intervals.

## Five things that will silently break a port

**Resampling is mandatory.** The network learned absolute durations — S1 is
128 ms, which is 256 samples at 2 kHz. Feed it 16 kHz audio and every sound
appears 8× longer than anything it saw in training.

**The filter must be zero-phase.** Filter forward, reverse the signal, filter
again, reverse back. A one-directional filter shifts every sound later and all
detections come out systematically late.

**Convolution here is cross-correlation** — no kernel flip. Same convention as
PyTorch and TensorFlow. Weights in `model.json` are flattened
`[kernel][inChannel][outChannel]`.

**The flatten order is column-major.** The 16×16 feature map flattens as *all 16
time positions for channel 1, then all 16 for channel 2*, and so on — not
position-by-position. Getting this backwards produces plausible-looking garbage.

**Dropout layers are identity at inference.** They appear in `model.json` for
completeness. Do not implement them.

## Verifying the port

`testvectors.json` holds a 10-second recording and the expected result of every
stage: `afterResample`, `afterBandpass`, `percentile995`, `afterNormalise`,
`singleWindow` (one window's input and its 4 outputs), `frameCentres`,
`posteriors`, `decodedStates`, `detectedS1Seconds`, `heartRateBpm`.

Check them **in order**. If `afterBandpass` is wrong, nothing downstream will
match and debugging from the final output is miserable. `singleWindow` isolates
the network from everything else — get that right first.

Expected final result for the supplied vector: **10 S1 events, 60.3 bpm.**

Don't chase exact float equality. The trained network runs in single precision
and your port probably won't; `verifyPort.m` shows differences up to 3e-2 on
frames where the network is genuinely undecided, while confident frames agree to
1e-4. **The bar is that the decoded states match and S1 times land within a few
milliseconds** — an S1 interval is ~128 ms, so a 5 ms discrepancy changes nothing.

## Performance

The MATLAB reference runs ~2× slower than real time because the convolutions are
written as readable nested loops. A straightforward Kotlin implementation will be
far quicker, and there's an easy lever: **`HOP` is the cost driver.** At 32 it
gives 16 ms resolution; 64 halves the compute and 128 quarters it, with
proportionally coarser detection timing. For a button-press-then-analyse flow,
32 is fine. For live streaming, start at 64.

Model size is 55,316 parameters — about 220 KB as float32. No inference runtime
needed; the whole forward pass is ~200 lines.

## For the app UI

`out.states` and `out.frameTimes` are what you want for a segmentation overlay —
colour the waveform by state. `out.s1Times` places beat markers. `out.bpm` and
`out.bpmCV` give heart rate and its variability (≈0.03 is regular, >0.2 irregular).

**Display `out.confidence`.** It's the mean top probability across frames: ~0.95
on good recordings, and it falls sharply on poor ones. A recording at 0.6 will
still produce a tidy-looking segmentation, because the Viterbi decoder always
emits an ordered cycle — but it won't be trustworthy. Surfacing it as a quality
indicator ("good signal" / "try again") is the cheapest way to stop the app
confidently showing nonsense.

## What this is and isn't

This segments heart sounds — it locates S1, systole, S2 and diastole. Held-out
performance on 862 patients never seen in training: **S1 F1 0.953**
(precision 0.955, recall 0.952).

It does **not** classify normal vs abnormal. That's a separate model, and it does
not currently generalise across recording sites well enough to ship.
