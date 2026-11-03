package com.capstone.stethoscope.analysis.heartsound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Checks the ported primitives against independent ground truth rather than against each
 * other. Every one of these operations feeds the trained model's features, and a subtly
 * wrong answer produces a plausible-looking probability rather than a crash — so
 * "it compiles and returns a number" is worth nothing here.
 */
class MatlabDspTest {

    // ------------------------------------------------------------------ FFT

    /** Naive O(N^2) DFT, deliberately written the slow obvious way as the reference. */
    private fun naiveDft(re: DoubleArray, im: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val a = -2.0 * PI * k * t / n
                sr += re[t] * cos(a) - im[t] * sin(a)
                si += re[t] * sin(a) + im[t] * cos(a)
            }
            outRe[k] = sr
            outIm[k] = si
        }
        return outRe to outIm
    }

    @Test
    fun `power-of-two fft matches a naive dft`() {
        val n = 64
        val re = DoubleArray(n) { sin(0.3 * it) + 0.5 * cos(0.11 * it) }
        val im = DoubleArray(n) { 0.2 * sin(0.07 * it) }
        val (expRe, expIm) = naiveDft(re, im)

        Fft.forward(re, im)
        for (k in 0 until n) {
            assertEquals("re[$k]", expRe[k], re[k], 1e-9)
            assertEquals("im[$k]", expIm[k], im[k], 1e-9)
        }
    }

    @Test
    fun `bluestein handles non-power-of-two lengths`() {
        // 120000 samples is what a 60 s recording at 2 kHz gives, and it is not a power of
        // two — this is the path MATLAB's exact-length hilbert() forces us onto.
        for (n in listOf(3, 5, 7, 100, 121, 1000)) {
            val re = DoubleArray(n) { sin(0.37 * it) + it % 3 }
            val im = DoubleArray(n) { cos(0.21 * it) }
            val (expRe, expIm) = naiveDft(re.copyOf(), im.copyOf())

            Fft.forward(re, im)
            for (k in 0 until n) {
                assertEquals("n=$n re[$k]", expRe[k], re[k], 1e-7 * n)
                assertEquals("n=$n im[$k]", expIm[k], im[k], 1e-7 * n)
            }
        }
    }

    @Test
    fun `inverse fft round-trips`() {
        for (n in listOf(16, 100, 257)) {
            val original = DoubleArray(n) { sin(0.5 * it) * (it + 1) }
            val re = original.copyOf()
            val im = DoubleArray(n)
            Fft.forward(re, im)
            Fft.inverse(re, im)
            for (i in 0 until n) assertEquals(original[i], re[i], 1e-8)
        }
    }

    // ------------------------------------------------------------------ Butterworth

    /**
     * Analytic Butterworth bandpass magnitude, evaluated on the pre-warped analog axis.
     *
     * This is the definition the design must satisfy, derived independently of the code
     * under test: |H| = 1/sqrt(1 + ((W^2 - wo^2)/(W*bw))^(2n)).
     */
    private fun theoreticalBandpassMag(
        digitalW: Double, lowHz: Double, highHz: Double, fs: Double, order: Int,
    ): Double {
        val nyq = fs / 2
        val u1 = 4.0 * tan(PI * (lowHz / nyq) / 2.0)
        val u2 = 4.0 * tan(PI * (highHz / nyq) / 2.0)
        val bw = u2 - u1
        val wo = sqrt(u1 * u2)
        val w = 4.0 * tan(digitalW / 2.0)
        if (w == 0.0) return 0.0
        val t = (w * w - wo * wo) / (w * bw)
        return 1.0 / sqrt(1.0 + t.pow(2 * order))
    }

    @Test
    fun `bandpass matches the analytic butterworth response`() {
        val fs = 2000.0
        val lo = 25.0
        val hi = 400.0
        val sos = MatlabDsp.butterBandpass(4, lo, hi, fs)

        for (f in listOf(5.0, 10.0, 25.0, 50.0, 100.0, 200.0, 300.0, 400.0, 600.0, 800.0)) {
            val w = 2 * PI * f / fs
            val actual = MatlabDsp.magnitudeAt(sos, w)
            val expected = theoreticalBandpassMag(w, lo, hi, fs, 4)
            assertEquals("magnitude at $f Hz", expected, actual, 1e-6)
        }
    }

    @Test
    fun `bandpass is -3 dB at both corners and unity at the centre`() {
        val fs = 2000.0
        val sos = MatlabDsp.butterBandpass(4, 25.0, 400.0, fs)
        val half = 1.0 / sqrt(2.0)

        assertEquals(half, MatlabDsp.magnitudeAt(sos, 2 * PI * 25.0 / fs), 1e-6)
        assertEquals(half, MatlabDsp.magnitudeAt(sos, 2 * PI * 400.0 / fs), 1e-6)

        // Geometric centre of the pre-warped edges, where a Butterworth bandpass is unity.
        val u1 = 4.0 * tan(PI * (25.0 / 1000.0) / 2.0)
        val u2 = 4.0 * tan(PI * (400.0 / 1000.0) / 2.0)
        val centre = 2.0 * atan(sqrt(u1 * u2) / 4.0)
        assertEquals(1.0, MatlabDsp.magnitudeAt(sos, centre), 1e-6)
    }

    @Test
    fun `bandpass has exactly 2n sections and blocks DC and Nyquist`() {
        val sos = MatlabDsp.butterBandpass(4, 25.0, 400.0, 2000.0)
        assertEquals("order-4 bandpass is an 8-pole filter, i.e. 4 biquads", 4, sos.size)
        assertEquals(0.0, MatlabDsp.magnitudeAt(sos, 0.0), 1e-12)
        assertEquals(0.0, MatlabDsp.magnitudeAt(sos, PI), 1e-12)
    }

    @Test
    fun `lowpass is unity at DC and -3 dB at its cutoff`() {
        val fs = 2000.0
        val sos = MatlabDsp.butterLowpass(2, 20.0, fs)
        assertEquals(1.0, MatlabDsp.magnitudeAt(sos, 0.0), 1e-9)
        assertEquals(1.0 / sqrt(2.0), MatlabDsp.magnitudeAt(sos, 2 * PI * 20.0 / fs), 1e-6)
    }

    @Test
    fun `filtering is causal`() {
        // An impulse at index 100 must produce no output before index 100. filtfilt would.
        val sos = MatlabDsp.butterBandpass(4, 25.0, 400.0, 2000.0)
        val x = DoubleArray(500)
        x[100] = 1.0
        val y = MatlabDsp.filter(sos, x)
        for (i in 0 until 100) assertEquals("output at $i must be zero", 0.0, y[i], 1e-15)
        assertTrue("something should happen after the impulse", y.drop(100).any { abs(it) > 1e-6 })
    }

    // ------------------------------------------------------------------ Hilbert

    @Test
    fun `envelope of a sine is its amplitude`() {
        val fs = 2000.0
        val n = 4000
        val amp = 0.37
        val x = DoubleArray(n) { amp * sin(2 * PI * 100.0 * it / fs) }
        val env = MatlabDsp.hilbertEnvelope(x)

        // Edges of an FFT-based Hilbert always ring; the interior should be flat.
        for (i in 500 until n - 500) {
            assertEquals("envelope at $i", amp, env[i], amp * 0.02)
        }
    }

    @Test
    fun `envelope tracks an amplitude ramp`() {
        val fs = 2000.0
        val n = 4000
        val x = DoubleArray(n) { (it.toDouble() / n) * sin(2 * PI * 120.0 * it / fs) }
        val env = MatlabDsp.hilbertEnvelope(x)
        assertTrue("envelope should rise with the signal", env[3000] > env[1000] * 2)
    }

    @Test
    fun `envelope handles a non-power-of-two length`() {
        val n = 12345
        val x = DoubleArray(n) { 0.5 * sin(2 * PI * 90.0 * it / 2000.0) }
        val env = MatlabDsp.hilbertEnvelope(x)
        assertEquals(n, env.size)
        assertEquals(0.5, env[n / 2], 0.02)
    }

    // ------------------------------------------------------------------ statistics

    @Test
    fun `kurtosis is MATLAB's biased non-excess form`() {
        // MATLAB: kurtosis([1 2 3 4 5]) == 1.7 exactly (m4/m2^2, no -3, no bias correction).
        assertEquals(1.7, MatlabDsp.kurtosisBiased(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)), 1e-12)
    }

    @Test
    fun `kurtosis of a gaussian is about three not about zero`() {
        val rng = java.util.Random(42)
        val x = DoubleArray(200_000) { rng.nextGaussian() }
        val k = MatlabDsp.kurtosisBiased(x)
        assertTrue("expected ~3 (non-excess), got $k", abs(k - 3.0) < 0.1)
    }

    @Test
    fun `std uses the N-1 denominator like MATLAB`() {
        // std([1 2 3 4]) = sqrt(5/3) = 1.29099...
        assertEquals(
            sqrt(5.0 / 3.0),
            MatlabDsp.stdSample(doubleArrayOf(1.0, 2.0, 3.0, 4.0)),
            1e-12,
        )
    }

    @Test
    fun `hamming matches MATLAB's symmetric definition`() {
        val w = MatlabDsp.hamming(5)
        // Endpoints are 0.54 - 0.46 = 0.08, centre is 1.0.
        assertEquals(0.08, w[0], 1e-12)
        assertEquals(0.08, w[4], 1e-12)
        assertEquals(1.0, w[2], 1e-12)
    }

    @Test
    fun `median averages the middle pair for even counts`() {
        assertEquals(2.5, MatlabDsp.median(listOf(1.0, 2.0, 3.0, 4.0)), 1e-12)
        assertEquals(3.0, MatlabDsp.median(listOf(5.0, 1.0, 3.0)), 1e-12)
    }

    // ------------------------------------------------------------------ band power

    @Test
    fun `band power finds a tone in the right band and not in others`() {
        val fs = 2000.0
        val n = 200 // 0.1 s window, 10 Hz bin spacing
        val x = DoubleArray(n) { sin(2 * PI * 95.0 * it / fs) }
        val bands = arrayOf(
            doubleArrayOf(85.0, 105.0),  // contains 90 and 100 Hz bins
            doubleArrayOf(25.0, 45.0),   // far from the tone
        )
        val p = MatlabDsp.bandPowerMedians(x, fs, bands)
        assertTrue("tone band should dominate: ${p[0]} vs ${p[1]}", p[0] > p[1] * 100)
    }

    @Test
    fun `band power returns zero when no bin falls inside a band`() {
        val fs = 2000.0
        // n = 8 gives 250 Hz bins, so nothing lands inside a 20 Hz band above DC.
        val x = DoubleArray(8) { sin(it.toDouble()) }
        val p = MatlabDsp.bandPowerMedians(x, fs, arrayOf(doubleArrayOf(85.0, 105.0)))
        assertEquals(0.0, p[0], 0.0)
    }

    @Test
    fun `band power matches a direct windowed dft`() {
        val fs = 2000.0
        val n = 100 // 20 Hz bins: 100 Hz lands exactly on bin 5
        val x = DoubleArray(n) { 0.3 * sin(2 * PI * 100.0 * it / fs) + 0.1 }
        val p = MatlabDsp.bandPowerMedians(x, fs, arrayOf(doubleArrayOf(95.0, 105.0)))

        // Only bin 5 (100 Hz) is inside, so the median is that bin's power. Recompute it
        // here from scratch: mean-remove, Hamming-window, one DFT bin.
        val mean = x.average()
        val w = MatlabDsp.hamming(n)
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) {
            val v = (x[i] - mean) * w[i]
            val a = -2.0 * PI * 5 * i / n
            re += v * cos(a)
            im += v * sin(a)
        }
        assertEquals(hypot(re, im).pow(2), p[0], 1e-6)
    }
}
