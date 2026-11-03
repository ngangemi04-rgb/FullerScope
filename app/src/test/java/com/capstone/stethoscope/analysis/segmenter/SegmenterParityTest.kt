package com.capstone.stethoscope.analysis.segmenter

import com.capstone.stethoscope.analysis.HeartRhythm
import com.capstone.stethoscope.analysis.SignalQuality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Runs the algorithm team's `testvectors.json` through the port, one stage at a time, in
 * the order their README says to check them — so a failure names the first stage that is
 * wrong rather than only the final answer.
 *
 * Tolerances are theirs: floats need not match exactly (their network runs in single
 * precision), but decoded states must agree and S1 times must land within 5 ms.
 */
class SegmenterParityTest {

    companion object {
        private lateinit var tv: Map<String, Any?>
        private lateinit var seg: HeartSoundSegmenter
        private lateinit var run: HeartSoundSegmenter.Segmentation

        @BeforeClass
        @JvmStatic
        fun setUp() {
            @Suppress("UNCHECKED_CAST")
            tv = MiniJson.parse(
                SegmenterParityTest::class.java.getResource("/segmenter/testvectors.json")!!.readText(),
            ) as Map<String, Any?>
            seg = HeartSoundSegmenter({ File("src/main/assets/segmenter/model.json").inputStream() })
            run = runBlocking { seg.segment(tv.doubles("rawAudio"), tv.int("rawSampleRate")) }
        }

        private fun maxAbsDiff(a: DoubleArray, b: DoubleArray): Double {
            assertEquals("length", b.size, a.size)
            return a.indices.maxOf { abs(a[it] - b[it]) }
        }
    }

    @Test
    fun `1 bandpass matches`() {
        val d = maxAbsDiff(run.afterBandpass, tv.doubles("afterBandpass"))
        assertTrue("afterBandpass differs by $d", d < 1e-9)
    }

    @Test
    fun `2 percentile and normalisation match`() {
        assertEquals(tv.num("percentile995"), run.percentile995, 1e-12)
        val d = maxAbsDiff(run.afterNormalise, tv.doubles("afterNormalise"))
        assertTrue("afterNormalise differs by $d", d < 1e-8)
    }

    @Test
    fun `3 single window through the network matches`() {
        val w = tv.obj("singleWindow")
        val input = w.floats("input")
        val expected = w.doubles("output")
        val got = seg.forwardWindow(input)
        for (k in expected.indices) {
            assertEquals("class $k", expected[k], got[k].toDouble(), 1e-4)
        }
    }

    @Test
    fun `4 frame centres match`() {
        val expected = tv.doubles("frameCentres").map { it.toInt() }.toIntArray()
        assertArrayEquals(expected, run.frameCentres)
    }

    @Test
    fun `5 posteriors agree, tightly where the network is confident`() {
        @Suppress("UNCHECKED_CAST")
        val expected = (tv.list("posteriors") as List<List<Double>>)
        assertEquals(expected.size, run.posteriors.size)
        var worstConfident = 0.0
        var worstAny = 0.0
        for (i in expected.indices) {
            val d = (0 until 4).maxOf { abs(expected[i][it] - run.posteriors[i][it]) }
            worstAny = maxOf(worstAny, d)
            if (expected[i].max() > 0.9) worstConfident = maxOf(worstConfident, d)
        }
        assertTrue("confident frames differ by $worstConfident", worstConfident < 2e-2)
        assertTrue("some frame differs by $worstAny", worstAny < 5e-2)
    }

    @Test
    fun `6 decoded states match`() {
        val expected = tv.doubles("decodedStates").map { it.toInt() }
        val agree = expected.indices.count { expected[it] == run.states[it] }.toDouble() / expected.size
        assertTrue("states agree on only ${agree * 100}% of frames", agree > 0.99)
    }

    @Test
    fun `7 S1 events and heart rate match - 10 beats, 60_3 bpm`() {
        val expected = tv.doubles("detectedS1Seconds")
        assertEquals(10, expected.size)
        assertEquals("S1 count", expected.size, run.s1Times.size)
        val d = maxAbsDiff(run.s1Times, expected)
        assertTrue("S1 timing off by ${d * 1000} ms", d < 0.005)
        assertEquals(tv.num("heartRateBpm"), run.bpm, 0.5)
        assertEquals(tv.num("confidence"), run.confidence, 0.005)
    }

    @Test
    fun `8 the test recording reads as a good, regular 60 bpm`() {
        val r = HeartSoundSegmenter.toResult(run)
        assertEquals(SignalQuality.GOOD, r.quality)
        assertEquals(HeartRhythm.REGULAR, r.rhythm)
        assertEquals(60.3f, r.heartRateBpm!!, 0.5f)
        assertEquals(10, r.beatCount)
    }

    @Test
    fun `9 a 16 kHz copy of the recording gives the same beats`() {
        // The one stage the vectors cannot check: resampling. Upsample the 2 kHz vector
        // 8x and make sure the port brings it back to the same answer.
        val raw = tv.doubles("rawAudio")
        val up = com.capstone.stethoscope.analysis.pcg.Resampler.resample(raw, 2000, 16000)
        val r = runBlocking { seg.segment(up, 16000) }
        assertEquals(run.s1Times.size, r.s1Times.size)
        val d = maxAbsDiff(r.s1Times, run.s1Times)
        assertTrue("16 kHz S1 timing off by ${d * 1000} ms", d < 0.005)
    }
}
