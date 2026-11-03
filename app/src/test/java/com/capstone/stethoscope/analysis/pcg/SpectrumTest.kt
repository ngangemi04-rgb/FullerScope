package com.capstone.stethoscope.analysis.pcg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

/**
 * Tests the spectrum estimator, using a Butterworth lowpass as a known probe.
 *
 * The later groups matter more than they look: those assertions are the textbook
 * Butterworth response worked out on paper, checked against what the estimator reports.
 * If the testing-mode screen ever draws a response in the wrong place, this says whether
 * the measurement is wrong or only the plot is.
 *
 * The probe is not part of the app's signal chain — nothing filters a real recording any
 * more (see [PcgSpec]).
 */
class SpectrumTest {

    private fun tone(hz: Double, fs: Int, seconds: Double, amp: Double = 1000.0) =
        DoubleArray((fs * seconds).toInt()) { i -> amp * sin(2.0 * PI * hz * i / fs) }

    private fun noise(fs: Int, seconds: Double, seed: Long = 42L): DoubleArray {
        val rng = Random(seed)
        return DoubleArray((fs * seconds).toInt()) { rng.nextGaussian() * 1000.0 }
    }

    // --- the estimator itself ---

    @Test
    fun `a tone peaks at its own frequency`() {
        val s = Spectrum.estimate(tone(500.0, 16_000, 2.0), 16_000)

        val peakBin = s.magnitudeDb.indices.maxByOrNull { s.magnitudeDb[it] } ?: -1
        assertEquals(500.0, s.frequencyOf(peakBin), s.binHz * 2)
        assertEquals(0.0, s.magnitudeDb[peakBin], 1e-9) // dB is relative to the peak
    }

    @Test
    fun `bin width follows the sample rate`() {
        assertEquals(
            16_000.0 / Spectrum.FFT_SIZE,
            Spectrum.estimate(tone(300.0, 16_000, 2.0), 16_000).binHz,
            1e-9,
        )
        assertEquals(
            2_000.0 / Spectrum.FFT_SIZE,
            Spectrum.estimate(tone(300.0, 2_000, 8.0), 2_000).binHz,
            1e-9,
        )
    }

    @Test
    fun `energy well away from a tone is far below it`() {
        // Hann windowing is what makes this true; a rectangular window's sidelobes would
        // leave the floor high enough to hide a real stopband.
        val s = Spectrum.estimate(tone(200.0, 16_000, 4.0), 16_000)
        assertTrue("Floor at 4 kHz was ${s.dbAt(4_000.0)} dB", s.dbAt(4_000.0) < -60.0)
    }

    @Test
    fun `a clip shorter than one window still produces a spectrum`() {
        // 100 samples is far less than the 4096-point default; it must shrink rather than
        // return nothing, or the scope shows a blank panel for a short capture.
        val s = Spectrum.estimate(tone(300.0, 16_000, 100.0 / 16_000), 16_000)
        assertTrue(s.magnitudeDb.isNotEmpty())
        assertTrue(s.magnitudeDb.all { it.isFinite() })
    }

    @Test
    fun `degenerate input does not produce infinities`() {
        assertEquals(0, Spectrum.estimate(DoubleArray(0), 16_000).magnitudeDb.size)
        // Silence: every bin is a true zero, which is -infinity in dB without the floor.
        val silent = Spectrum.estimate(DoubleArray(16_000), 16_000)
        assertTrue(silent.magnitudeDb.all { it == Spectrum.FLOOR_DB })
    }

    // --- absolute calibration, which the dBFS axis depends on ---

    @Test
    fun `a tone reads back its own amplitude`() {
        // The whole point of the scaling: a 1000-count tone must measure 1000 counts, or
        // every voltage the scope prints is wrong by a constant nobody would notice.
        val s = Spectrum.estimate(tone(500.0, 16_000, 2.0, amp = 1000.0), 16_000)
        assertEquals(1000.0, s.amplitudeAt(500.0), 20.0)

        // Linear all the way up: a full-scale tone measures full scale, not something
        // window-dependent.
        val full = Spectrum.estimate(tone(500.0, 16_000, 2.0, amp = Spectrum.FULL_SCALE), 16_000)
        assertEquals(Spectrum.FULL_SCALE, full.amplitudeAt(500.0), Spectrum.FULL_SCALE * 0.02)
    }

