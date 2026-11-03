package com.capstone.stethoscope.analysis.pcg

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Butterworth filtering applied **zero-phase**, the counterpart of
 * `scipy.signal.sosfiltfilt`.
 *
 * **Nothing in the app calls this any more** (2026-09-23). The pipeline used to run
 * [lowpassZeroPhase] at 600 Hz and, before that, [bandpassZeroPhase] at 25–400 Hz; the
 * algorithm team now does all filtering on their side, so
 * [com.capstone.stethoscope.analysis.pcg.PcgPreprocessor] hands the signal over
 * unconditioned.
 *
 * It is kept, and kept under test, for two reasons. It is the code that would be
 * reinstated if band limiting is ever wanted back — a decision that should be a one-line
 * change, not a reimplementation. And `SpectrumTest` uses it as a probe: a filter whose
 * response is known in closed form is the only honest way to check that the spectrum
 * estimator reports dB correctly.
 *
 * ## Why zero-phase matters here
 *
 * Any causal filter delays different frequencies by different amounts. For heart
 * sounds that would smear the relationship between S1 and S2 — exactly the timing a
 * segmentation or rhythm-regularity algorithm depends on. Running the filter forward
 * and then backward over the result cancels the phase response exactly, leaving only
 * the (squared) magnitude response. The cost is that the filter is non-causal, which
 * is fine: we are processing a finished recording, not a live stream.
 *
 * ## Implementation
 *
 * Built as a cascade of second-order sections using the standard bilinear-transform
 * biquad forms, with Butterworth Q values, rather than by translating scipy's
 * pole-placement code. An order-4 lowpass and an order-4 highpass in series give the
 * same passband and the same 4th-order skirts as scipy's order-4 bandpass, and the
 * cascade is better behaved numerically than a single high-order section.
 *
 * > Functionally equivalent to the SciPy reference, not coefficient-identical.
 */
object Butterworth {

    /**
     * Lowpass [x] at [cutoffHz], zero-phase — forward then backward through an
     * [order]-pole Butterworth, so the phase response cancels exactly and the magnitude
     * response is squared (an order-4 design rolls off like an order-8 one, and the
     * −3 dB point moves down to roughly 0.8 × [cutoffHz]).
     *
     * [cutoffHz] is clamped just under Nyquist: at or above it the bilinear transform
     * blows up. That clamp is what makes this safe to call unconditionally on a signal
     * whose rate the app did not choose — a 1 kHz-sampled input simply gets filtered
     * near its own Nyquist instead of throwing.
     */
    fun lowpassZeroPhase(
        x: DoubleArray,
        sampleRate: Int,
        cutoffHz: Double,
        order: Int,
    ): DoubleArray {
        if (x.size < 2) return x
        val nyquist = sampleRate / 2.0
        val fc = min(cutoffHz, 0.99 * nyquist)
        require(fc > 0) { "Need a positive cutoff (got $cutoffHz at $sampleRate Hz)" }

        // A lowpass's slowest pole sits at the cutoff, decaying over about
        // sampleRate/cutoff samples, so pad several of those. Much shorter than the
        // bandpass case below, whose 25 Hz highpass corner needed 20x a far longer
        // time constant.
        val padLen = min(x.size - 1, (20.0 * sampleRate / fc).toInt())
        return runZeroPhase(x, lowpassSections(fc, sampleRate, order), padLen)
    }

    /**
     * The magnitude a zero-phase [order]-pole lowpass at [cutoffHz] *should* have at
     * [hz], in dB. Textbook, not measured.
     *
     * `|H|² = 1 / (1 + (f/fc)^2n)` is the Butterworth definition; running the filter
     * forward and backward squares that again, which is why the corner lands at −6 dB
     * and not the −3 dB a single pass would give. This is the reference `SpectrumTest`
     * checks a measured response against, and it is deliberately derived from the
     * *specification* rather than from [lowpassZeroPhase] — a reference computed by
     * running the same code would agree with a wrong implementation just as happily.
     *
     * Analog-prototype form, so it ignores the bilinear transform's frequency warping.
     * That divergence only becomes visible well past the corner, by which point the
     * response is below −80 dB and buried in the capture's noise floor anyway.
     */
    fun zeroPhaseLowpassDb(hz: Double, cutoffHz: Double, order: Int): Double {
        if (cutoffHz <= 0.0) return 0.0
        val ratio = (hz / cutoffHz).coerceAtLeast(0.0)
        return -20.0 * log10(1.0 + ratio.pow(2 * order))
    }

