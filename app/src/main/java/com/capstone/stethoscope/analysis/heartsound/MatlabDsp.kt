package com.capstone.stethoscope.analysis.heartsound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The handful of MATLAB signal-processing primitives `classifyHeartSound.m` relies on,
 * reimplemented to match MATLAB's definitions rather than the nearest convenient
 * equivalent.
 *
 * Fidelity matters more than usual here: the model's 15 coefficients were fitted against
 * features produced by *these specific* operations, so a filter with a slightly different
 * phase response or a kurtosis with a different bias convention shifts every feature away
 * from the distribution encoded in `muX`/`sigmaX`. Three places where the obvious Kotlin
 * choice would have been wrong are called out below.
 */
internal object MatlabDsp {

    /** One second-order section, coefficients already normalised by a0. */
    data class Sos(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double,
    )

    // ------------------------------------------------------------------ filtering

    /**
     * Causal filtering with zero initial conditions — MATLAB's `filter`, **not**
     * `filtfilt`.
     *
     * The script uses `filter`, so the signal it segments is phase-shifted by the
     * bandpass. Substituting zero-phase filtering would move every envelope peak relative
     * to the trained model's expectations, which is exactly what the interval features
     * measure. The app's own PCG pipeline uses `filtfilt` for a different purpose and must
     * not be reused here.
     */
    fun filter(sections: List<Sos>, x: DoubleArray): DoubleArray {
        var signal = x
        for (s in sections) {
            val y = DoubleArray(signal.size)
            var x1 = 0.0
            var x2 = 0.0
            var y1 = 0.0
            var y2 = 0.0
            for (i in signal.indices) {
                val x0 = signal[i]
                val y0 = s.b0 * x0 + s.b1 * x1 + s.b2 * x2 - s.a1 * y1 - s.a2 * y2
                y[i] = y0
                x2 = x1; x1 = x0
                y2 = y1; y1 = y0
            }
            signal = y
        }
        return signal
    }

    /**
     * Equivalent of `butter(order, [lowHz highHz]/nyquist, 'bandpass')`.
     *
     * MATLAB's bandpass is a genuine `2 * order`-pole design: the normalised lowpass
     * prototype poles are frequency-transformed by `lp2bp`, each splitting into two, then
     * mapped to the z-plane bilinearly. A highpass-then-lowpass cascade of the same order
     * is *not* the same filter — it happens to be close for a wide band like 25-400 Hz,
     * but "close" is not what a trained model wants.
     */
    fun butterBandpass(order: Int, lowHz: Double, highHz: Double, fs: Double): List<Sos> {
        require(order % 2 == 0 && order >= 2) { "order must be even and >= 2" }
        val nyq = fs / 2.0
        // MATLAB pre-warps with fs = 2, so u = 2*fs*tan(pi*W/fs) = 4*tan(pi*W/2).
        val u1 = 4.0 * tan(PI * (lowHz / nyq) / 2.0)
        val u2 = 4.0 * tan(PI * (highHz / nyq) / 2.0)
        val bw = u2 - u1
        val wo = sqrt(u1 * u2)

        val sections = mutableListOf<Sos>()
        for (p in prototypePolesUpperHalf(order)) {
            // lp2bp: scale by bw/2, then each pole splits about the band centre.
            val q = Complex(p.re * bw / 2.0, p.im * bw / 2.0)
            val disc = (q * q - Complex(wo * wo, 0.0)).sqrt()
            for (s in listOf(q + disc, q - disc)) {
                val z = bilinear(s)
                // A bandpass has zeros at DC and Nyquist, i.e. z = +1 and z = -1,
                // giving the numerator (z - 1)(z + 1) = z^2 - 1 for every section.
                sections += Sos(
                    b0 = 1.0, b1 = 0.0, b2 = -1.0,
                    a1 = -2.0 * z.re, a2 = z.re * z.re + z.im * z.im,
                )
            }
        }
        // Butterworth bandpass is unity at the geometric band centre.
        val centre = 2.0 * atan(wo / 4.0)
        return normalise(sections, centre)
    }

