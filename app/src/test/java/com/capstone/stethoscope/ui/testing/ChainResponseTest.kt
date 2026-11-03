package com.capstone.stethoscope.ui.testing

import com.capstone.stethoscope.analysis.pcg.PcgPreprocessor
import com.capstone.stethoscope.analysis.pcg.PcgSpec
import com.capstone.stethoscope.analysis.pcg.Spectrum
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * The testing screen's "Chain response" must read 0 dB for a chain that does not alter
 * the signal. On a real phone it read −10 to −22 dB for mic noise, because it divided a
 * 3.9 Hz-bin spectrum by a 0.49 Hz-bin one — tone-calibrated amplitudes, broadband input.
 */
class ChainResponseTest {

    private val rate = PcgSpec.CAPTURE_SAMPLE_RATE

    private fun response(raw: DoubleArray): TestingViewModel.Response {
        val scratch = File.createTempFile("chain", ".wav")
        val pcg = try {
            PcgPreprocessor.processSamples(raw, rate, scratch)
        } finally {
            scratch.delete()
        }
        val out = DoubleArray(pcg.samples.size) { pcg.samples[it].toDouble() }
        val input = Spectrum.estimate(raw, rate)
        val output = Spectrum.estimate(
            out, PcgSpec.SAMPLE_RATE,
            fftSize = Spectrum.matchedFftSize(PcgSpec.SAMPLE_RATE, rate),
        )
        return TestingViewModel.Response(input, output, output.nyquistHz)
    }

    private fun assertFlat(r: TestingViewModel.Response, what: String) {
        for (hz in listOf(50.0, 100.0, 200.0, 400.0, 600.0, 800.0)) {
            assertEquals("$what at $hz Hz", 0.0, r.measuredDbAt(hz), 1.0)
        }
    }

    @Test
    fun `white noise through the chain reads 0 dB`() {
        val rnd = Random(1)
        val raw = DoubleArray(rate * 20) { rnd.nextGaussian() * 2000.0 }
        assertFlat(response(raw), "white noise")
    }

    @Test
    fun `low-frequency-heavy noise reads 0 dB`() {
        // Leaky-integrated noise: most energy at the bottom, like a mic or a chest.
        val rnd = Random(2)
        var acc = 0.0
        val raw = DoubleArray(rate * 20) {
            acc = 0.995 * acc + rnd.nextGaussian() * 200.0
            acc
        }
        assertFlat(response(raw), "red noise")
    }

    @Test
    fun `a tone reads 0 dB`() {
        val raw = DoubleArray(rate * 20) { 8000.0 * sin(2 * PI * 200.0 * it / rate) }
        assertEquals(0.0, response(raw).measuredDbAt(200.0), 0.5)
    }

    @Test
    fun `matched FFT size gives equal bin widths`() {
        val n = Spectrum.matchedFftSize(2000, 16000)
        assertEquals(512, n)
        assertEquals(16000.0 / Spectrum.FFT_SIZE, 2000.0 / n, 1e-9)
    }
}
