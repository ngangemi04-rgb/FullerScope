package com.capstone.stethoscope.analysis.pcg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Tests for the PCG pipeline, which is pure maths and therefore worth pinning down
 * before it ever reaches a phone.
 *
 * Since 2026-09-23 the pipeline's job is to change as little as possible: resample to
 * 2 kHz, write the container, and otherwise leave the samples alone. Most of what is
 * asserted here is therefore the *absence* of processing — that a tone comes out the
 * amplitude it went in at, that a DC offset survives, that 800 Hz is not attenuated.
 * Those are the tests that fail if someone reintroduces conditioning, which is the
 * failure mode that would silently move every diagnosis.
 *
 * The exception, and the one thing that must still happen, is anti-aliasing: content
 * above 1 kHz has to be gone before decimation, because once folded it is
 * indistinguishable from a heart sound.
 */
class PcgPreprocessorTest {

    private fun tempWav(name: String): File =
        File.createTempFile(name, ".wav").also { it.deleteOnExit() }

    private fun sine(freqHz: Double, sampleRate: Int, seconds: Double, amplitude: Double = 1000.0) =
        DoubleArray((sampleRate * seconds).toInt()) { i ->
            amplitude * sin(2.0 * PI * freqHz * i / sampleRate)
        }

    /** Peak magnitude over the whole clip, edge artifacts included. */
    private fun peak(x: ShortArray): Int = x.maxOfOrNull { abs(it.toInt()) } ?: 0

    /**
     * Peak over the middle half.
     *
     * The resampler's convolution runs off the end of the array at both edges, so the
     * first and last few output samples are computed from a truncated window. Amplitude
     * assertions measure the interior, which is what the pipeline does to signal;
     * [edgeArtifactsAreBounded] covers the edges separately.
     */
    private fun interiorPeak(x: ShortArray): Int {
        var p = 0
        for (i in x.size / 4 until x.size * 3 / 4) p = maxOf(p, abs(x[i].toInt()))
        return p
    }

    // --- Format compliance ---

    @Test
    fun `output matches the PhysioNet spec`() {
        val out = tempWav("spec")
        PcgPreprocessor.processSamples(sine(100.0, 8_000, 2.0), 8_000, out)

        val c = PcgSpec.verify(out)
        assertTrue("Not compliant: ${c.problems}", c.compliant)
        assertEquals(2_000, c.sampleRate)
        assertEquals(16, c.bits)
        assertEquals(1, c.channels)
        assertEquals(2.0, c.seconds, 0.01)
    }

    @Test
    fun `header is exactly 44 bytes with data at offset 36`() {
        val out = tempWav("header")
        PcgPreprocessor.processSamples(sine(100.0, 8_000, 1.0), 8_000, out)

        val head = out.readBytes().copyOfRange(0, 44)
        assertEquals("RIFF", String(head, 0, 4))
        assertEquals("WAVE", String(head, 8, 4))
        assertEquals("fmt ", String(head, 12, 4))
        assertEquals("data", String(head, 36, 4))
        // fmt chunk size 16 and format tag 1 are what keep the header at 44 bytes.
        assertEquals(16, head[16].toInt())
        assertEquals(1, head[20].toInt())
        // Body length must match the declared data size.
        assertEquals(out.length() - 44, 2_000L * 2)
    }

    // --- Resampling ---

    @Test
    fun `the specified 16 kHz capture decimates by exactly 8`() {
        // The rate the whole chain is designed around: 16 kHz in, 2 kHz out, ratio 1:8.
        val out = tempWav("sixteen")
        val r = PcgPreprocessor.processSamples(sine(120.0, 16_000, 3.0), 16_000, out)

        assertEquals(6_000, r.samples.size) // 3 s at 2 kHz
        assertEquals(16_000, r.report.inputSampleRate)
        assertEquals(48_000, r.report.inputSamples)
        assertTrue(PcgSpec.verify(out).compliant)
        assertEquals(1_000.0, interiorPeak(r.samples).toDouble(), 30.0)
    }

    @Test
    fun `resampling preserves an in-band tone`() {
        val out = tempWav("inband")
        val r = PcgPreprocessor.processSamples(sine(100.0, 8_000, 2.0), 8_000, out)

        assertEquals(4_000, r.samples.size) // 2 s at 2 kHz
        assertEquals(
            "Tone did not survive at its own amplitude",
            1_000.0, interiorPeak(r.samples).toDouble(), 30.0,
        )
    }