    /**
     * Bandpass [x] between [lowHz] and [highHz] at [sampleRate], zero-phase.
     *
     * [highHz] is clamped just under Nyquist, mirroring the guard in the Python
     * reference — a cutoff at or above Nyquist makes the bilinear transform blow up.
     */
    fun bandpassZeroPhase(
        x: DoubleArray,
        sampleRate: Int,
        lowHz: Double,
        highHz: Double,
        order: Int,
    ): DoubleArray {
        if (x.isEmpty()) return x
        val nyquist = sampleRate / 2.0
        val hi = min(highHz, 0.99 * nyquist)
        require(lowHz > 0 && lowHz < hi) { "Need 0 < lowHz ($lowHz) < highHz ($hi)" }

        val sections = highpassSections(lowHz, sampleRate, order) +
            lowpassSections(hi, sampleRate, order)

        // Padding length controls how far the *state-seeding* transient at each end of the
        // array has to travel before it reaches real data, so it wants to be several time
        // constants of the slowest pole — the highpass corner, decaying over roughly
        // sampleRate/lowHz samples.
        //
        // It does NOT remove the artifact at the seam between invented and real samples:
        // that seam sits at the boundary of the retained region by construction, so its
        // ringing always contaminates the first and last few samples. That is inherent to
        // zero-phase filtering with any padding scheme (`filtfilt` behaves the same way),
        // which is why callers must not treat the extreme edges as signal — see the guard
        // in PcgPreprocessor's peak measurement.
        val padLen = min(x.size - 1, (20.0 * sampleRate / lowHz).toInt())
        return runZeroPhase(x, sections, padLen)
    }

    /**
     * Reflect-pads, runs [sections] forward then backward, and trims the padding off —
     * the `filtfilt` core shared by both public entry points.
     */
    private fun runZeroPhase(
        x: DoubleArray,
        sections: List<Biquad>,
        padLen: Int,
    ): DoubleArray {
        var y = reflectExtend(x, padLen)
        for (s in sections) y = s.filterForward(y)
        y = y.reversedArray()
        for (s in sections) y = s.filterForward(y)
        y = y.reversedArray()
        return y.copyOfRange(padLen, padLen + x.size)
    }

    /**
     * Even (mirror) extension of the signal at both ends.
     *
     * `filtfilt` defaults to *odd* extension, which reflects through the endpoint value
     * (`2·x[0] − x[k]`). That is the better choice for a signal with a trend, since it
     * continues the slope. It is the worse choice here: heart sounds are oscillatory, and
     * near the top of the band an odd extension can produce a seam value of twice the
     * signal amplitude — measured at −2127 for an 800 Hz tone of amplitude 1000. Mirroring
     * keeps the seam continuous in value, so the discontinuity it injects is far smaller.
     */
    private fun reflectExtend(x: DoubleArray, padLen: Int): DoubleArray {
        if (padLen <= 0) return x.copyOf()
        val n = x.size
        val out = DoubleArray(n + 2 * padLen)
        for (i in 0 until padLen) {
            out[i] = x[padLen - i]
            out[n + padLen + i] = x[n - 2 - i]
        }
        System.arraycopy(x, 0, out, padLen, n)
        return out
    }

    /**
     * Butterworth Q values for an [order]-pole cascade. For even orders these are the
     * `order / 2` conjugate pole pairs; odd orders would need a trailing first-order
     * section, which the PCG spec never asks for.
     */
    private fun butterworthQs(order: Int): DoubleArray {
        require(order >= 2 && order % 2 == 0) { "Only even orders >= 2 supported (got $order)" }
        val pairs = order / 2
        return DoubleArray(pairs) { k ->
            1.0 / (2.0 * cos(PI * (2 * k + 1) / (2.0 * order)))
        }
    }

    private fun lowpassSections(cutoffHz: Double, sampleRate: Int, order: Int): List<Biquad> =
        butterworthQs(order).map { q ->
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            Biquad.normalized(
                b0 = (1 - cosW0) / 2, b1 = 1 - cosW0, b2 = (1 - cosW0) / 2,
                a0 = 1 + alpha, a1 = -2 * cosW0, a2 = 1 - alpha,
            )
        }

    private fun highpassSections(cutoffHz: Double, sampleRate: Int, order: Int): List<Biquad> =
        butterworthQs(order).map { q ->
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            Biquad.normalized(
                b0 = (1 + cosW0) / 2, b1 = -(1 + cosW0), b2 = (1 + cosW0) / 2,
                a0 = 1 + alpha, a1 = -2 * cosW0, a2 = 1 - alpha,
            )
        }

    /** One second-order section in direct form I, coefficients already normalized by a0. */
    class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        /**
         * DC gain of this section, used to seed the state. Guarded against a pole at
         * z = 1, which no Butterworth section here has but which would divide by zero.
         */
        private val dcGain: Double = (1 + a1 + a2).let { denom ->
            if (kotlin.math.abs(denom) < 1e-12) 0.0 else (b0 + b1 + b2) / denom
        }

        /**
         * Filters [x] front to back. State is seeded from the first sample held steady
         * rather than from zero, which stops the section ringing on a signal that
         * doesn't happen to begin at 0.
         */
        fun filterForward(x: DoubleArray): DoubleArray {
            val y = DoubleArray(x.size)
            var x1 = x.firstOrNull() ?: 0.0
            var x2 = x1
            var y1 = x1 * dcGain
            var y2 = y1
            for (i in x.indices) {
                val x0 = x[i]
                val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                y[i] = y0
                x2 = x1; x1 = x0
                y2 = y1; y1 = y0
            }
            return y
        }

        companion object {
            fun normalized(
                b0: Double, b1: Double, b2: Double,
                a0: Double, a1: Double, a2: Double,
            ) = Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}