    /** Equivalent of `butter(order, cutoffHz/nyquist, 'low')`. */
    fun butterLowpass(order: Int, cutoffHz: Double, fs: Double): List<Sos> {
        require(order % 2 == 0 && order >= 2) { "order must be even and >= 2" }
        val nyq = fs / 2.0
        val wo = 4.0 * tan(PI * (cutoffHz / nyq) / 2.0)

        val sections = mutableListOf<Sos>()
        for (p in prototypePolesUpperHalf(order)) {
            // lp2lp is a plain scaling; the conjugate pole is implied by the real biquad.
            val z = bilinear(Complex(p.re * wo, p.im * wo))
            // All zeros sit at s = infinity, which bilinear maps to z = -1: (z + 1)^2.
            sections += Sos(
                b0 = 1.0, b1 = 2.0, b2 = 1.0,
                a1 = -2.0 * z.re, a2 = z.re * z.re + z.im * z.im,
            )
        }
        return normalise(sections, 0.0) // unity at DC
    }

    /**
     * Upper-half-plane poles of the normalised Butterworth lowpass prototype. Only half
     * are returned because each real biquad accounts for a conjugate pair.
     */
    private fun prototypePolesUpperHalf(order: Int): List<Complex> =
        (1..order)
            .map { k ->
                val ang = PI * (2.0 * k + order - 1.0) / (2.0 * order)
                Complex(cos(ang), sin(ang))
            }
            .filter { it.im > 0.0 }

    /** Bilinear transform with MATLAB's `fs = 2`, so the scale factor is `2*fs = 4`. */
    private fun bilinear(s: Complex): Complex {
        val quarter = Complex(s.re / 4.0, s.im / 4.0)
        return (Complex(1.0, 0.0) + quarter) / (Complex(1.0, 0.0) - quarter)
    }

    /** Scales the cascade so its magnitude is exactly 1 at digital frequency [w]. */
    private fun normalise(sections: List<Sos>, w: Double): List<Sos> {
        var mag = 1.0
        for (s in sections) mag *= sectionMagnitude(s, w)
        if (mag == 0.0 || !mag.isFinite()) return sections
        // Spread the correction across sections so no single numerator gets extreme.
        val g = (1.0 / mag).pow(1.0 / sections.size)
        return sections.map { Sos(it.b0 * g, it.b1 * g, it.b2 * g, it.a1, it.a2) }
    }

    private fun sectionMagnitude(s: Sos, w: Double): Double {
        val e1 = Complex(cos(-w), sin(-w))
        val e2 = Complex(cos(-2 * w), sin(-2 * w))
        val num = Complex(s.b0, 0.0) + e1 * s.b1 + e2 * s.b2
        val den = Complex(1.0, 0.0) + e1 * s.a1 + e2 * s.a2
        return num.abs() / den.abs()
    }

    /** Magnitude response of a cascade at digital frequency [w], for tests. */
    fun magnitudeAt(sections: List<Sos>, w: Double): Double {
        var m = 1.0
        for (s in sections) m *= sectionMagnitude(s, w)
        return m
    }

    // ------------------------------------------------------------------ Hilbert

    /**
     * `abs(hilbert(x))` — the amplitude envelope of the analytic signal.
     *
     * Built exactly as MATLAB does it: forward FFT at the signal's own length, zero the
     * negative-frequency half, double the positive half, inverse FFT, take the magnitude.
     * [Fft] supports arbitrary lengths precisely so this step needn't zero-pad.
     */
    fun hilbertEnvelope(x: DoubleArray): DoubleArray {
        val n = x.size
        if (n == 0) return DoubleArray(0)
        if (n == 1) return doubleArrayOf(abs(x[0]))

        val re = x.copyOf()
        val im = DoubleArray(n)
        Fft.forward(re, im)

        // Keep DC and, for even n, Nyquist as they are; double 1..n/2-1; zero the rest.
        val half = n / 2
        for (k in 1 until n) {
            when {
                k < half || (n % 2 != 0 && k <= half) -> {
                    re[k] *= 2.0
                    im[k] *= 2.0
                }
                k == half && n % 2 == 0 -> Unit // Nyquist bin is unpaired
                else -> {
                    re[k] = 0.0
                    im[k] = 0.0
                }
            }
        }

        Fft.inverse(re, im)
        return DoubleArray(n) { hypot(re[it], im[it]) }
    }

    // ------------------------------------------------------------------ statistics