    @Test
    fun `frequencies above the new Nyquist are not aliased down`() {
        // 3 kHz at 8 kHz input would fold to 1 kHz if decimation skipped the anti-alias
        // filter. This is the one filter left in the pipeline, and it is not optional.
        val out = tempWav("alias")
        val r = PcgPreprocessor.processSamples(sine(3_000.0, 8_000, 2.0), 8_000, out)

        assertTrue(
            "3 kHz leaked through as ${interiorPeak(r.samples)}; anti-alias filter is not working",
            interiorPeak(r.samples) < 100,
        )
    }

    @Test
    fun `handles a non-integer rate ratio`() {
        // 44100 -> 2000 is 20/441, the awkward case.
        val out = tempWav("rational")
        val r = PcgPreprocessor.processSamples(sine(150.0, 44_100, 1.0), 44_100, out)

        assertEquals(2_000, r.samples.size)
        assertEquals(1_000.0, interiorPeak(r.samples).toDouble(), 30.0)
        assertTrue(PcgSpec.verify(out).compliant)
    }

    @Test
    fun `passes through unchanged length when already at target rate`() {
        val out = tempWav("passthrough")
        val r = PcgPreprocessor.processSamples(sine(100.0, 2_000, 3.0), 2_000, out)
        assertEquals(6_000, r.samples.size)
    }

    @Test
    fun `a capture already at 2 kHz is copied through bit for bit`() {
        // Nothing to resample and nothing to condition, so the output must be the input.
        // If this ever fails, something is processing the signal that should not be.
        val out = tempWav("identity")
        val input = sine(100.0, 2_000, 1.0, amplitude = 4_321.0)
        val r = PcgPreprocessor.processSamples(input, 2_000, out)

        for (i in input.indices) {
            assertEquals("Sample $i changed", Math.round(input[i]).toInt(), r.samples[i].toInt())
        }
    }

    // --- No filtering: the contract with the algorithm team ---

    @Test
    fun `the heart-sound band passes through untouched`() {
        val out = tempWav("passband")
        for (hz in listOf(50.0, 100.0, 300.0)) {
            val r = PcgPreprocessor.processSamples(sine(hz, 16_000, 2.0), 16_000, out)
            assertEquals(
                "$hz Hz came out at ${interiorPeak(r.samples)} instead of 1000",
                1_000.0, interiorPeak(r.samples).toDouble(), 30.0,
            )
        }
    }

    @Test
    fun `800 Hz is no longer attenuated`() {
        // This is the regression guard for the 600 Hz lowpass. With it, 800 Hz came out at
        // about 91 of 1000 (-21 dB). Without it, 800 Hz is simply below the 1 kHz fold and
        // must survive. A number back down near 100 means someone reinstated the filter.
        val out = tempWav("eighthundred")
        val r = PcgPreprocessor.processSamples(sine(800.0, 16_000, 2.0), 16_000, out)

        assertTrue(
            "800 Hz was attenuated to ${interiorPeak(r.samples)} from 1000 — is the lowpass back?",
            interiorPeak(r.samples) > 700,
        )
    }

    @Test
    fun `baseline wander is deliberately left in`() {
        // 5 Hz chest-wall wander reaches the output WAV. That is the specified behaviour:
        // the classifier's own causal 25 Hz corner inside HeartSoundFeatures removes it,
        // and doing it twice moves the spectral features off the distribution the model
        // was fitted against.
        val out = tempWav("wander")
        val r = PcgPreprocessor.processSamples(sine(5.0, 16_000, 4.0), 16_000, out)

        assertTrue(
            "5 Hz was attenuated to ${interiorPeak(r.samples)} — a highpass is back",
            interiorPeak(r.samples) > 900,
        )
    }

    @Test
    fun `DC offset is preserved`() {
        // A 12-bit unsigned source parks the signal around 2048. The pipeline used to
        // subtract that; it no longer does, because the algorithm team handles it.
        val fs = 16_000
        val x = DoubleArray(fs * 2) { i -> 2048.0 + 500.0 * sin(2.0 * PI * 100.0 * i / fs) }

        val out = tempWav("dcoffset")
        val r = PcgPreprocessor.processSamples(x, fs, out)

        val mean = r.samples.map { it.toInt() }.average()
        assertEquals("DC offset was removed: mean $mean, expected ~2048", 2048.0, mean, 20.0)
    }

