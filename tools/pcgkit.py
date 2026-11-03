"""
Host-side port of the app's PCG signal chain, so a recording made on a laptop is
byte-for-byte the same *kind* of file the phone produces.

This is a deliberate duplicate of four Kotlin files:

    analysis/pcg/PcgSpec.kt          -> SAMPLE_RATE / verify()
    analysis/pcg/Butterworth.kt      -> lowpass_zero_phase()  (kept, not used)
    analysis/pcg/Resampler.kt        -> resample()
    analysis/pcg/PcgPreprocessor.kt  -> process_samples()

Duplication is the point: the app runs on Android and cannot be invoked from a
bench script, but the whole reason to record on the PC is to get a file the
algorithm team can treat as equivalent to a phone capture. If you change the
signal chain in Kotlin, change it here too - `python tools/selftest.py` checks
this port against the same thresholds `PcgPreprocessorTest.kt` asserts.

**The chain does not filter** (2026-09-23, at the algorithm team's request): a
capture is resampled to 2 kHz and written, and nothing else happens to it. No
lowpass, no DC removal, no normalization. The only filter left is the anti-alias
FIR inside `resample`, which is part of rate conversion rather than a choice.

Pure standard library on purpose: no numpy, no scipy. A 30-second clip takes
roughly 20-40 seconds to process, which is fine for a bench tool and much better
than a dependency you have to install on a lab machine.
"""

import cmath
import math
import operator
import struct

# --------------------------------------------------------------------- the spec
# Mirrors PcgSpec.kt. The output format is the PhysioNet/CinC 2016 heart-sound
# format, which is what the training data is and what the classifier expects.

SAMPLE_RATE = 2000
CHANNELS = 1
BITS_PER_SAMPLE = 16
HEADER_SIZE = 44

CAPTURE_SAMPLE_RATE = 16000

# The app does not filter (2026-09-23, at the algorithm team's request). These two
# constants describe the lowpass that USED to be applied; nothing in the pipeline reads
# them. They are kept because `lowpass_zero_phase` is kept - see its docstring - and
# because `selftest.py` uses them to prove the filter still works if it is ever wanted
# back.
FORMER_LOWPASS_HZ = 600.0
FORMER_LOWPASS_ORDER = 4

MAX_INT16 = 32767.0
MIN_INT16 = -32768.0


# ------------------------------------------------------------------- WAV file IO