    /**
     * MATLAB's `kurtosis(x)`: the **biased**, **non-excess** fourth standardised moment.
     *
     * `m4 / m2^2`, so a Gaussian gives about 3. MATLAB does not subtract 3 by default and
     * does not apply a bias correction unless asked. Returning excess kurtosis instead
     * would shift three of the fifteen features by a constant 3 and quietly wreck the
     * standardisation.
     */
    fun kurtosisBiased(x: DoubleArray): Double {
        val n = x.size
        if (n < 2) return 0.0
        var mean = 0.0
        for (v in x) mean += v
        mean /= n
        var m2 = 0.0
        var m4 = 0.0
        for (v in x) {
            val d = v - mean
            val d2 = d * d
            m2 += d2
            m4 += d2 * d2
        }
        m2 /= n
        m4 /= n
        return if (m2 <= 0.0) 0.0 else m4 / (m2 * m2)
    }

    /** MATLAB's `std(x)`: sample standard deviation, normalised by N-1. */
    fun stdSample(x: DoubleArray): Double {
        val n = x.size
        if (n < 2) return 0.0
        var mean = 0.0
        for (v in x) mean += v
        mean /= n
        var ss = 0.0
        for (v in x) {
            val d = v - mean
            ss += d * d
        }
        return sqrt(ss / (n - 1))
    }

    fun mean(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        var s = 0.0
        for (v in x) s += v
        return s / x.size
    }

    /** MATLAB's symmetric `hamming(n)`: 0.54 - 0.46*cos(2*pi*i/(n-1)). */
    fun hamming(n: Int): DoubleArray {
        if (n <= 1) return DoubleArray(n) { 1.0 }
        return DoubleArray(n) { 0.54 - 0.46 * cos(2.0 * PI * it / (n - 1)) }
    }

    // ------------------------------------------------------------------ band power

    /**
     * Port of `localBandPower`: mean-remove, Hamming-window, take `abs(fft)^2`, keep the
     * one-sided half, and return the **median** bin value inside each band.
     *
     * Only the bins that fall inside a requested band are ever needed, and the bands are
     * narrow (20 Hz) against a coarse bin spacing, so each bin is evaluated with a direct
     * DFT sum rather than a full transform. That is exact — the same value MATLAB's `fft`
     * would produce for that bin — and far cheaper than transforming every segment.
     */
    fun bandPowerMedians(seg: DoubleArray, fs: Double, bands: Array<DoubleArray>): DoubleArray {
        val out = DoubleArray(bands.size)
        val n = seg.size
        if (n < 4) return out

        val m = mean(seg)
        val w = hamming(n)
        val windowed = DoubleArray(n) { (seg[it] - m) * w[it] }

        val halfBins = n / 2 // one-sided spectrum is bins 0..n/2 inclusive
        val binHz = fs / n

        for (bi in bands.indices) {
            val lo = bands[bi][0]
            val hi = bands[bi][1]
            val values = ArrayList<Double>()
            for (k in 0..halfBins) {
                val f = k * binHz
                if (f >= lo && f < hi) values.add(binPower(windowed, k))
            }
            out[bi] = if (values.isEmpty()) 0.0 else median(values)
        }
        return out
    }

    /** |X[k]|^2 for a single bin, by direct summation. */
    private fun binPower(x: DoubleArray, k: Int): Double {
        val n = x.size
        var re = 0.0
        var im = 0.0
        val step = -2.0 * PI * k / n
        for (i in x.indices) {
            val a = step * i
            re += x[i] * cos(a)
            im += x[i] * sin(a)
        }
        return re * re + im * im
    }

    /** MATLAB's `median`: mean of the two middle values for an even count. */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    // ------------------------------------------------------------------ complex helper

    private data class Complex(val re: Double, val im: Double) {
        operator fun plus(o: Complex) = Complex(re + o.re, im + o.im)
        operator fun minus(o: Complex) = Complex(re - o.re, im - o.im)
        operator fun times(o: Complex) = Complex(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun times(k: Double) = Complex(re * k, im * k)
        operator fun div(o: Complex): Complex {
            val d = o.re * o.re + o.im * o.im
            return Complex((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
        }

        fun abs() = hypot(re, im)

        fun sqrt(): Complex {
            if (re == 0.0 && im == 0.0) return Complex(0.0, 0.0)
            val r = abs()
            val a = kotlin.math.sqrt((r + re) / 2.0)
            val b = kotlin.math.sqrt((r - re) / 2.0)
            return Complex(a, if (im < 0.0) -b else b)
        }
    }
}
