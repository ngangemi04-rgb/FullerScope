package com.capstone.stethoscope.analysis.pcg

import com.capstone.stethoscope.analysis.heartsound.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Averaged power spectrum (Welch's method) of a captured signal, for the testing-mode
 * scope.
 *
 * ## Why averaged rather than one big FFT
 *
 * A single transform of a 60-second recording gives 0.017 Hz bins and a spectrum so noisy
 * that a filter corner is invisible in the scatter — each bin is one sample of a random
 * variable, and more bins do not reduce its variance. Splitting the signal into
 * overlapping windows and averaging their periodograms trades resolution for variance,
 * which is the right trade when the question is "where does the response fall off", not
 * "what exact frequency is that tone".
 *
 * With the default [FFT_SIZE] at a 16 kHz capture the bins are 3.9 Hz apart and a
 * 60-second clip averages ~460 windows, which is smooth enough to read a −20 dB point off
 * the screen.
 *
 * ## Why Hann windowing
 *
 * Chopping a signal into rectangular blocks multiplies it by a boxcar, whose own spectrum
 * has −13 dB sidelobes that decay at only 6 dB per octave. Those sidelobes *are* the
 * noise floor: a strong 100 Hz S1 would smear enough energy across the whole axis to sit
 * above the stopband being measured, and the 600 Hz corner would look like it was barely
 * doing anything. Hann's −31 dB sidelobes fall at 18 dB per octave, which puts the
 * leakage well below the ~40 dB of attenuation worth reading.
 */
object Spectrum {

    /** 4096 points: 3.9 Hz bins at 16 kHz, 0.49 Hz at 2 kHz. Power of two, so radix-2. */
    const val FFT_SIZE = 4096

    /**
     * A calibrated one-sided amplitude spectrum.
     *
     * [amplitude] is in the **input's own units** — int16 counts, here — and is scaled so
     * that a sinusoid sitting on a bin centre reads its own peak amplitude back. The
     * scaling matters even though the scope plots ratios: dividing one of these spectra
     * by another gives the transfer function of whatever sits between them, and that is
     * only true if both are on a real, length-independent scale.
     *
     * The caveat that comes with any amplitude spectrum: for *broadband* content the
     * per-bin reading depends on the bin width, so absolute numbers are meaningful for
     * tones and for comparing one frequency against another within a trace, not as a
     * total energy figure. Ratios between bins — which is all a cutoff measurement is —
     * are unaffected.
     */
    class Result(
        val binHz: Double,
        val amplitude: DoubleArray,
        val sampleRate: Int,
    ) {
        /** Highest frequency present, i.e. the right-hand end of the axis. */
        val nyquistHz: Double get() = sampleRate / 2.0

        fun frequencyOf(bin: Int): Double = bin * binHz

        /** Loudest bin's amplitude, in input units. */
        val peakAmplitude: Double = amplitude.maxOrNull() ?: 0.0

        /**
         * Magnitudes in dB relative to the loudest bin — pure shape, no calibration.
         * Kept because it is the right reference when the stage has been peak-normalized
         * and absolute level has already been destroyed.
         */
        val magnitudeDb: DoubleArray = DoubleArray(amplitude.size) { k ->
            if (peakAmplitude <= 0.0 || amplitude[k] <= 0.0) {
                FLOOR_DB
            } else {
                (20.0 * log10(amplitude[k] / peakAmplitude)).coerceAtLeast(FLOOR_DB)
            }
        }

        /** Amplitude at [hz] in input units, linearly interpolated between bins. */
        fun amplitudeAt(hz: Double): Double = interpolate(amplitude, hz, 0.0)

        /**
         * Amplitude at [hz] divided by sqrt(bin width): the per-bin reading of broadband
         * content with the bin width taken out, so two spectra with different bins can be
         * divided. For a tone it is off by the same factor, which is why callers comparing
         * rates should also match bin widths with [matchedFftSize].
         */
        fun densityAt(hz: Double): Double = amplitudeAt(hz) / sqrt(binHz)

        /** dB at [hz] relative to the loudest bin, linearly interpolated between bins. */
        fun dbAt(hz: Double): Double = interpolate(magnitudeDb, hz, FLOOR_DB)

        private fun interpolate(from: DoubleArray, hz: Double, empty: Double): Double {
            if (from.isEmpty()) return empty
            val pos = (hz / binHz).coerceIn(0.0, (from.size - 1).toDouble())
            val i = pos.toInt()
            if (i >= from.size - 1) return from.last()
            val frac = pos - i
            return from[i] * (1 - frac) + from[i + 1] * frac
        }

        companion object {
            val EMPTY = Result(1.0, DoubleArray(0), 2_000)
        }
    }

    /** Amplitude of a sinusoid that just fills the signed 16-bit range. */
    const val FULL_SCALE = 32_768.0

    /**
     * Floor applied to the dB scale.
     *
     * Without it, a bin that happens to land on a numerical zero becomes −infinity and
     * takes the whole plot's y-axis with it. −120 dB is far below anything measurable
     * from a 16-bit capture, so clamping there discards nothing real.
     */
    const val FLOOR_DB = -120.0

    fun estimate(x: DoubleArray, sampleRate: Int, fftSize: Int = FFT_SIZE): Result {
        if (x.isEmpty() || sampleRate <= 0) return Result.EMPTY

        // A clip shorter than one window still deserves a spectrum; shrink to the largest
        // power of two that fits rather than refusing.
        var n = fftSize
        while (n > 32 && n > x.size) n /= 2
        if (x.size < 8) return Result.EMPTY

        val window = hann(n)
        // Coherent gain: a window scales a tone's transform by the sum of its taps, so
        // dividing it back out is what turns a raw bin into an amplitude in the input's
        // own units. Hann sums to n/2.
        var coherentGain = 0.0
        for (w in window) coherentGain += w

        val hop = n / 2 // 50% overlap: Hann's complement, so every sample is weighted once
        val bins = n / 2 + 1
        val accumulated = DoubleArray(bins)
        var windows = 0

        var start = 0
        while (start + n <= x.size) {
            val re = DoubleArray(n)
            val im = DoubleArray(n)
            for (i in 0 until n) re[i] = x[start + i] * window[i]
            Fft.forward(re, im)
            for (k in 0 until bins) {
                accumulated[k] += (re[k] * re[k] + im[k] * im[k])
            }
            windows++
            start += hop
        }

        if (windows == 0) {
            // Shorter than one window even after shrinking: transform what there is,
            // zero-padded. Less resolution, but a plot rather than a blank panel.
            val re = DoubleArray(n)
            val im = DoubleArray(n)
            for (i in x.indices) if (i < n) re[i] = x[i] * window[i]
            Fft.forward(re, im)
            for (k in 0 until bins) accumulated[k] = re[k] * re[k] + im[k] * im[k]
            windows = 1
        }

        // Averaged power per bin becomes an amplitude: undo the window's coherent gain,
        // and double every bin except DC and Nyquist, which have no negative-frequency
        // twin to fold in. Skipping that doubling would report every tone 6 dB light.
        val amplitude = DoubleArray(bins) { k ->
            val meanPower = accumulated[k] / windows
            val fold = if (k == 0 || k == bins - 1) 1.0 else 2.0
            fold * sqrt(meanPower) / coherentGain
        }

        return Result(
            binHz = sampleRate.toDouble() / n,
            amplitude = amplitude,
            sampleRate = sampleRate,
        )
    }

    /**
     * FFT length at [sampleRate] whose bins are as wide as [FFT_SIZE]'s at [referenceRate].
     *
     * Needed whenever two spectra at different rates are divided. These amplitudes are
     * calibrated for tones, and a broadband signal's per-bin reading grows with the square
     * root of the bin width — so a 16 kHz spectrum (3.9 Hz bins) against a 2 kHz one
     * (0.49 Hz bins) of the *same* noise reads sqrt(8), about 9 dB, apart. Matching the
     * bin width makes the ratio right for tones and broadband alike. Rounded down to a
     * power of two for the radix-2 FFT; [Result.densityAt] absorbs any remainder.
     */
    fun matchedFftSize(sampleRate: Int, referenceRate: Int, referenceSize: Int = FFT_SIZE): Int {
        val ideal = referenceSize.toDouble() * sampleRate / referenceRate
        var n = 32
        while (n * 2 <= ideal) n *= 2
        return n
    }

    /** Periodic Hann window, matching what an FFT-based estimator wants. */
    private fun hann(n: Int) = DoubleArray(n) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / n) }
}