def read_wav(path):
    """
    Reads a 16-bit PCM WAV and returns (samples, sample_rate) with samples as a
    list of ints.

    Unlike the app's reader this walks the RIFF chunks properly instead of
    assuming a 44-byte header - training files from other tools often carry a
    LIST/INFO chunk, and skipping past it is the difference between a clean read
    and a burst of noise at the start. Stereo is folded to mono by averaging.
    """
    with open(path, "rb") as f:
        data = f.read()

    if len(data) < 12 or data[0:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError("%s is not a RIFF/WAVE file" % path)

    pos = 12
    fmt = None
    body = None
    while pos + 8 <= len(data):
        cid = data[pos:pos + 4]
        size = struct.unpack_from("<I", data, pos + 4)[0]
        payload = data[pos + 8: pos + 8 + size]
        if cid == b"fmt ":
            fmt = struct.unpack_from("<HHIIHH", payload, 0)
        elif cid == b"data":
            body = payload
        pos += 8 + size + (size & 1)  # chunks are word-aligned

    if fmt is None or body is None:
        raise ValueError("%s is missing a fmt or data chunk" % path)

    tag, channels, rate, _byte_rate, _align, bits = fmt
    if tag not in (1, 0xFFFE):
        raise ValueError("%s is not PCM (format tag %d)" % (path, tag))
    if bits != 16:
        raise ValueError("%s is %d-bit; only 16-bit PCM is supported" % (path, bits))

    count = len(body) // 2
    flat = struct.unpack_from("<%dh" % count, body, 0)
    if channels == 1:
        return list(flat), rate
    frames = count // channels
    mono = [0] * frames
    for i in range(frames):
        base = i * channels
        mono[i] = sum(flat[base:base + channels]) // channels
    return mono, rate


def write_wav(path, samples, sample_rate):
    """Writes a canonical 44-byte-header 16-bit mono PCM WAV."""
    body = struct.pack("<%dh" % len(samples), *samples)
    header = struct.pack(
        "<4sI4s4sIHHIIHH4sI",
        b"RIFF", 36 + len(body), b"WAVE",
        b"fmt ", 16, 1, CHANNELS, sample_rate,
        sample_rate * CHANNELS * BITS_PER_SAMPLE // 8,
        CHANNELS * BITS_PER_SAMPLE // 8, BITS_PER_SAMPLE,
        b"data", len(body),
    )
    with open(path, "wb") as f:
        f.write(header)
        f.write(body)


def verify(path):
    """
    Checks a file against the target spec by reading header bytes directly - the
    host twin of PcgSpec.verify(). Returns a dict; `problems` is empty when the
    file is compliant.
    """
    problems = []
    with open(path, "rb") as f:
        head = f.read(HEADER_SIZE)
        f.seek(0, 2)
        total = f.tell()

    if len(head) < HEADER_SIZE:
        return {"compliant": False, "sample_rate": 0, "bits": 0, "channels": 0,
                "frames": 0, "seconds": 0.0,
                "problems": ["File shorter than a WAV header"]}

    def ascii_at(a, b):
        return head[a:b].decode("ascii", "replace")

    u16 = lambda at: struct.unpack_from("<H", head, at)[0]
    u32 = lambda at: struct.unpack_from("<I", head, at)[0]

    if ascii_at(0, 4) != "RIFF":
        problems.append("Missing RIFF magic")
    if ascii_at(8, 12) != "WAVE":
        problems.append("Missing WAVE magic")
    if ascii_at(12, 16) != "fmt ":
        problems.append("Missing fmt chunk")
    if u32(16) != 16:
        problems.append("fmt chunk size is %d, expected 16 (canonical PCM)" % u32(16))
    if u16(20) != 1:
        problems.append("Format tag is %d, expected 1 (PCM)" % u16(20))
    if ascii_at(36, 40) != "data":
        problems.append("data chunk not at offset 36")

    channels, rate, bits = u16(22), u32(24), u16(34)
    if channels != CHANNELS:
        problems.append("%d channels, expected %d" % (channels, CHANNELS))
    if rate != SAMPLE_RATE:
        problems.append("%d Hz, expected %d" % (rate, SAMPLE_RATE))
    if bits != BITS_PER_SAMPLE:
        problems.append("%d-bit, expected %d" % (bits, BITS_PER_SAMPLE))

    data_bytes = u32(40)
    frames = data_bytes // (max(channels, 1) * (max(bits, 8) // 8))
    actual = total - HEADER_SIZE
    if data_bytes != actual:
        problems.append("data chunk claims %d bytes, file holds %d" % (data_bytes, actual))

    return {
        "compliant": not problems,
        "sample_rate": rate,
        "bits": bits,
        "channels": channels,
        "frames": frames,
        "seconds": frames / rate if rate else 0.0,
        "problems": problems,
    }


# ------------------------------------------------------------------- Butterworth
# Port of Butterworth.kt: a cascade of RBJ biquads with Butterworth Q values, run
# forward then backward (scipy's sosfiltfilt) so the phase response cancels.
# Phase linearity is not cosmetic here - S1-to-S2 timing is the feature a rhythm
# algorithm measures, and a causal filter smears it by a frequency-dependent amount.

class _Biquad:
    __slots__ = ("b0", "b1", "b2", "a1", "a2", "dc_gain")

    def __init__(self, b0, b1, b2, a0, a1, a2):
        self.b0, self.b1, self.b2 = b0 / a0, b1 / a0, b2 / a0
        self.a1, self.a2 = a1 / a0, a2 / a0
        denom = 1 + self.a1 + self.a2
        self.dc_gain = 0.0 if abs(denom) < 1e-12 else (self.b0 + self.b1 + self.b2) / denom

    def forward(self, x):
        """State seeded from the first sample held steady, not from zero, so the
        section does not ring on a signal that does not start at 0."""
        b0, b1, b2, a1, a2 = self.b0, self.b1, self.b2, self.a1, self.a2
        n = len(x)
        y = [0.0] * n
        x1 = x[0] if n else 0.0
        x2 = x1
        y1 = x1 * self.dc_gain
        y2 = y1
        for i in range(n):
            x0 = x[i]
            y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            y[i] = y0
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        return y


def _butterworth_qs(order):
    if order < 2 or order % 2:
        raise ValueError("Only even orders >= 2 supported (got %d)" % order)
    return [1.0 / (2.0 * math.cos(math.pi * (2 * k + 1) / (2.0 * order)))
            for k in range(order // 2)]


def _lowpass_sections(cutoff_hz, sample_rate, order):
    sections = []
    for q in _butterworth_qs(order):
        w0 = 2.0 * math.pi * cutoff_hz / sample_rate
        cos_w0 = math.cos(w0)
        alpha = math.sin(w0) / (2.0 * q)
        sections.append(_Biquad(
            (1 - cos_w0) / 2, 1 - cos_w0, (1 - cos_w0) / 2,
            1 + alpha, -2 * cos_w0, 1 - alpha,
        ))
    return sections


def _reflect_extend(x, pad_len):
    """
    Even (mirror) extension. filtfilt defaults to odd extension, which for an
    oscillatory signal near the top of the band can produce a seam of twice the
    amplitude; mirroring keeps the seam continuous in value.
    """
    if pad_len <= 0:
        return list(x)
    n = len(x)
    head = [x[pad_len - i] for i in range(pad_len)]
    tail = [x[n - 2 - i] for i in range(pad_len)]
    return head + list(x) + tail


def _run_zero_phase(x, sections, pad_len):
    y = _reflect_extend(x, pad_len)
    for s in sections:
        y = s.forward(y)
    y.reverse()
    for s in sections:
        y = s.forward(y)
    y.reverse()
    return y[pad_len: pad_len + len(x)]


def lowpass_zero_phase(x, sample_rate, cutoff_hz, order=FORMER_LOWPASS_ORDER):
    """
    Zero-phase lowpass. Running forward and backward squares the magnitude
    response, which is why the corner lands at -6 dB rather than -3 dB.

    **Not part of the pipeline any more.** Kept as the host twin of Butterworth.kt,
    which the app also keeps: it is what would be reinstated if band limiting is
    ever wanted back, and it is a filter with a known closed-form response, which
    makes it useful for checking measurements.
    """
    if len(x) < 2:
        return list(x)
    fc = min(cutoff_hz, 0.99 * sample_rate / 2.0)
    if fc <= 0:
        raise ValueError("Need a positive cutoff (got %s at %s Hz)" % (cutoff_hz, sample_rate))
    pad_len = min(len(x) - 1, int(20.0 * sample_rate / fc))
    return _run_zero_phase(x, _lowpass_sections(fc, sample_rate, order), pad_len)


def zero_phase_lowpass_db(hz, cutoff_hz, order=FORMER_LOWPASS_ORDER):
    """Textbook magnitude of a zero-phase Butterworth lowpass, in dB. Derived from
    the specification, not from the code above, so it cannot agree with a wrong
    implementation."""
    if cutoff_hz <= 0:
        return 0.0
    return -20.0 * math.log10(1.0 + max(hz / cutoff_hz, 0.0) ** (2 * order))


# --------------------------------------------------------------------- resampling
# Port of Resampler.kt: scipy.signal.resample_poly's recipe - upsample by `up`,
# Kaiser-windowed sinc lowpass, decimate by `down`, evaluated polyphase.

_HALF_LEN_MULTIPLIER = 10
_KAISER_BETA = 5.0


def _bessel_i0(x):
    total = 1.0
    term = 1.0
    half_sq = (x / 2.0) ** 2
    k = 1
    while k < 64:
        term *= half_sq / (k * k)
        total += term
        if term < 1e-18 * total:
            break
        k += 1
    return total


def _sinc(x):
    if abs(x) < 1e-12:
        return 1.0
    px = math.pi * x
    return math.sin(px) / px


def _kaiser_sinc_lowpass(half_len, cutoff, gain):
    n = 2 * half_len + 1
    fc = cutoff / 2.0
    i0_beta = _bessel_i0(_KAISER_BETA)
    h = [0.0] * n
    for i in range(n):
        t = float(i - half_len)
        ideal = 2.0 * fc * _sinc(2.0 * fc * t)
        r = 2.0 * i / (n - 1) - 1.0
        window = _bessel_i0(_KAISER_BETA * math.sqrt(max(1.0 - r * r, 0.0))) / i0_beta
        h[i] = ideal * window
    # firwin's scale=True: force the DC gain to exactly `gain`, otherwise the
    # windowing error shows up directly as an amplitude change.
    total = sum(h)
    if total != 0.0:
        correction = gain / total
        h = [v * correction for v in h]
    return h


def _ceil_div(a, b):
    return (a + b - 1) // b if a >= 0 else -((-a) // b)


def resample(x, fs_in, fs_out):
    """Rational rate conversion with a real anti-alias filter. Naive decimation
    would fold 1-8 kHz energy onto the heart sounds irreversibly."""
    if fs_in <= 0 or fs_out <= 0:
        raise ValueError("Sample rates must be positive (%s -> %s)" % (fs_in, fs_out))
    if fs_in == fs_out or not x:
        return list(x)

    g = math.gcd(fs_in, fs_out)
    up, down = fs_out // g, fs_in // g
    max_rate = max(up, down)
    half_len = _HALF_LEN_MULTIPLIER * max_rate
    h = _kaiser_sinc_lowpass(half_len, 1.0 / max_rate, float(up))

    n_in = len(x)
    h_len = len(h)
    out_len = -(-n_in * up // down)  # ceil
    y = [0.0] * out_len
    mul = operator.mul

    for m in range(out_len):
        # Position in the virtual upsampled signal, shifted by half_len to undo
        # the FIR's group delay so the output stays time-aligned with the input.
        center = m * down + half_len
        k_min = max(_ceil_div(center - h_len + 1, up), 0)
        k_max = min(center // up, n_in - 1)
        if k_min > k_max:
            continue
        if up == 1:
            # h index runs backwards as k runs forwards; slice + reverse keeps the
            # inner loop in C, which is worth a lot in pure Python.
            hs = h[center - k_max: center - k_min + 1]
            hs.reverse()
            y[m] = sum(map(mul, hs, x[k_min:k_max + 1]))
        else:
            acc = 0.0
            for k in range(k_min, k_max + 1):
                acc += h[center - k * up] * x[k]
            y[m] = acc
    return y


# ------------------------------------------------------------------ the pipeline
# Port of PcgPreprocessor.kt.
#
#   raw at 16 kHz -> decimate to 2 kHz -> int16 -> canonical 44-byte 2 kHz mono WAV
#                    (anti-alias only)   (clamped)
#
# That is the whole chain. The 600 Hz lowpass, the DC removal and the peak
# normalization were removed on 2026-09-23 at the algorithm team's request - they
# condition the signal themselves, and two conditioning stages in series would put
# the features somewhere neither side designed for.
#
# The resampler stays because rate conversion is not conditioning and its
# anti-alias filter is not optional: taking every eighth sample folds everything
# from 1 kHz to 8 kHz onto the heart sounds, where nobody downstream can separate
# it again.

def _to_int16(x):
    """
    Rounds to int16, clamping rather than wrapping.

    Works in int16 units throughout (+-32768), not the +-1.0 float convention the
    Python reference used - the input comes straight off a 16-bit WAV. Clamping
    matters on its own terms now that nothing scales the signal down first:
    wrapping would turn a loud S1 into a full-scale negative spike, which looks
    exactly like an arrhythmia.
    """
    out = [0] * len(x)
    for i, v in enumerate(x):
        if v > MAX_INT16:
            v = MAX_INT16
        elif v < MIN_INT16:
            v = MIN_INT16
        out[i] = int(math.floor(v + 0.5))  # Kotlin roundToInt semantics
    return out


def process_samples(raw, source_rate):
    """
    Resamples to 2 kHz and returns (int16_samples, report).

    Nothing else is done to the samples. Absolute level is preserved, so two
    recordings are comparable in loudness, and any DC offset the front end has
    arrives in the output untouched.
    """
    raw = [float(v) for v in raw]
    x = resample(raw, source_rate, SAMPLE_RATE)
    samples = _to_int16(x)
    peak = max((abs(v) for v in x), default=0.0)
    clipped = sum(1 for v in x if v > MAX_INT16 or v < MIN_INT16)
    return samples, {
        "input_sample_rate": source_rate,
        "input_samples": len(raw),
        "output_samples": len(samples),
        "duration_s": len(samples) / SAMPLE_RATE,
        "peak": peak,
        "clipped": clipped,
    }


def process_file(source, destination, source_rate=None):
    """Reads a WAV, runs the chain, writes a spec-compliant 2 kHz file."""
    samples, rate = read_wav(source)
    samples, report = process_samples(samples, source_rate or rate)
    write_wav(destination, samples, SAMPLE_RATE)
    return samples, report


# ------------------------------------------------------------------- measurement
# Everything below is analysis only - nothing here feeds the output file.

def _fft(values):
    """Iterative radix-2 Cooley-Tukey. len(values) must be a power of two."""
    n = len(values)
    if n & (n - 1):
        raise ValueError("FFT length must be a power of two (got %d)" % n)
    a = list(values)
    # Bit-reversal permutation.
    j = 0
    for i in range(1, n):
        bit = n >> 1
        while j & bit:
            j ^= bit
            bit >>= 1
        j |= bit
        if i < j:
            a[i], a[j] = a[j], a[i]
    size = 2
    while size <= n:
        step = cmath.exp(-2j * cmath.pi / size)
        half = size // 2
        for start in range(0, n, size):
            w = 1 + 0j
            for k in range(half):
                u = a[start + k]
                v = a[start + k + half] * w
                a[start + k] = u + v
                a[start + k + half] = u - v
                w *= step
        size <<= 1
    return a


def welch(x, fs, nfft=1024):
    """
    Averaged periodogram (Hann window, 50% overlap). Returns (freqs, power) where
    power is a one-sided power spectrum in squared input units.
    """
    if len(x) < nfft:
        nfft = 1 << max(3, int(math.log2(max(len(x), 8))))
    window = [0.5 - 0.5 * math.cos(2.0 * math.pi * i / (nfft - 1)) for i in range(nfft)]
    win_power = sum(w * w for w in window)
    hop = nfft // 2
    bins = nfft // 2 + 1
    acc = [0.0] * bins
    segments = 0
    for start in range(0, len(x) - nfft + 1, hop):
        seg = [complex(x[start + i] * window[i], 0.0) for i in range(nfft)]
        spec = _fft(seg)
        for k in range(bins):
            mag2 = spec[k].real ** 2 + spec[k].imag ** 2
            # Fold the negative frequencies in, except DC and Nyquist.
            acc[k] += mag2 * (1.0 if k in (0, bins - 1) else 2.0)
        segments += 1
    if not segments:
        return [k * fs / nfft for k in range(bins)], acc
    norm = 1.0 / (segments * win_power)
    return [k * fs / nfft for k in range(bins)], [v * norm for v in acc]


DEFAULT_BANDS = [(0, 25), (25, 50), (50, 100), (100, 200), (200, 400), (400, 1000)]


def band_energies(freqs, power, bands=DEFAULT_BANDS):
    """Fraction of total power in each band. Shape, not level, so it survives the
    pipeline's peak normalization."""
    total = sum(power) or 1.0
    out = []
    for lo, hi in bands:
        s = sum(p for f, p in zip(freqs, power) if lo <= f < hi)
        out.append((lo, hi, s / total))
    return out


def spectral_centroid(freqs, power):
    total = sum(power)
    if total <= 0:
        return 0.0
    return sum(f * p for f, p in zip(freqs, power)) / total


def shannon_envelope(x, fs, window_ms=20.0, hop_ms=10.0):
    """
    Average Shannon energy envelope - the standard PCG envelope, which emphasises
    medium-amplitude S1/S2 transients over both noise and clicks. Returns
    (envelope, envelope_rate_hz).
    """
    peak = max((abs(v) for v in x), default=0.0) or 1.0
    win = max(int(fs * window_ms / 1000.0), 1)
    hop = max(int(fs * hop_ms / 1000.0), 1)
    env = []
    for start in range(0, max(len(x) - win + 1, 1), hop):
        total = 0.0
        for i in range(start, min(start + win, len(x))):
            n = x[i] / peak
            sq = n * n
            if sq > 1e-12:
                total -= sq * math.log(sq)
        env.append(total / win)
    mean = sum(env) / len(env) if env else 0.0
    sd = math.sqrt(sum((v - mean) ** 2 for v in env) / len(env)) if env else 1.0
    env = [(v - mean) / (sd or 1.0) for v in env]
    return env, fs / hop


def estimate_bpm(x, fs, lo_bpm=35.0, hi_bpm=200.0):
    """
    Heart rate from the autocorrelation of the Shannon envelope. Returns
    (bpm, strength).

    `strength` is the autocorrelation peak measured **above the median of the
    curve**, not the raw peak. That distinction is the whole value of the number:
    band-limited noise autocorrelates at 0.2-0.3 across the entire lag range, so a
    raw peak of 0.27 says nothing, while a peak 0.27 *above its own background*
    means something really does repeat at that interval.

    Indicative, not clinical: with S1 and S2 both in the envelope the true cardiac
    period is often found at twice the strongest lag, so this reports the cardiac
    cycle when the sub-harmonic is convincing and the raw peak otherwise.
    """
    env, env_fs = shannon_envelope(x, fs)
    n = len(env)
    if n < 16:
        return 0.0, 0.0
    lo_lag = max(int(env_fs * 60.0 / hi_bpm), 1)
    hi_lag = min(int(env_fs * 60.0 / lo_bpm), n - 1)
    if hi_lag <= lo_lag:
        return 0.0, 0.0

    energy = sum(v * v for v in env) or 1.0
    corr = {}
    for lag in range(lo_lag, hi_lag + 1):
        corr[lag] = sum(env[i] * env[i + lag] for i in range(n - lag)) / energy

    values = sorted(corr.values())
    baseline = values[len(values) // 2]
    best_lag = max(corr, key=corr.get)
    best_height = corr[best_lag] - baseline

    # A periodic envelope correlates just as well at 2x and 3x its period as at the
    # period itself, and which of those wins is decided by noise. Walk down to the
    # smallest sub-multiple that is still nearly as strong, so the answer is the
    # fundamental beat rather than an arbitrary multiple of it.
    for k in (4, 3, 2):
        candidate = best_lag // k
        if candidate >= lo_lag and (corr.get(candidate, -1e9) - baseline) >= 0.8 * best_height:
            best_lag = candidate
            break
    return 60.0 * env_fs / best_lag, max(best_height, 0.0)


def rms(x):
    if not x:
        return 0.0
    return math.sqrt(sum(float(v) * v for v in x) / len(x))


def db(value, reference=MAX_INT16):
    if value <= 0:
        return float("-inf")
    return 20.0 * math.log10(value / reference)
