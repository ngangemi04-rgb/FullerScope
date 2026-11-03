package com.capstone.stethoscope.analysis.murmur

import com.capstone.stethoscope.analysis.heartsound.Fft
import com.capstone.stethoscope.analysis.segmenter.MiniJson
import com.capstone.stethoscope.analysis.segmenter.doubles
import com.capstone.stethoscope.analysis.segmenter.int
import com.capstone.stethoscope.analysis.segmenter.list
import com.capstone.stethoscope.analysis.segmenter.num
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The algorithm team's murmur classifier — a Kotlin port of `classifyMurmur.m` (the
 * reference, README and test vectors live in `algorithm/murmur/`).
 *
 * A four-feature logistic regression on top of the segmenter: it reads the signal the
 * segmenter worked on ([com.capstone.stethoscope.analysis.segmenter.HeartSoundSegmenter.Preprocessed.normalised])
 * and the beat boundaries it found ([beatBoundaries]), and returns one score per
 * recording. Every feature is a ratio within a single beat, so stethoscope gain and
 * frequency response divide out.
 *
 * `MurmurParityTest` runs the team's three-stage `testvectors.json`. If you change
 * anything here, that test is what tells you whether it still matches the MATLAB.
 */
class MurmurClassifier(private val model: MurmurModel) {

    /**
     * MATLAB's `out` struct.
     *
     * @property ok       false when the recording cannot be measured (fewer than three
     *                    usable beats). That is "unable to analyse", not a negative.
     * @property score    0..1; NaN when not [ok].
     * @property features raw values before transform and scaling, in the model's order.
     * @property nBeats   beats that contributed.
     */
    class Result(
        val ok: Boolean,
        val score: Double,
        val isMurmur: Boolean,
        val features: DoubleArray,
        val nBeats: Int,
    )

    /**
     * @param x   the segmenter's normalised 2 kHz signal — not raw audio.
     * @param fs  sample rate of [x].
     * @param seg one row per complete beat, 1-based sample indices
     *            `[s1start, sysStart, s2start, diaStart, diaEnd]`, as from [beatBoundaries].
     */
    fun classify(x: DoubleArray, fs: Int, seg: Array<IntArray>): Result {
        val failed = Result(false, Double.NaN, false, DoubleArray(4) { Double.NaN }, 0)
        val n = x.size
        if (seg.isEmpty() || seg.any { it.size != 5 }) return failed

        val r1 = DoubleArray(seg.size) { Double.NaN }  // systolic flatness / S1 flatness
        val r2 = DoubleArray(seg.size) { Double.NaN }  // diastolic flatness / S1 flatness
        val occ = DoubleArray(seg.size) { Double.NaN } // share of systole louder than diastole
        for ((i, row) in seg.withIndex()) {
            val s1 = cutSegment(x, row[0], row[1], n)
            val sy = cutSegment(x, row[1], row[2], n)
            val di = cutSegment(x, row[3], row[4], n)
            if (s1 == null || sy == null || di == null) continue

            val f1 = spectralFlatness(s1, fs, model.bandLow, model.bandHigh)
            val fsy = spectralFlatness(sy, fs, model.bandLow, model.bandHigh)
            val fd = spectralFlatness(di, fs, model.bandLow, model.bandHigh)
            r1[i] = fsy / (f1 + EPS)
            r2[i] = fd / (f1 + EPS)
            occ[i] = occupancy(sy, di, model.occupancyBins)
        }

        val envCorr = beatConsistency(x, seg, n, model.envBins, model.envTrimFraction)
        val f = doubleArrayOf(medianOmitNaN(r2), medianOmitNaN(occ), medianOmitNaN(r1), envCorr)
        val nBeats = r1.count { it.isFinite() }
        if (f.any { !it.isFinite() }) return Result(false, Double.NaN, false, f, nBeats)

        val score = score(f)
        return Result(true, score, score >= model.threshold, f, nBeats)
    }

    /** z = intercept + Σ w·(t(f) − centre)/scale, through the logistic. The README's stage 3. */
    fun score(features: DoubleArray): Double {
        var z = model.intercept
        for (k in features.indices) {
            var v = features[k]
            if (model.logTransform[k]) v = ln(max(v, EPS))
            z += model.weight[k] * (v - model.center[k]) / model.scale[k]
        }
        return 1 / (1 + exp(-z))
    }

    val threshold: Double get() = model.threshold

