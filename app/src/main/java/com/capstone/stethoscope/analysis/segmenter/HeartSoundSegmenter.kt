package com.capstone.stethoscope.analysis.segmenter

import com.capstone.stethoscope.analysis.HeartRhythm
import com.capstone.stethoscope.analysis.HeartStates
import com.capstone.stethoscope.analysis.HeartSignal
import com.capstone.stethoscope.analysis.HeartSoundResult
import com.capstone.stethoscope.analysis.HeartbeatClassifier
import com.capstone.stethoscope.analysis.MurmurFinding
import com.capstone.stethoscope.analysis.MurmurOutcome
import com.capstone.stethoscope.analysis.SignalQuality
import com.capstone.stethoscope.analysis.murmur.MurmurClassifier
import com.capstone.stethoscope.analysis.pcg.Resampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The algorithm team's heart-sound segmenter — a Kotlin port of `segmentHeartSound.m`
 * (the reference, README and test vectors live in `algorithm/segmenter/`).
 *
 * Locates S1, systole, S2 and diastole, and from those reports heart rate, beat-to-beat
 * variability and a signal-quality score. Given a [murmur] classifier it then runs that on
 * the same normalised signal and the beats it found — the team's design: the murmur model
 * "plugs onto the end of segmentHeartSound" with no preprocessing of its own.
 *
 * Four stages, each matching a banner in the MATLAB:
 *  1. resample to 2 kHz → 4th-order Butterworth bandpass 25–400 Hz, zero phase →
 *     divide by the 99.5th percentile of |x|, clip to ±8;
 *  2. reflect-pad by 2048, run the CNN on a 4096-sample window every [hop] samples;
 *  3. Viterbi decode, constrained to S1 → systole → S2 → diastole → S1;
 *  4. one event per run of S1 frames, at the run's most confident frame.
 *
 * `SegmenterParityTest` runs `testvectors.json` through every stage. If you change
 * anything here, that test is what tells you whether it still matches the team's network.
 *
 * @param modelSource opens `model.json`; read once, on first use.
 * @param hop frames are this many samples apart at 2 kHz. 32 is the team's default
 *            (16 ms resolution); the README suggests 64 if this ever needs to run live.
 * @param murmurSource opens the murmur model's JSON; null leaves [HeartSoundResult.murmur] null.
 */
