package com.capstone.stethoscope.analysis.murmur

import com.capstone.stethoscope.analysis.MurmurDriver
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

    @Test
    fun `contributions explain the score exactly`() {
        for (c in cases("featureOnly")) {
            val f = c.doubles("features")
            val z = model.intercept + clf.contributions(f).sum()
            assertEquals(c.num("score"), 1 / (1 + kotlin.math.exp(-z)), TOL)
        }
    }

    @Test
    fun `main driver pushes towards the decision that was made`() {
        for (r in cases("recordings")) {
            val seg = r.list("seg").map { row -> (row as List<Any?>).map { (it as Double).toInt() }.toIntArray() }
            val out = clf.classify(r.doubles("audio"), r.int("sampleRate"), seg.toTypedArray())
            val k = out.mainDriver!!
            val c = out.contributions
            if (out.isMurmur) {
                assertTrue("${r.str("name")}: murmur driver must raise the score", c[k] > 0)
                assertEquals(c.max(), c[k], 0.0)
            } else {
                assertTrue("${r.str("name")}: clear driver must lower the score", c[k] < 0)
                assertEquals(c.min(), c[k], 0.0)
            }
        }
        val failed = clf.classify(DoubleArray(4000), 2000, arrayOf(intArrayOf(1, 300, 700, 1000, 1500)))
        assertEquals(null, failed.mainDriver)
    }

    @Test
    fun `driver survives the round trip through the database`() {
        val d = MurmurDriver("sysFlat/S1", high = true)
        assertEquals("sysFlat/S1:high", d.encode())
        assertEquals(d, MurmurDriver.decode(d.encode()))
        assertEquals(MurmurDriver("diaFlat/S1", false), MurmurDriver.decode("diaFlat/S1:low"))
        assertEquals(null, MurmurDriver.decode("garbage"))
        assertEquals(null, MurmurDriver.decode(null))
    }

    @Test
    fun `threshold and reported performance come from the model file`() {
        // The team asked that the threshold be read, not hard-coded (0.428 -> 0.165 on retraining).
        assertEquals(0.1653, model.threshold, 1e-4)
        assertEquals(0.671, model.crossValidatedSe!!, 1e-3)
        assertEquals(0.775, model.crossValidatedSp!!, 1e-3)
        assertEquals(listOf("diaFlat/S1", "sysOccupancy", "sysFlat/S1", "sysEnvCorr"), clf.featureNames)
    }
}
