package com.capstone.stethoscope.analysis.murmur

import com.capstone.stethoscope.analysis.MurmurOutcome
import com.capstone.stethoscope.analysis.SignalQuality
import com.capstone.stethoscope.analysis.segmenter.HeartSoundSegmenter
import com.capstone.stethoscope.analysis.segmenter.MiniJson
import kotlinx.coroutines.runBlocking
import com.capstone.stethoscope.analysis.segmenter.doubles
import com.capstone.stethoscope.analysis.segmenter.int
import com.capstone.stethoscope.analysis.segmenter.list
import com.capstone.stethoscope.analysis.segmenter.num
import com.capstone.stethoscope.analysis.segmenter.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * The algorithm team's `testvectors.json`, in the three stages their README orders them
 * in, so a failure names the first place that is wrong. Their tolerance: 1e-6 on every
 * value, decisions must match exactly.
 */
@Suppress("UNCHECKED_CAST")
class MurmurParityTest {

    companion object {
        private const val TOL = 1e-6
        private lateinit var tv: Map<String, Any?>
        private lateinit var model: MurmurModel
        private lateinit var clf: MurmurClassifier

        @BeforeClass
        @JvmStatic
        fun setUp() {
            tv = MiniJson.parse(
                MurmurParityTest::class.java.getResource("/murmur/testvectors.json")!!.readText(),
            ) as Map<String, Any?>
            model = MurmurModel.fromJson(File("src/main/assets/murmur/model.json").readText())
            clf = MurmurClassifier(model)
        }

        private fun cases(key: String) = tv.list(key).map { it as Map<String, Any?> }
    }

    @Test
    fun `1 flatness matches on the synthetic signals`() {
        for (c in cases("flatnessCases")) {
            val got = MurmurClassifier.spectralFlatness(
                c.doubles("signal"), model.sampleRate, model.bandLow, model.bandHigh,
            )
            assertEquals(c.str("label"), c.num("expected"), got, TOL)
        }
    }

    @Test
    fun `2 audio plus boundaries gives the same features, score and decision`() {
        for (r in cases("recordings")) {
            val seg = r.list("seg").map { row ->
                (row as List<Any?>).map { (it as Double).toInt() }.toIntArray()
            }.toTypedArray()
            val out = clf.classify(r.doubles("audio"), r.int("sampleRate"), seg)
            val name = r.str("name")
            assertTrue("$name not ok", out.ok)
            val want = r.doubles("features")
            for (k in want.indices) {
                val rel = abs(out.features[k] - want[k]) / max(abs(want[k]), 1e-6)
                assertTrue("$name feature $k: ${out.features[k]} vs ${want[k]}", rel < TOL)
            }
            assertEquals("$name score", r.num("score"), out.score, TOL)
            assertEquals("$name decision", r["isMurmur"] as Boolean, out.isMurmur)
        }
    }

    @Test
    fun `3 features to score is the same arithmetic`() {
        for (c in cases("featureOnly")) {
            val s = clf.score(c.doubles("features"))
            assertEquals(c.num("score"), s, TOL)
            assertEquals(c["isMurmur"] as Boolean, s >= model.threshold)
        }
    }

    @Test
    fun `beat boundaries take complete cycles only, on the frame grid`() {
        // Starts mid-diastole, then two full beats, then a beat cut off in diastole.
        val states = intArrayOf(
            4, 4,
            1, 1, 2, 2, 2, 3, 3, 4, 4, 4,
            1, 1, 2, 2, 3, 3, 4, 4,
            1, 2, 3, 4,
        )
        val seg = MurmurClassifier.beatBoundaries(states, hop = 32)
        assertEquals(2, seg.size)
        fun c(frame: Int) = 1 + frame * 32
        assertArrayEquals(intArrayOf(c(2), c(4), c(7), c(9), c(11)), seg[0])
        assertArrayEquals(intArrayOf(c(12), c(14), c(16), c(18), c(19)), seg[1])
    }

    @Test
    fun `runs end to end on the segmenter's own output`() {
        val stv = MiniJson.parse(
            MurmurParityTest::class.java.getResource("/segmenter/testvectors.json")!!.readText(),
        ) as Map<String, Any?>
        val segmenter = HeartSoundSegmenter({ File("src/main/assets/segmenter/model.json").inputStream() })
        val s = runBlocking { segmenter.segment(stv.doubles("rawAudio"), stv.int("rawSampleRate")) }
        val beats = MurmurClassifier.beatBoundaries(s.states, HeartSoundSegmenter.HOP)
        // The test recording has 10 S1 events, so at least 8 complete beats between them.
        assertTrue("only ${beats.size} beats", beats.size >= 8)
        val out = clf.classify(s.afterNormalise, HeartSoundSegmenter.TARGET_FS, beats)
        println("segmenter test vector: ${beats.size} beats, ${out.nBeats} usable, score ${out.score}, features ${out.features.toList()}")
        assertTrue(out.ok)
        val finding = HeartSoundSegmenter.murmurFinding(clf, s, SignalQuality.GOOD)
        assertEquals(out.score.toFloat(), finding.score!!, 0f)
        assertEquals(MurmurOutcome.UNABLE, HeartSoundSegmenter.murmurFinding(clf, s, SignalQuality.POOR).outcome)
    }

    @Test
    fun `too few beats is unable to analyse, not a negative`() {
        val x = DoubleArray(4000) { kotlin.math.sin(it * 0.3) }
        val out = clf.classify(x, 2000, arrayOf(intArrayOf(1, 300, 700, 1000, 1500)))
        assertTrue(!out.ok)
        assertTrue(!out.isMurmur)
    }
}
