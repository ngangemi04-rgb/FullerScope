"""
Compare a recording against a training-data WAV.

    python tools/compare_pcg.py captures/subject1_pcg2k.wav training/a0001.wav

Prints a side-by-side report: file format, level, spectral shape, and rhythm,
followed by a verdict on whether the recording is in the same family as the
reference. The point is to answer "is what I just recorded the same *kind* of
signal the model was trained on" before anyone spends time on a diagnosis that
was never going to mean anything.

What it does NOT do is run the classifier. That lives in Kotlin
(analysis/heartsound/MatlabHeartSoundClassifier.kt) and its weights were fitted
against that exact feature extractor, so a second Python implementation of it
would be a new model, not a check on this one.

Both files are read at whatever rate they carry; anything that is not already
2000 Hz mono is run through the same pipeline as a capture first, so the
comparison is like for like. Use --raw to compare exactly as stored instead.
"""

import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pcgkit  # noqa: E402


def load(path, allow_process):
    """Returns (samples, rate, note) with the file conformed to the PCG spec."""
    samples, rate = pcgkit.read_wav(path)
    note = ""
    if rate != pcgkit.SAMPLE_RATE and allow_process:
        samples, _ = pcgkit.process_samples(samples, rate)
        note = "resampled %d -> %d Hz through the app pipeline" % (rate, pcgkit.SAMPLE_RATE)
        rate = pcgkit.SAMPLE_RATE
    return samples, rate, note


def measure(samples, rate):
    n = len(samples)
    peak = max((abs(v) for v in samples), default=0)
    clipped = sum(1 for v in samples if v >= 32767 or v <= -32768)

    # Subtract the mean before the spectral work. The pipeline no longer removes DC,
    # so a front end sitting on a 2048-count bias would otherwise dump all of that
    # into bin 0 and swamp every band fraction below. The offset itself is reported
    # on its own row, which is where it belongs.
    mean = sum(samples) / n if n else 0.0
    centred = [v - mean for v in samples]

    freqs, power = pcgkit.welch(centred, rate)
    bpm, strength = pcgkit.estimate_bpm(centred, rate)
    return {
        "samples": n,
        "seconds": n / rate if rate else 0.0,
        "rate": rate,
        "rms": pcgkit.rms(centred),
        "rms_db": pcgkit.db(pcgkit.rms(centred)),
        "peak": peak,
        "peak_db": pcgkit.db(peak),
        "crest_db": pcgkit.db(peak) - pcgkit.db(pcgkit.rms(centred) or 1e-9),
        "dc": mean,
        "clipped": clipped,
        "freqs": freqs,
        "power": power,
        "bands": pcgkit.band_energies(freqs, power),
        "centroid": pcgkit.spectral_centroid(freqs, power),
        "bpm": bpm,
        "bpm_strength": strength,
    }


def log_spectral_distance(a, b, lo=20.0, hi=600.0):
    """
    RMS difference between the two log spectra over the clinically useful band,
    after normalizing each to unit power. Level-independent by construction, which
    is what makes it the right comparison against training data: a PhysioNet file's
    absolute level is an artifact of whoever recorded it, so only shape is
    comparable. Roughly: under 3 dB is a close match, over 8 dB is a different kind
    of signal.
    """
    pa = [p for f, p in zip(a["freqs"], a["power"]) if lo <= f <= hi]
    pb = [p for f, p in zip(b["freqs"], b["power"]) if lo <= f <= hi]
    if not pa or len(pa) != len(pb):
        return float("nan")
    sa, sb = sum(pa) or 1.0, sum(pb) or 1.0
    total, count = 0.0, 0
    for x, y in zip(pa, pb):
        xa = max(x / sa, 1e-12)
        yb = max(y / sb, 1e-12)
        d = 10.0 * (math.log10(xa) - math.log10(yb))
        total += d * d
        count += 1
    return (total / count) ** 0.5