class HeartSoundSegmenter(
    private val modelSource: () -> InputStream,
    private val hop: Int = HOP,
    private val murmurSource: (() -> InputStream)? = null,
) : HeartbeatClassifier {

    private val model: SegmenterModel by lazy { modelSource().use { SegmenterModel.load(it) } }

    private val murmur: MurmurClassifier? by lazy { murmurSource?.invoke()?.use { MurmurClassifier.load(it) } }

    /** Every intermediate of one run, so the port can be compared with the MATLAB. */
    class Segmentation(
        val resampled: DoubleArray,
        val afterBandpass: DoubleArray,
        val percentile995: Double,
        val afterNormalise: DoubleArray,
        /** 1-based sample index (at 2 kHz) each frame is centred on — MATLAB's `centres`. */
        val frameCentres: IntArray,
        /** `frameCentres.size x 4`, row-major, in the model's class order. */
        val posteriors: Array<DoubleArray>,
        /** 1 = S1, 2 = systole, 3 = S2, 4 = diastole — MATLAB's numbering. */
        val states: IntArray,
        val s1Times: DoubleArray,
        val bpm: Double,
        val bpmCV: Double,
        val confidence: Double,
        /** Median duration of each state in ms, S1/systole/S2/diastole. */
        val stateMediansMs: DoubleArray,
        val elapsedMs: Long,
    ) {
        val frameTimes: DoubleArray get() = DoubleArray(frameCentres.size) { frameCentres[it] / TARGET_FS.toDouble() }

        /** Human-readable dump for the Hardware diagnostics screen or logcat. */
        fun describe(): String = buildString {
            appendLine("=== HEART SOUND SEGMENTATION ===")
            appendLine("Frames: ${frameCentres.size}   computed in $elapsedMs ms")
            appendLine("Signal quality (mean top probability): %.3f".format(confidence))
            appendLine("S1 events: ${s1Times.size}")
            appendLine("Heart rate: %.1f bpm   variability (CV): %.3f".format(bpm, bpmCV))
            appendLine(
                "Median S1 / systole / S2 / diastole: %.0f / %.0f / %.0f / %.0f ms".format(
                    stateMediansMs[0], stateMediansMs[1], stateMediansMs[2], stateMediansMs[3],
                ),
            )
            appendLine("S1 times (s): " + s1Times.joinToString(" ") { "%.3f".format(it) })
        }
    }

    /** The most recent run, for the Hardware diagnostics screen. */
    @Volatile
    var lastRun: Segmentation? = null
        private set

    override suspend fun classify(signal: HeartSignal): HeartSoundResult =
        withContext(Dispatchers.Default) {
            // Any scaling is fine — the percentile normalisation removes it — but the
            // MATLAB reference reads audioread's -1..1, so keep that for comparability.
            val audio = DoubleArray(signal.samples.size) { signal.samples[it] / 32768.0 }
            if (resampledLength(audio.size, signal.sampleRate) < MIN_SAMPLES) {
                return@withContext HeartSoundResult(
                    quality = SignalQuality.POOR,
                    rhythm = HeartRhythm.UNKNOWN,
                    heartRateBpm = null,
                    beatCount = 0,
                    confidence = 0f,
                    bpmVariability = null,
                    murmur = murmur?.let { MurmurFinding(MurmurOutcome.UNABLE, null) },
                    note = "Recording too short to analyse.",
                )
            }
            val s = segment(audio, signal.sampleRate).also { lastRun = it }
            val result = toResult(s)
            val clf = murmur ?: return@withContext result
            result.copy(murmur = murmurFinding(clf, s, result.quality))
        }

    /** Runs all four stages on mono [audio] at [sampleRate] and returns every intermediate. */
    suspend fun segment(audio: DoubleArray, sampleRate: Int): Segmentation = coroutineScope {
        val started = System.nanoTime()

        // ---- Stage 1: preprocessing ------------------------------------------------
        val pre = preprocess(audio, sampleRate)
        val x = pre.normalised

        // ---- Stage 2: sliding window -----------------------------------------------
        val net = model
        val win = net.inputLength
        val half = win / 2
        val n = x.size
        require(n > half + 1) { "Recording too short: $n samples at 2 kHz, need more than ${half + 1}" }

        // Reflect-pad: MATLAB [flipud(x(2:half+1)); x; flipud(x(N-half:N-1))].
        val xp = FloatArray(n + 2 * half)
        for (i in 0 until half) xp[i] = x[half - i].toFloat()
        for (i in 0 until n) xp[half + i] = x[i].toFloat()
        for (i in 0 until half) xp[half + n + i] = x[n - 2 - i].toFloat()

        // MATLAB centres = 1:HOP:N (1-based); window for centre c is xp(c+1 : c+WIN),
        // which is 0-based xp[c until c+WIN].
        val centres = IntArray((n - 1) / hop + 1) { 1 + it * hop }
        val nF = centres.size
        val post = Array(nF) { DoubleArray(net.classes.size) }

        // Frames are independent, so spread them over the cores. Each worker gets its own
        // scratch buffers; the model's weights are only read.
        val workers = max(1, min(Runtime.getRuntime().availableProcessors(), nF / 16))
        val chunk = (nF + workers - 1) / workers
        (0 until workers).map { w ->
            async(Dispatchers.Default) {
                val ws = net.Workspace()
                val out = FloatArray(net.classes.size)
                for (i in w * chunk until min(nF, (w + 1) * chunk)) {
                    net.forward(xp, centres[i], ws, out)
                    for (k in out.indices) post[i][k] = out[k].toDouble()
                }
            }
        }.awaitAll()

        // ---- Stage 3: Viterbi ------------------------------------------------------
        val states = decodeViterbi(post, P_STAY)

        // ---- Stage 4: events and heart rate ----------------------------------------
        val s1Frames = runPeaks(states, post, code = 1, minRun = MIN_RUN)
        // c/Fs rather than (c-1)/Fs, matching the pipeline the team published.
        val s1Times = DoubleArray(s1Frames.size) { centres[s1Frames[it]] / TARGET_FS.toDouble() }

        var confSum = 0.0
        for (row in post) confSum += row.max()
        val confidence = confSum / nF

        var bpm = Double.NaN
        var bpmCV = Double.NaN
        if (s1Times.size >= 2) {
            val rr = DoubleArray(s1Times.size - 1) { s1Times[it + 1] - s1Times[it] }
            val mean = rr.average()
            bpm = 60.0 / mean
            // MATLAB std: sample standard deviation (N-1). One interval has no spread.
            bpmCV = if (rr.size >= 2) {
                sqrt(rr.sumOf { (it - mean) * (it - mean) } / (rr.size - 1)) / mean
            } else 0.0
        }

        val medians = DoubleArray(4) { s ->
            val d = runLengths(states, s + 1).map { it * hop * 1000.0 / TARGET_FS }
            if (d.isEmpty()) 0.0 else median(d)
        }

        Segmentation(
            resampled = pre.resampled,
            afterBandpass = pre.filtered,
            percentile995 = pre.scale,
            afterNormalise = x,
            frameCentres = centres,
            posteriors = post,
            states = states,
            s1Times = s1Times,
            bpm = bpm,
            bpmCV = bpmCV,
            confidence = confidence,
            stateMediansMs = medians,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
        )
    }

    /** One 4096-sample window straight through the network — the README's `singleWindow` check. */
    fun forwardWindow(window: FloatArray): FloatArray {
        val out = FloatArray(model.classes.size)
        model.forward(window, 0, model.Workspace(), out)
        return out
    }

    /** Stage 1's output: the signal exactly as the network sees it, plus its intermediates. */
    class Preprocessed(
        val resampled: DoubleArray,
        val filtered: DoubleArray,
        val scale: Double,
        /** 2 kHz, 25–400 Hz, divided by [scale] and clipped to ±8. */
        val normalised: DoubleArray,
    )

    companion object {
        const val TARGET_FS = 2_000
        const val HOP = 32
        const val MIN_RUN = 2
        const val CLIP = 8.0

        /** Shortest 2 kHz input the reflect padding can handle (window/2 + 2). */
        const val MIN_SAMPLES = 2_050

        /**
         * Below this mean top probability the result is reported as a poor signal and no
         * rate or rhythm is shown. The team's README: ~0.95 on good recordings, and at 0.6
         * the segmentation "won't be trustworthy" even though it still looks tidy. 0.8 is
         * our choice between the two — **confirm it with the algorithm team.**
         */
        const val QUALITY_THRESHOLD = 0.80

        /** Beat-to-beat variability (CV of S1 intervals) above which rhythm is irregular — README: ">0.2 irregular". */
        const val IRREGULAR_CV = 0.20

        /** Fewest S1 events before rhythm is judged: three intervals, so one odd beat cannot decide it. */
        const val MIN_BEATS_FOR_RHYTHM = 4

        /** Self-transition probabilities, S1/systole/S2/diastole, from `spec.json`. */
        val P_STAY = doubleArrayOf(
            0.87520676717551393, 0.90598814857338916, 0.84404872668527053, 0.95654817754556642,
        )

        /** butter(4, [25 400]/1000, 'bandpass'), from `localButterCoeffs` in the MATLAB. */
        val BANDPASS_B = doubleArrayOf(
            0.0379730414194768, 0.0, -0.151892165677907, 0.0, 0.227838248516861,
            0.0, -0.151892165677907, 0.0, 0.0379730414194768,
        )
        val BANDPASS_A = doubleArrayOf(
            1.0, -4.70203268042376, 9.6538853799703, -11.6429927241205,
            9.30055661969788, -5.07209079812917, 1.79598268938372,
            -0.371013433549363, 0.0377338824211926,
        )

        fun resampledLength(n: Int, fs: Int): Long = n.toLong() * TARGET_FS / fs

        /**
         * Stage 1 alone — cheap (no network), so the Analysis screen and the PDF can plot
         * the exact signal that was segmented without re-running the CNN.
         */
        fun preprocess(audio: DoubleArray, sampleRate: Int): Preprocessed {
            // Resampling is not optional: the network learned absolute durations (S1 is
            // 256 samples at 2 kHz). The team's MATLAB uses resample(); this uses the
            // app's Kaiser-windowed resampler, which is equally anti-aliased. The test
            // vector is already at 2 kHz, so `SegmenterParityTest` checks this step
            // separately with a 16 kHz copy.
            val resampled = if (sampleRate == TARGET_FS) audio.copyOf()
            else Resampler.resample(audio, sampleRate, TARGET_FS)

            val filtered = filtfiltSimple(BANDPASS_B, BANDPASS_A, resampled)

            // 99.5th percentile, not the max, so a single knock on the bell cannot
            // rescale the whole recording.
            var scale = prctileSimple(DoubleArray(filtered.size) { kotlin.math.abs(filtered[it]) }, 99.5)
            if (!scale.isFinite() || scale <= 0) scale = filtered.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
            if (scale <= 0) scale = 1.0
            val x = DoubleArray(filtered.size) { (filtered[it] / scale).coerceIn(-CLIP, CLIP) }
            return Preprocessed(resampled, filtered, scale, x)
        }

        /** Same as [preprocess], from int16 PCM as stored. */
        fun preprocess(samples: ShortArray, sampleRate: Int): Preprocessed =
            preprocess(DoubleArray(samples.size) { samples[it] / 32768.0 }, sampleRate)

        /** Turns a segmentation into what the app stores and shows. */
        fun toResult(s: Segmentation): HeartSoundResult {
            val good = s.confidence >= QUALITY_THRESHOLD && s.s1Times.size >= 2
            val rhythm = when {
                !good || s.s1Times.size < MIN_BEATS_FOR_RHYTHM -> HeartRhythm.UNKNOWN
                s.bpmCV > IRREGULAR_CV -> HeartRhythm.IRREGULAR
                else -> HeartRhythm.REGULAR
            }
            return HeartSoundResult(
                quality = if (good) SignalQuality.GOOD else SignalQuality.POOR,
                rhythm = rhythm,
                heartRateBpm = s.bpm.takeIf { it.isFinite() }?.toFloat(),
                beatCount = s.s1Times.size,
                confidence = s.confidence.toFloat(),
                bpmVariability = s.bpmCV.takeIf { it.isFinite() }?.toFloat(),
                segmentation = HeartStates(
                    states = ByteArray(s.states.size) { s.states[it].toByte() },
                    hop = if (s.frameCentres.size > 1) s.frameCentres[1] - s.frameCentres[0] else HOP,
                    sampleRate = TARGET_FS,
                ),
            )
        }

        /**
         * Runs [clf] on what the segmenter saw and found. The outcome is UNABLE on a POOR
         * signal even when a score came out: the score is only as good as the beat
         * boundaries, and those are what POOR says not to trust. The score itself is kept
         * for the testing readout.
         */
        fun murmurFinding(clf: MurmurClassifier, s: Segmentation, quality: SignalQuality): MurmurFinding {
            val hop = if (s.frameCentres.size > 1) s.frameCentres[1] - s.frameCentres[0] else HOP
            val out = clf.classify(s.afterNormalise, TARGET_FS, MurmurClassifier.beatBoundaries(s.states, hop))
            val outcome = when {
                !out.ok || quality == SignalQuality.POOR -> MurmurOutcome.UNABLE
                out.isMurmur -> MurmurOutcome.DETECTED
                else -> MurmurOutcome.NOT_DETECTED
            }
            return MurmurFinding(outcome, out.score.takeIf { out.ok }?.toFloat())
        }

        /** MATLAB `filter(b, a, x)` (direct form II transposed, zero initial state). */
        fun filter(b: DoubleArray, a: DoubleArray, x: DoubleArray): DoubleArray {
            val order = b.size - 1
            val a0 = a[0]
            val bn = DoubleArray(b.size) { b[it] / a0 }
            val an = DoubleArray(a.size) { a[it] / a0 }
            val z = DoubleArray(order + 1)
            val y = DoubleArray(x.size)
            for (i in x.indices) {
                val xi = x[i]
                val yi = bn[0] * xi + z[0]
                for (k in 1..order) z[k - 1] = bn[k] * xi + z[k] - an[k] * yi
                y[i] = yi
            }
            return y
        }

        /**
         * Forward, reverse, forward, reverse — the MATLAB's `filtfiltSimple`, deliberately
         * without filtfilt's edge handling, so results match the reference exactly.
         */
        fun filtfiltSimple(b: DoubleArray, a: DoubleArray, x: DoubleArray): DoubleArray {
            val y = filter(b, a, x)
            y.reverse()
            val z = filter(b, a, y)
            z.reverse()
            return z
        }

        /** MATLAB's `prctile` convention, as in the reference's `prctileSimple`. */
        fun prctileSimple(x: DoubleArray, p: Double): Double {
            if (x.isEmpty()) return 0.0
            val s = x.sortedArray()
            val n = s.size
            val pos = p / 100.0 * n + 0.5 // 1-based
            return when {
                pos <= 1 -> s[0]
                pos >= n -> s[n - 1]
                else -> {
                    val lo = floor(pos).toInt()
                    val frac = pos - lo
                    s[lo - 1] * (1 - frac) + s[lo] * frac
                }
            }
        }

        /** Left-to-right cyclic Viterbi; returns 1-based states like the MATLAB. */
        fun decodeViterbi(post: Array<DoubleArray>, pStay: DoubleArray): IntArray {
            val nF = post.size
            val nS = pStay.size
            val logS = DoubleArray(nS) { ln(max(pStay[it], 1e-6)) }
            val logA = DoubleArray(nS) { ln(max(1 - pStay[it], 1e-6)) }
            // prevOf = [4 1 2 3] in 1-based terms.
            val prevOf = IntArray(nS) { (it + nS - 1) % nS }
            var delta = DoubleArray(nS) { ln(1.0 / nS) + ln(max(post[0][it], 1e-12)) }
            val psi = Array(nF) { IntArray(nS) }
            for (t in 1 until nF) {
                val next = DoubleArray(nS)
                for (s in 0 until nS) {
                    val stay = delta[s] + logS[s]
                    val p = prevOf[s]
                    val move = delta[p] + logA[p]
                    val e = ln(max(post[t][s], 1e-12))
                    if (stay >= move) { next[s] = stay + e; psi[t][s] = s }
                    else { next[s] = move + e; psi[t][s] = p }
                }
                delta = next
            }
            val path = IntArray(nF)
            var best = 0
            for (s in 1 until nS) if (delta[s] > delta[best]) best = s
            path[nF - 1] = best
            for (t in nF - 2 downTo 0) path[t] = psi[t + 1][path[t + 1]]
            for (t in 0 until nF) path[t] += 1
            return path
        }

        /** Each run of [code] lasting at least [minRun] frames → its most confident frame (0-based). */
        fun runPeaks(states: IntArray, post: Array<DoubleArray>, code: Int, minRun: Int): IntArray {
            val out = ArrayList<Int>()
            var i = 0
            while (i < states.size) {
                if (states[i] != code) { i++; continue }
                var j = i
                while (j + 1 < states.size && states[j + 1] == code) j++
                if (j - i + 1 >= minRun) {
                    var best = i
                    for (k in i + 1..j) if (post[k][code - 1] > post[best][code - 1]) best = k
                    out += best
                }
                i = j + 1
            }
            return out.toIntArray()
        }

        private fun runLengths(states: IntArray, code: Int): List<Int> {
            val out = ArrayList<Int>()
            var run = 0
            for (s in states) {
                if (s == code) run++ else if (run > 0) { out += run; run = 0 }
            }
            if (run > 0) out += run
            return out
        }

        private fun median(v: List<Double>): Double {
            val s = v.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }
    }
}
