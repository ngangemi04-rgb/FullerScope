package com.capstone.stethoscope.analysis.pcg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rational sample-rate conversion with a proper anti-alias filter — the Kotlin
 * counterpart of `scipy.signal.resample_poly`.
 *
 * ## Why not just drop samples
 *
 * Decimating by taking every Nth sample folds everything above the new Nyquist back
 * into the passband, and it does so *irreversibly*. Going 8 kHz → 2 kHz that way
 * would alias energy from 1–4 kHz down on top of the heart sounds, and no downstream
 * filter could separate them again. The lowpass has to be applied **before** the
 * decimation, which is what this does.
 *
 * ## Design
 *
 * Follows the same recipe as `resample_poly`: upsample by `up`, lowpass at
 * `1/max(up, down)` of the intermediate Nyquist, decimate by `down`. The filter is a
 * Kaiser-windowed sinc (β = 5.0) of length `2 * 10 * max(up, down) + 1`, scaled by
 * `up` to make up for the energy lost to zero-stuffing.
 *
 * The convolution is evaluated in polyphase form — only the products that involve a
 * non-zero input sample are computed, and only at output positions we keep. Written
 * naively (materialise the zero-stuffed signal, convolve, throw most of it away)
 * a 44.1 kHz → 2 kHz conversion of a 60-second clip is on the order of 10^11
 * operations; this way it is around 5 × 10^7.
 *
 * > This is *functionally* equivalent to `resample_poly`, not bit-identical to it.
 * > Same filter family and parameters, but floating-point summation order differs, so
 * > expect agreement to a few LSBs rather than exact equality.
 */
object Resampler {

    /** Half-length multiplier, matching `resample_poly`'s `half_len = 10 * max_rate`. */
    private const val HALF_LEN_MULTIPLIER = 10

    /** Kaiser window shape parameter, matching `resample_poly`'s default. */
    private const val KAISER_BETA = 5.0

    /**
     * Resamples [x] from [fsIn] to [fsOut]. Returns [x] unchanged when the rates
     * already match, so calling this unconditionally is free in the common case.
     */
    fun resample(x: DoubleArray, fsIn: Int, fsOut: Int): DoubleArray {
        require(fsIn > 0 && fsOut > 0) { "Sample rates must be positive (got $fsIn -> $fsOut)" }
        if (fsIn == fsOut || x.isEmpty()) return x

        val g = gcd(fsIn, fsOut)
        val up = fsOut / g
        val down = fsIn / g

        val maxRate = max(up, down)
        val halfLen = HALF_LEN_MULTIPLIER * maxRate
        val h = kaiserSincLowpass(halfLen, cutoff = 1.0 / maxRate, gain = up.toDouble())

        return polyphase(x, h, up, down, halfLen)
    }

    /**
     * Evaluates `decimate(conv(upsample(x, up), h), down)`, compensating for the
     * filter's group delay of [halfLen] samples so the output stays time-aligned
     * with the input.
     */
    private fun polyphase(
        x: DoubleArray,
        h: DoubleArray,
        up: Int,
        down: Int,
        halfLen: Int,
    ): DoubleArray {
        val nIn = x.size
        val outLen = ceil(nIn.toDouble() * up / down).toInt()
        val y = DoubleArray(outLen)
        val hLen = h.size

        for (m in 0 until outLen) {
            // Position in the (virtual) upsampled signal this output taps, shifted by
            // halfLen to undo the FIR's delay.
            val center = m.toLong() * down + halfLen

            // Non-zero inputs sit at multiples of `up`; keep only those whose filter
            // index falls inside h.
            var kMin = ceilDiv(center - hLen + 1, up.toLong()).toInt()
            var kMax = (center / up).toInt()
            if (kMin < 0) kMin = 0
            if (kMax > nIn - 1) kMax = nIn - 1

            var acc = 0.0
            for (k in kMin..kMax) {
                acc += h[(center - k.toLong() * up).toInt()] * x[k]
            }
            y[m] = acc
        }
        return y
    }

    /**
     * Kaiser-windowed sinc lowpass, `2 * halfLen + 1` taps.
     *
     * [cutoff] is normalized to Nyquist of the signal being filtered (1.0 == Nyquist),
     * matching `firwin`'s convention; [gain] scales the whole response.
     */
    private fun kaiserSincLowpass(halfLen: Int, cutoff: Double, gain: Double): DoubleArray {
        val n = 2 * halfLen + 1
        val h = DoubleArray(n)
        // firwin takes cutoff relative to Nyquist; sinc wants cycles per sample.
        val fc = cutoff / 2.0
        val i0Beta = besselI0(KAISER_BETA)

        for (i in 0 until n) {
            val t = (i - halfLen).toDouble()
            val ideal = 2.0 * fc * sinc(2.0 * fc * t)
            // Kaiser window.
            val r = 2.0 * i / (n - 1) - 1.0
            val window = besselI0(KAISER_BETA * sqrt((1.0 - r * r).coerceAtLeast(0.0))) / i0Beta
            h[i] = ideal * window
        }

        // Force the DC gain to exactly `gain`, as firwin's scale=True does. The windowed
        // sinc only approximates it, and on the un-normalized path that error shows up
        // directly as a change in absolute amplitude.
        var sum = 0.0
        for (v in h) sum += v
        if (sum != 0.0) {
            val correction = gain / sum
            for (i in h.indices) h[i] *= correction
        }
        return h
    }

    /** Normalized sinc, sin(pi x) / (pi x), with the removable singularity handled. */
    private fun sinc(x: Double): Double {
        if (abs(x) < 1e-12) return 1.0
        val px = PI * x
        return sin(px) / px
    }

    /**
     * Modified Bessel function of the first kind, order 0, by its power series.
     * Converges quickly for the arguments a Kaiser window needs (|x| <= beta = 5).
     */
    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val halfSquared = (x / 2.0) * (x / 2.0)
        var k = 1
        while (k < 64) {
            term *= halfSquared / (k.toDouble() * k.toDouble())
            sum += term
            if (term < 1e-18 * sum) break
            k++
        }
        return sum
    }

    private fun ceilDiv(a: Long, b: Long): Long =
        if (a >= 0) (a + b - 1) / b else -((-a) / b)

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = y
            y = x % y
            x = t
        }
        return x
    }
}
