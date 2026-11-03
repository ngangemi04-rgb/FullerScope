"""
Checks the host port of the signal chain against the numbers the app's own Kotlin
tests assert.

Every case here is a translation of a test in
`app/src/test/java/com/capstone/stethoscope/analysis/pcg/PcgPreprocessorTest.kt`,
with the same thresholds. If this passes, a WAV written by `record_pcg.py` was
resampled the same way, and left alone in the same ways, as one written by the
phone.

Most of these assert the *absence* of processing. The app stopped filtering on
2026-09-23 (the algorithm team handles it), so a tone must come out at the
amplitude it went in at, a DC offset must survive, and 800 Hz must not be
attenuated. The one thing that must still happen is anti-aliasing.

    python tools/selftest.py
"""

import math
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pcgkit  # noqa: E402

FAILURES = []


def check(name, condition, detail=""):
    if condition:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s%s" % (name, ("  -- " + detail) if detail else ""))
        FAILURES.append(name)


def sine(freq_hz, sample_rate, seconds, amplitude=1000.0):
    n = int(sample_rate * seconds)
    return [amplitude * math.sin(2.0 * math.pi * freq_hz * i / sample_rate) for i in range(n)]


def interior_peak(x):
    """Peak over the middle half. Zero-phase filtering always leaves an artifact in
    the first and last few samples, so frequency-response claims measure the interior."""
    return max(abs(v) for v in x[len(x) // 4: len(x) * 3 // 4])


def main():
    tmp = tempfile.mkdtemp(prefix="pcgkit-selftest-")
    out = os.path.join(tmp, "out.wav")

    print("Format")
    samples, report = pcgkit.process_samples(sine(100.0, 16000, 2.0), 16000)
    pcgkit.write_wav(out, samples, pcgkit.SAMPLE_RATE)
    v = pcgkit.verify(out)
    check("output matches the PhysioNet spec", v["compliant"], "; ".join(v["problems"]))
    check("2 kHz, 16-bit, mono",
          (v["sample_rate"], v["bits"], v["channels"]) == (2000, 16, 1))
    check("duration survives (2.0 s)", abs(v["seconds"] - 2.0) < 0.01, "%.3f s" % v["seconds"])
    with open(out, "rb") as f:
        head = f.read(44)
    check("header is 44 bytes with data at offset 36",
          head[0:4] == b"RIFF" and head[8:12] == b"WAVE" and head[36:40] == b"data")

    print("Resampling")
    samples, report = pcgkit.process_samples(sine(100.0, 16000, 3.0), 16000)
    check("16 kHz decimates by exactly 8", len(samples) == 6000, "%d samples" % len(samples))
    check("in-band tone keeps its amplitude", abs(interior_peak(samples) - 1000) <= 30,
          "peak %d of 1000" % interior_peak(samples))

    samples, _ = pcgkit.process_samples(sine(100.0, 8000, 2.0), 8000)
    check("8 kHz input lands at 2 kHz", len(samples) == 4000, "%d samples" % len(samples))
    check("100 Hz survives 8 kHz -> 2 kHz", abs(interior_peak(samples) - 1000) <= 30,
          "peak %d of 1000" % interior_peak(samples))

    samples, _ = pcgkit.process_samples(sine(3000.0, 8000, 2.0), 8000)
    check("3 kHz does not alias down", interior_peak(samples) < 100,
          "peak %d" % interior_peak(samples))

    samples, _ = pcgkit.process_samples(sine(100.0, 44100, 1.0), 44100)
    check("non-integer ratio 44100 -> 2000", len(samples) == 2000, "%d samples" % len(samples))
    check("tone survives the 20/441 ratio", abs(interior_peak(samples) - 1000) <= 30,
          "peak %d of 1000" % interior_peak(samples))

    x = sine(100.0, 2000, 1.0, amplitude=4321.0)
    samples, _ = pcgkit.process_samples(x, 2000)
    same = all(abs(samples[i] - int(math.floor(v + 0.5))) == 0 for i, v in enumerate(x))
    check("a 2 kHz capture is copied through bit for bit", same)

    print("No filtering")
    for hz in (50.0, 100.0, 300.0):
        samples, _ = pcgkit.process_samples(sine(hz, 16000, 2.0), 16000)
        check("%.0f Hz passes through untouched" % hz,
              abs(interior_peak(samples) - 1000) <= 30,
              "peak %d of 1000" % interior_peak(samples))

    samples, _ = pcgkit.process_samples(sine(800.0, 16000, 2.0), 16000)
    p = interior_peak(samples)
    check("800 Hz is no longer attenuated", p > 700, "peak %d of 1000 -- is the lowpass back?" % p)

    samples, _ = pcgkit.process_samples(sine(1500.0, 16000, 2.0), 16000)
    check("1500 Hz still rejected (anti-alias)", interior_peak(samples) < 5,
          "peak %d" % interior_peak(samples))

    samples, _ = pcgkit.process_samples(sine(5.0, 16000, 4.0), 16000)
    check("baseline wander is deliberately left in", interior_peak(samples) > 900,
          "peak %d of 1000 -- a highpass is back" % interior_peak(samples))

    biased = [v + 2048.0 for v in sine(100.0, 16000, 2.0, amplitude=500.0)]
    samples, _ = pcgkit.process_samples(biased, 16000)
    mean = sum(samples) / len(samples)
    check("DC offset is preserved", abs(mean - 2048.0) < 20.0, "mean %.1f, expected ~2048" % mean)

    samples, _ = pcgkit.process_samples(sine(100.0, 16000, 2.0, amplitude=100.0), 16000)
    check("a quiet recording stays quiet", abs(interior_peak(samples) - 100) <= 10,
          "peak %d, expected ~100 -- something normalized it" % interior_peak(samples))

    print("Phase and amplitude")
    fs = 16000
    x = [0.0] * (fs * 2)
    burst_at = fs
    for i in range(320):
        x[burst_at + i] = 1000.0 * math.sin(2.0 * math.pi * 100.0 * i / fs)
    samples, _ = pcgkit.process_samples(x, fs)
    peak_index = max(range(len(samples)), key=lambda i: abs(samples[i]))
    check("resampling does not shift timing", abs(peak_index - pcgkit.SAMPLE_RATE) < 25,
          "burst landed at %d, expected %d" % (peak_index, pcgkit.SAMPLE_RATE))

    samples, report = pcgkit.process_samples(sine(100.0, 8000, 2.0, amplitude=100000.0), 8000)
    check("loud input clips rather than wraps",
          all(-32768 <= v <= 32767 for v in samples) and report["clipped"] > 0,
          "%d clipped" % report["clipped"])

    print("Butterworth, kept but unused")
    for hz, expected in ((600.0, -6.0), (800.0, -21.0), (1200.0, -48.0)):
        got = pcgkit.zero_phase_lowpass_db(hz, pcgkit.FORMER_LOWPASS_HZ,
                                           pcgkit.FORMER_LOWPASS_ORDER)
        check("textbook response at %.0f Hz is about %.0f dB" % (hz, expected),
              abs(got - expected) < 1.5, "%.1f dB" % got)
    filtered = pcgkit.lowpass_zero_phase(sine(800.0, 16000, 2.0), 16000,
                                         pcgkit.FORMER_LOWPASS_HZ)
    p = max(abs(v) for v in filtered[len(filtered) // 4: len(filtered) * 3 // 4])
    check("the filter still works if it is ever wanted back", 30 < p < 150,
          "800 Hz -> %.0f of 1000" % p)

    print()
    if FAILURES:
        print("%d FAILED: %s" % (len(FAILURES), ", ".join(FAILURES)))
        return 1
    print("All checks passed - the host pipeline matches the app's.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