def row(label, a, b, fmt="%s", width=26):
    va = fmt % a if not isinstance(a, str) else a
    vb = fmt % b if not isinstance(b, str) else b
    print("  %-*s %18s %18s" % (width, label, va, vb))


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("recording", help="the WAV you recorded")
    ap.add_argument("reference", help="the training-data WAV to compare against")
    ap.add_argument("--raw", action="store_true",
                    help="compare the files exactly as stored, without conforming "
                         "either to 2 kHz first")
    args = ap.parse_args()

    for p in (args.recording, args.reference):
        if not os.path.exists(p):
            print("No such file: %s" % p)
            return 2

    print("Format")
    for path in (args.recording, args.reference):
        v = pcgkit.verify(path)
        state = "spec-compliant" if v["compliant"] else "; ".join(v["problems"])
        print("  %-40s %d Hz  %d-bit  %dch  %.1f s  [%s]"
              % (os.path.basename(path), v["sample_rate"], v["bits"], v["channels"],
                 v["seconds"], state))
    print()

    a_s, a_rate, a_note = load(args.recording, not args.raw)
    b_s, b_rate, b_note = load(args.reference, not args.raw)
    for path, note in ((args.recording, a_note), (args.reference, b_note)):
        if note:
            print("  note: %s -> %s" % (os.path.basename(path), note))
    if a_rate != b_rate:
        print("  WARNING: comparing %d Hz against %d Hz; spectra are not aligned."
              % (a_rate, b_rate))
    if a_note or b_note:
        print()

    a, b = measure(a_s, a_rate), measure(b_s, b_rate)

    print("  %-26s %18s %18s" % ("", "recording", "reference"))
    print("  " + "-" * 64)
    row("duration (s)", a["seconds"], b["seconds"], "%.1f")
    row("peak (dBFS)", a["peak_db"], b["peak_db"], "%.1f")
    row("RMS (dBFS)", a["rms_db"], b["rms_db"], "%.1f")
    row("crest factor (dB)", a["crest_db"], b["crest_db"], "%.1f")
    row("DC offset (counts)", a["dc"], b["dc"], "%.1f")
    row("clipped samples", a["clipped"], b["clipped"], "%d")
    row("spectral centroid (Hz)", a["centroid"], b["centroid"], "%.0f")
    print()

    print("Spectral shape (fraction of total power)")
    print("  %-26s %18s %18s" % ("", "recording", "reference"))
    print("  " + "-" * 64)
    for (lo, hi, fa), (_, _, fb) in zip(a["bands"], b["bands"]):
        row("%d-%d Hz" % (lo, hi), 100 * fa, 100 * fb, "%.1f %%")
    lsd = log_spectral_distance(a, b)
    print()
    row("log-spectral distance", lsd if lsd == lsd else float("nan"), "-", "%.1f dB")
    print()

    print("Rhythm (autocorrelation of the Shannon energy envelope)")
    print("  %-26s %18s %18s" % ("", "recording", "reference"))
    print("  " + "-" * 64)
    row("estimated rate (bpm)", a["bpm"], b["bpm"], "%.0f")
    row("periodicity strength", a["bpm_strength"], b["bpm_strength"], "%.2f")
    print()

    # ------------------------------------------------------------------ verdict
    print("Verdict")
    issues = []

    if a["peak"] == 0:
        issues.append("The recording is silent.")
    if a["clipped"] > len(a_s) // 1000 and a["clipped"] > 10:
        issues.append("%d clipped samples - reduce the preamp gain." % a["clipped"])
    if a["crest_db"] < 6 and b["crest_db"] > 10:
        issues.append("Crest factor is %.1f dB against the reference's %.1f dB: the recording "
                      "looks like continuous noise rather than discrete heart sounds."
                      % (a["crest_db"], b["crest_db"]))

    low_a = sum(f for lo, hi, f in a["bands"] if hi <= 200)
    low_b = sum(f for lo, hi, f in b["bands"] if hi <= 200)
    if low_a < 0.5 * low_b:
        issues.append("Only %.0f%% of the recording's power is below 200 Hz against %.0f%% in "
                      "the reference - S1/S2 energy lives down there, so this is probably "
                      "handling noise or a poor seal." % (100 * low_a, 100 * low_b))
    hf_a = sum(f for lo, hi, f in a["bands"] if lo >= 400)
    if hf_a > 0.25:
        issues.append("%.0f%% of the power sits above 400 Hz - likely rubbing, mains hum "
                      "harmonics or amplifier noise rather than heart sound." % (100 * hf_a))
    if a["bpm_strength"] < 0.2:
        issues.append("No convincing periodicity (strength %.2f); nothing in this recording "
                      "repeats like a heartbeat." % a["bpm_strength"])
    elif not (35 <= a["bpm"] <= 200):
        issues.append("Estimated rate %.0f bpm is outside anything physiological." % a["bpm"])
    if lsd == lsd and lsd > 8.0:
        issues.append("Spectral shape differs from the reference by %.1f dB RMS - the two "
                      "files are not the same kind of signal." % lsd)

    if not issues:
        print("  The recording is in the same family as the reference: same format, heart-sound")
        print("  energy distribution, and a plausible periodic beat. Reasonable to feed to the")
        print("  classifier.")
        if lsd == lsd:
            print("  Spectral shape differs by %.1f dB RMS (under 3 dB is close, over 8 dB is not)."
                  % lsd)
    else:
        for i in issues:
            print("  - %s" % i)
    print()
    print("  Note: levels are real now — the pipeline no longer normalizes, so two of your")
    print("  own recordings ARE comparable in loudness. Against a training file they are")
    print("  not: its level is whatever gain that lab used. Compare shape to the reference,")
    print("  compare level to your own other captures.")
    return 0 if not issues else 1


if __name__ == "__main__":
    sys.exit(main())