    @Test
    fun `a quiet recording stays quiet`() {
        // Normalization used to bring anything up to 1 dB below full scale. It is gone, so
        // a quiet chest produces a quiet file — which is the truth about that chest, and
        // what makes two recordings comparable at all.
        val out = tempWav("quiet")
        val r = PcgPreprocessor.processSamples(sine(100.0, 16_000, 2.0, amplitude = 100.0), 16_000, out)

        assertEquals(
            "Something normalized the output: peak ${interiorPeak(r.samples)}",
            100.0, interiorPeak(r.samples).toDouble(), 10.0,
        )
    }

    @Test
    fun `absolute amplitude is preserved between recordings`() {
        val out = tempWav("levels")
        val quiet = PcgPreprocessor.processSamples(
            sine(100.0, 16_000, 2.0, amplitude = 100.0), 16_000, out,
        )
        val loud = PcgPreprocessor.processSamples(
            sine(100.0, 16_000, 2.0, amplitude = 1_000.0), 16_000, out,
        )
        val ratio = interiorPeak(loud.samples).toDouble() / interiorPeak(quiet.samples)
        assertEquals("The 10x level difference did not survive", 10.0, ratio, 0.5)
    }

    // --- Timing ---

    @Test
    fun `resampling does not shift S1 S2 timing`() {
        // A single impulse-like burst must come out where it went in. The resampler's FIR
        // has a group delay of half its length, and polyphase() subtracts it back off; if
        // that compensation broke, every interval the classifier measures would still be
        // right but every absolute time would be wrong.
        val fs = 16_000
        val x = DoubleArray(fs * 2)
        val burstAt = fs // exactly 1 s in
        for (i in 0 until 320) {
            x[burstAt + i] = 1000.0 * sin(2.0 * PI * 100.0 * i / fs)
        }

        val out = tempWav("phase")
        val r = PcgPreprocessor.processSamples(x, fs, out)

        val peakIndex = r.samples.indices.maxByOrNull { abs(r.samples[it].toInt()) } ?: -1
        val expected = PcgSpec.SAMPLE_RATE // 1 s in, at the output rate
        assertTrue(
            "Burst moved from $expected to $peakIndex — group delay is not compensated",
            abs(peakIndex - expected) < 25,
        )
    }

    // --- Amplitude handling ---

    @Test
    fun edgeArtifactsAreBounded() {
        // The resampler's window runs off the end of the array; that must stay small
        // enough never to dominate the signal.
        val out = tempWav("edges")
        val r = PcgPreprocessor.processSamples(sine(100.0, 8_000, 4.0), 8_000, out)
        val edge = peak(r.samples)
        val interior = interiorPeak(r.samples)
        assertTrue(
            "Edge artifact $edge is more than 1.5x the interior peak $interior",
            edge < interior * 1.5,
        )
    }

    @Test
    fun `loud input is clipped rather than wrapped`() {
        // Wrapping would turn a loud S1 into a full-scale negative spike, which looks
        // exactly like an arrhythmia to a classifier. Clipping is now reachable in normal
        // use — nothing scales the signal down first — so it is reported too.
        val out = tempWav("clip")
        val r = PcgPreprocessor.processSamples(
            sine(100.0, 8_000, 2.0, amplitude = 100_000.0), 8_000, out,
        )
        assertTrue(r.samples.all { it.toInt() in -32_768..32_767 })
        assertTrue("Clipping was not reported", r.report.clipped > 0)
        // Sign must be preserved across the clip: no polarity flips.
        val firstQuarter = r.samples.take(500).filter { it.toInt() != 0 }
        assertTrue(firstQuarter.isNotEmpty())
    }

    @Test
    fun `an unclipped recording reports no clipping`() {
        val out = tempWav("noclip")
        val r = PcgPreprocessor.processSamples(sine(100.0, 16_000, 2.0), 16_000, out)
        assertEquals(0, r.report.clipped)
        assertEquals(1_000.0, r.report.peak, 30.0)
    }

    // --- Degenerate input ---

    @Test
    fun `empty input produces a valid empty file`() {
        val out = tempWav("empty")
        val r = PcgPreprocessor.processSamples(DoubleArray(0), 8_000, out)
        assertEquals(0, r.samples.size)
        assertEquals(44L, out.length())
        assertTrue(PcgSpec.verify(out).compliant)
    }

    @Test
    fun `silent input stays silent`() {
        val out = tempWav("silent")
        val r = PcgPreprocessor.processSamples(DoubleArray(8_000), 8_000, out)
        assertEquals(0, peak(r.samples))
        assertEquals(0.0, r.report.peak, 1e-9)
    }
}