    companion object {
        /** MATLAB's `eps`. */
        private val EPS = Math.ulp(1.0)

        fun load(input: InputStream): MurmurClassifier =
            MurmurClassifier(MurmurModel.fromJson(input.bufferedReader().readText()))

        /**
         * The segmenter's per-frame phases → one row per complete beat, in the 1-based
         * sample indices [classify] takes.
         *
         * A beat is a run of S1, then systole, S2 and diastole, with the next S1 after it —
         * a diastole that runs into the end of the recording is not a complete beat. Frame
         * `i` is centred on sample `1 + i*hop`, and each phase starts at the centre of its
         * first frame. The diastole ends at the centre of its *last* frame, which is what
         * the team's test vectors show (diaEnd sits exactly one frame before the next S1).
         *
         * @param states 1 = S1, 2 = systole, 3 = S2, 4 = diastole, one per frame.
         */
        fun beatBoundaries(states: IntArray, hop: Int): Array<IntArray> {
            // Runs as (code, firstFrame, lastFrame).
            val runs = ArrayList<IntArray>()
            var i = 0
            while (i < states.size) {
                var j = i
                while (j + 1 < states.size && states[j + 1] == states[i]) j++
                runs += intArrayOf(states[i], i, j)
                i = j + 1
            }
            fun centre(frame: Int) = 1 + frame * hop
            val out = ArrayList<IntArray>()
            for (r in 0 until runs.size - 4) {
                if ((0..4).any { runs[r + it][0] != (it % 4) + 1 }) continue
                out += intArrayOf(
                    centre(runs[r][1]),
                    centre(runs[r + 1][1]),
                    centre(runs[r + 2][1]),
                    centre(runs[r + 3][1]),
                    centre(runs[r + 3][2]),
                )
            }
            return out.toTypedArray()
        }

        // ------------------------------------------------------------ segment helpers

        /** x(a .. b-1) in MATLAB terms, clipped; null when shorter than 129 samples. */
        private fun cutSegment(x: DoubleArray, a0: Int, b0: Int, n: Int): DoubleArray? {
            val a = max(1, a0)
            val b = min(n, b0 - 1)
            return if (b - a < 128) null else x.copyOfRange(a - 1, b)
        }

        internal fun medianOmitNaN(v: DoubleArray): Double {
            val s = v.filter { it.isFinite() }.sorted()
            if (s.isEmpty()) return Double.NaN
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }

        /** MATLAB `round` for the non-negative values used here: half away from zero. */
        private fun roundHalfUp(v: Double): Int = floor(v + 0.5).toInt()

        /** 1-based bin edges `round(linspace(1, n+1, k+1))`. */
        private fun binEdges(n: Int, k: Int): IntArray {
            val step = n.toDouble() / k
            return IntArray(k + 1) { if (it == k) n + 1 else roundHalfUp(1 + it * step) }
        }

        private fun rms(x: DoubleArray, from: Int, to: Int): Double {
            var acc = 0.0
            for (i in from until to) acc += x[i] * x[i]
            return sqrt(acc / (to - from))
        }

        // ------------------------------------------------------ features 1 and 3

        /**
         * Geometric over arithmetic mean of the Welch PSD between [lo] and [hi] Hz. The
         * PSD's scale cancels in the ratio, so it is left unnormalised as in the MATLAB.
         */
        internal fun spectralFlatness(s: DoubleArray, fs: Int, lo: Double, hi: Double): Double {
            val n = s.size
            if (n < 128) return Double.NaN
            val w = min(max(2.0.pow(floor(log2(n / 4.0))).toInt(), 64), 512)
            if (n < 2 * w) return Double.NaN
            val nfft = max(512, 2 * w)

            val mean = s.average()
            val p = welchPsd(DoubleArray(n) { s[it] - mean }, w, roundHalfUp(w / 2.0), nfft)

            var count = 0
            var logSum = 0.0
            var sum = 0.0
            for (k in p.indices) {
                val f = k * (fs.toDouble() / nfft)
                if (f < lo || f > hi) continue
                val v = p[k]
                if (!v.isFinite() || v <= 0) continue
                count++
                logSum += ln(v)
                sum += v
            }
            if (count < 8) return Double.NaN
            return exp(logSum / count) / (sum / count)
        }

        /** Averaged one-sided periodogram, symmetric Hann, `floor(nfft/2)+1` bins. */
        private fun welchPsd(s: DoubleArray, w: Int, noverlap: Int, nfft: Int): DoubleArray {
            val step = w - noverlap
            val nSeg = (s.size - noverlap) / step
            val bins = nfft / 2 + 1
            val acc = DoubleArray(bins)
            if (nSeg < 1) return acc
            val win = DoubleArray(w) { if (w == 1) 1.0 else 0.5 * (1 - cos(2 * PI * it / (w - 1))) }
            val re = DoubleArray(nfft)
            val im = DoubleArray(nfft)
            for (k in 0 until nSeg) {
                re.fill(0.0); im.fill(0.0)
                val a = k * step
                for (j in 0 until w) re[j] = s[a + j] * win[j]
                Fft.forward(re, im)
                for (b in 0 until bins) acc[b] += re[b] * re[b] + im[b] * im[b]
            }
            for (b in 0 until bins) acc[b] /= nSeg
            return acc
        }

        // ------------------------------------------------------------- feature 2

        /** Share of [k] systole bins louder (RMS) than the same beat's diastole. */
        private fun occupancy(sy: DoubleArray, di: DoubleArray, k: Int): Double {
            if (sy.size < 4 * k) return Double.NaN
            val ref = rms(di, 0, di.size)
            if (!ref.isFinite() || ref <= 0) return Double.NaN
            val idx = binEdges(sy.size, k)
            var hit = 0
            for (b in 0 until k) if (rms(sy, idx[b] - 1, idx[b + 1] - 1) > ref) hit++
            return hit.toDouble() / k
        }

        // ------------------------------------------------------------- feature 4

        /** Median pairwise Pearson correlation of trimmed systoles' log-RMS envelopes. */
        private fun beatConsistency(x: DoubleArray, seg: Array<IntArray>, n: Int, k: Int, trimFrac: Double): Double {
            if (seg.size < 3) return Double.NaN
            val env = ArrayList<DoubleArray>()
            for (row in seg) {
                val a = max(1, row[1])
                val b = min(n, row[2] - 1)
                val len = b - a
                if (len < 4 * k) continue
                val t = roundHalfUp(trimFrac * len)
                val from = a + t       // 1-based, inclusive
                val to = b - t         // 1-based, inclusive
                val m = to - from + 1
                if (m < 2 * k) continue
                val idx = binEdges(m, k)
                val e = DoubleArray(k) { rms(x, from - 1 + idx[it] - 1, from - 1 + idx[it + 1] - 1) }
                for (j in e.indices) e[j] = ln(e[j] + EPS)
                // MATLAB: all finite and std > 0. Compared directly rather than through a
                // computed std, which can come out an ulp above zero for equal values.
                if (e.all { it.isFinite() } && e.any { it != e[0] }) env += e
            }
            if (env.size < 3) return Double.NaN
            val vals = ArrayList<Double>()
            for (i in 0 until env.size - 1) for (j in i + 1 until env.size) vals += pearson(env[i], env[j])
            return medianOmitNaN(vals.toDoubleArray())
        }

        private fun pearson(a: DoubleArray, b: DoubleArray): Double {
            val ma = a.average()
            val mb = b.average()
            var sab = 0.0; var saa = 0.0; var sbb = 0.0
            for (i in a.indices) {
                val da = a[i] - ma
                val db = b[i] - mb
                sab += da * db; saa += da * da; sbb += db * db
            }
            val d = sqrt(saa * sbb)
            return if (d <= 0) Double.NaN else sab / d
        }
    }
}

/** Every constant from `murmurModel.json`, read rather than transcribed. */
class MurmurModel(
    val intercept: Double,
    val weight: DoubleArray,
    val center: DoubleArray,
    val scale: DoubleArray,
    val logTransform: BooleanArray,
    val threshold: Double,
    val bandLow: Double,
    val bandHigh: Double,
    val occupancyBins: Int,
    val envBins: Int,
    val envTrimFraction: Double,
    val sampleRate: Int,
) {
    companion object {
        fun fromJson(text: String): MurmurModel {
            @Suppress("UNCHECKED_CAST")
            val m = MiniJson.parse(text) as Map<String, Any?>
            return MurmurModel(
                intercept = m.num("intercept"),
                weight = m.doubles("weight"),
                center = m.doubles("center"),
                scale = m.doubles("scale"),
                logTransform = m.list("logTransform").map { it as Boolean }.toBooleanArray(),
                threshold = m.num("threshold"),
                bandLow = m.num("bandLow"),
                bandHigh = m.num("bandHigh"),
                occupancyBins = m.int("occupancyBins"),
                envBins = m.int("envBins"),
                envTrimFraction = m.num("envTrimFraction"),
                sampleRate = m.int("sampleRate"),
            )
        }
    }
}
