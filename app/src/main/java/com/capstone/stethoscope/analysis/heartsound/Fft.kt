package com.capstone.stethoscope.analysis.heartsound

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Complex FFT for **any** transform length.
 *
 * Arbitrary length is not a nicety here. MATLAB's `hilbert(x)` transforms the signal at
 * its exact length, and the analytic envelope drives peak picking, which in turn decides
 * where cardiac cycles start. A 60-second recording at 2 kHz is 120,000 samples — not a
 * power of two. Zero-padding to 131,072 would introduce a step at the boundary and shift
 * the envelope near the ends, potentially gaining or losing a cycle and moving every
 * averaged feature slightly. Matching MATLAB exactly removes that whole class of doubt.
 *
 * Power-of-two lengths use iterative radix-2. Everything else goes through **Bluestein's
 * algorithm**, which re-expresses a length-N DFT as a convolution that a larger
 * power-of-two FFT can evaluate.
 *
 * Arrays are separate real and imaginary parts, transformed in place, with the sign
 * convention `X[k] = sum x[n] exp(-2i*pi*n*k/N)` — the same as MATLAB's `fft`.
 */
internal object Fft {

    /** Forward transform of [re]/[im] in place. Length may be anything >= 1. */
    fun forward(re: DoubleArray, im: DoubleArray) {
        require(re.size == im.size) { "Real and imaginary parts must match in length" }
        val n = re.size
        if (n <= 1) return
        if (n and (n - 1) == 0) radix2(re, im, inverse = false) else bluestein(re, im)
    }

    /** Inverse transform of [re]/[im] in place, including the 1/N scaling. */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        // conj -> forward -> conj -> scale is the standard reduction, and avoids a second
        // implementation that could disagree with the forward one.
        for (i in im.indices) im[i] = -im[i]
        forward(re, im)
        val scale = 1.0 / re.size
        for (i in re.indices) {
            re[i] *= scale
            im[i] = -im[i] * scale
        }
    }

    // ------------------------------------------------------------------ radix-2

    private fun radix2(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2.0 else -2.0) * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ------------------------------------------------------------------ Bluestein

    /**
     * Bluestein / chirp-z. Uses the identity `n*k = (n^2 + k^2 - (k-n)^2) / 2` to turn the
     * DFT into a linear convolution of a chirp-weighted input with a chirp kernel, then
     * evaluates that convolution with a power-of-two FFT of length >= 2N-1.
     */
    private fun bluestein(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var m = 1
        while (m < 2 * n - 1) m = m shl 1

        // Chirp: exp(-i*pi*n^2/N). Angles are reduced mod 2N before scaling to keep
        // precision for large n, where n*n would otherwise lose low-order bits.
        val chirpRe = DoubleArray(n)
        val chirpIm = DoubleArray(n)
        for (i in 0 until n) {
            val j2 = (i.toLong() * i) % (2L * n)
            val ang = -PI * j2 / n
            chirpRe[i] = cos(ang)
            chirpIm[i] = sin(ang)
        }

        val aRe = DoubleArray(m)
        val aIm = DoubleArray(m)
        for (i in 0 until n) {
            aRe[i] = re[i] * chirpRe[i] - im[i] * chirpIm[i]
            aIm[i] = re[i] * chirpIm[i] + im[i] * chirpRe[i]
        }

        // Kernel is the conjugate chirp, mirrored so the convolution is cyclic-safe.
        val bRe = DoubleArray(m)
        val bIm = DoubleArray(m)
        for (i in 0 until n) {
            bRe[i] = chirpRe[i]
            bIm[i] = -chirpIm[i]
            if (i > 0) {
                bRe[m - i] = chirpRe[i]
                bIm[m - i] = -chirpIm[i]
            }
        }

        radix2(aRe, aIm, inverse = false)
        radix2(bRe, bIm, inverse = false)
        for (i in 0 until m) {
            val r = aRe[i] * bRe[i] - aIm[i] * bIm[i]
            val q = aRe[i] * bIm[i] + aIm[i] * bRe[i]
            aRe[i] = r
            aIm[i] = q
        }
        radix2(aRe, aIm, inverse = true)
        val scale = 1.0 / m
        for (i in 0 until m) {
            aRe[i] *= scale
            aIm[i] *= scale
        }

        for (i in 0 until n) {
            re[i] = aRe[i] * chirpRe[i] - aIm[i] * chirpIm[i]
            im[i] = aRe[i] * chirpIm[i] + aIm[i] * chirpRe[i]
        }
    }
}