    @Test
    fun `dividing two spectra recovers the filter between them`() {
        // This is what the scope's response plot does, and the only gain measurement it
        // can honestly make: the input's own spectrum cancels, leaving the filter.
        val input = noise(16_000, 8.0)
        val output = probeLowpass(input)
        val x = Spectrum.estimate(input, 16_000)
        val y = Spectrum.estimate(output, 16_000)

        listOf(100.0, 300.0, PROBE_CUTOFF_HZ, 800.0, 1_200.0).forEach { hz ->
            val measured = 20.0 * log10(y.amplitudeAt(hz) / x.amplitudeAt(hz))
            val spec = Butterworth.zeroPhaseLowpassDb(hz, PROBE_CUTOFF_HZ, PROBE_ORDER)
            assertEquals("At $hz Hz: measured $measured dB, spec $spec dB", spec, measured, 3.0)
        }
    }

    @Test
    fun `amplitude does not depend on how long the clip is`() {
        // A scaling that forgot to average over windows, or divided by the wrong window
        // factor, would drift with clip length and make two captures incomparable.
        val short = Spectrum.estimate(tone(500.0, 16_000, 1.0, amp = 1000.0), 16_000)
        val long = Spectrum.estimate(tone(500.0, 16_000, 8.0, amp = 1000.0), 16_000)
        assertEquals(short.amplitudeAt(500.0), long.amplitudeAt(500.0), 20.0)
    }

    // --- Butterworth as a measurement probe ---
    //
    // The app no longer filters (see PcgSpec), so none of the filtering below is applied
    // to a real recording. It stays because a filter whose response is known in closed
    // form is the only honest way to check that the spectrum estimator reports dB
    // correctly — and because Butterworth is still the code that would be reinstated if
    // the algorithm team ever asked for band limiting back.

    @Test
    fun `the corner sits 6 dB down, as a zero-phase order-4 design should`() {
        // Running an order-4 Butterworth forward and backward squares its magnitude
        // response, so at the corner |H| = (1/sqrt 2)^2 = 0.5 — a power ratio of 0.25,
        // i.e. 6 dB, not the 3 dB a single causal pass would give.
        val filtered = probeLowpass(noise(16_000, 8.0))
        val s = Spectrum.estimate(filtered, 16_000)

        val relative = s.dbAt(PROBE_CUTOFF_HZ) - s.dbAt(100.0)
        assertEquals("Corner response was $relative dB relative to 100 Hz", -6.0, relative, 3.0)
    }

    @Test
    fun `the skirt falls at the rate an order-4 zero-phase filter implies`() {
        val filtered = probeLowpass(noise(16_000, 8.0))
        val s = Spectrum.estimate(filtered, 16_000)
        val reference = s.dbAt(100.0)

        // |H|^2 = 1 / (1 + (f/600)^8)^2, in power dB.
        listOf(800.0 to -20.8, 1_200.0 to -48.2, 1_800.0 to -79.1).forEach { (hz, expected) ->
            val measured = s.dbAt(hz) - reference
            assertTrue(
                "At $hz Hz expected about $expected dB but measured $measured dB",
                measured < expected + 6.0,
            )
        }
    }

    @Test
    fun `the passband is flat where heart sounds live`() {
        val filtered = probeLowpass(noise(16_000, 8.0))
        val s = Spectrum.estimate(filtered, 16_000)
        val reference = s.dbAt(100.0)

        // Nothing below 300 Hz may be meaningfully attenuated — that is the band the
        // diagnosis depends on.
        listOf(25.0, 50.0, 150.0, 300.0).forEach { hz ->
            val measured = s.dbAt(hz) - reference
            assertTrue("$hz Hz was $measured dB off the 100 Hz reference", measured > -2.0)
        }
    }

    // --- probe filter ---

    /**
     * A known filter used only to exercise [Spectrum]. Deliberately the same 600 Hz
     * order-4 zero-phase design the app used to apply, so these expectations did not have
     * to be re-derived when the app stopped filtering.
     */
    private fun probeLowpass(x: DoubleArray) =
        Butterworth.lowpassZeroPhase(x, 16_000, PROBE_CUTOFF_HZ, PROBE_ORDER)

    private companion object {
        const val PROBE_CUTOFF_HZ = 600.0
        const val PROBE_ORDER = 4
    }
}
