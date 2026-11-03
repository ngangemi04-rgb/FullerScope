package com.capstone.stethoscope.analysis.pcg

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Guards the aliasing case that no amount of later filtering can undo.
 *
 * Decimating to 2 kHz folds input frequencies around multiples of 2 kHz, and content near
 * 2 kHz folds *into* the heart-sound band — 1800 Hz and 2200 Hz both land on 200 Hz, 1900
 * and 2100 both land on 100 Hz. Once folded it is indistinguishable from a real heart
 * sound, so no downstream filter can separate it again, theirs or ours.
 *
 * This used to have two lines of defence: the 600 Hz zero-phase lowpass ahead of the
 * decimation, and the resampler's own anti-alias filter. **The lowpass is gone**
 * (2026-09-23 — the algorithm team does its own filtering), so the resampler is now the
 * only thing standing here. That makes these assertions more important than they were,
 * not less: they are what says the remaining filter is sufficient on its own.
 */
class AliasProbeTest {

    private fun sine(f: Double, fs: Int, seconds: Double, amp: Double = 1000.0) =
        DoubleArray((fs * seconds).toInt()) { i -> amp * sin(2.0 * PI * f * i / fs) }

    /** Interior peak; the resampler truncates its convolution at the array edges. */
    private fun interiorPeak(x: ShortArray): Int {
        var p = 0
        for (i in x.size / 4 until x.size * 3 / 4) p = maxOf(p, abs(x[i].toInt()))
        return p
    }

    @Test
    fun `content that folds into the passband is rejected`() {
        val out = File.createTempFile("aliasfold", ".wav").also { it.deleteOnExit() }

        // Input Hz -> where it would land after decimation, all inside 25-400 Hz.
        val foldsIntoPassband = listOf(
            1_800.0, // -> 200
            1_900.0, // -> 100
            2_100.0, // -> 100
            2_200.0, // -> 200
            3_800.0, // -> 200
            3_900.0, // -> 100
        )

        for (f in foldsIntoPassband) {
            val r = PcgPreprocessor.processSamples(sine(f, 8_000, 4.0), 8_000, out)
            val leaked = interiorPeak(r.samples)
            // Measured 0 for all of these, i.e. below one LSB at this amplitude.
            assertTrue(
                "$f Hz aliased into the passband at amplitude $leaked (from 1000)",
                leaked <= 2,
            )
        }
    }

    @Test
    fun `content that folds above the passband is also rejected`() {
        val out = File.createTempFile("aliashigh", ".wav").also { it.deleteOnExit() }
        // These fold to 500-1000 Hz: inside the output's Nyquist, so nothing downstream
        // would remove them if they got through, and nothing upstream filters any more.
        for (f in listOf(1_500.0, 2_500.0, 2_900.0, 3_000.0)) {
            val r = PcgPreprocessor.processSamples(sine(f, 8_000, 4.0), 8_000, out)
            assertTrue(
                "$f Hz survived at ${interiorPeak(r.samples)}",
                interiorPeak(r.samples) <= 2,
            )
        }
    }
}
