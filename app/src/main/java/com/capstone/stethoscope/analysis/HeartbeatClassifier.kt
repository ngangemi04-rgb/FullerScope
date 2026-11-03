package com.capstone.stethoscope.analysis

import java.io.File

/**
 * ============================================================================
 *  HEART SOUND ANALYSIS CONTRACT
 * ============================================================================
 *
 * An analyser reads the recorded heart-sound signal and reports heart rate, rhythm
 * regularity and signal quality.
 *
 * The production implementation is
 * [com.capstone.stethoscope.analysis.segmenter.HeartSoundSegmenter], a port of the
 * algorithm team's `segmentHeartSound.m` (2026-10-19 export, in `algorithm/segmenter/`),
 * with their murmur classifier (`classifyMurmur.m`, 2026-10-28, `algorithm/murmur/`) run
 * on its output. [MockHeartbeatClassifier] remains for demos on a machine with no hardware. Swapping
 * between them is one line in di/AppContainer.kt.
 */
interface HeartbeatClassifier {
    suspend fun classify(signal: HeartSignal): HeartSoundResult
}

/**
 * The recorded heart-sound signal, **exactly as captured**.
 *
 * Unprocessed: the capture rate (normally **16 kHz** — see
 * [com.capstone.stethoscope.analysis.pcg.PcgSpec.CAPTURE_SAMPLE_RATE]), no resampling,
 * no filtering, no DC removal, no normalization. Everything a classifier needs to do to
 * the signal — including getting it to the rate its model was trained at — happens
 * inside the classifier — [com.capstone.stethoscope.analysis.segmenter.HeartSoundSegmenter]
 * resamples to 2 kHz itself, so replacing the algorithm replaces that too. See INTEGRATION.md.
 *
 * @property samples  PCM samples, mono 16-bit, at [sampleRate]. Divide by 32768 for the
 *                    -1..1 float scale MATLAB's `audioread` would return.
 * @property audioFile The same capture as a canonical 16-bit mono WAV, if reading the file
 *                    directly is preferred. Deleted after [HeartbeatClassifier.classify]
 *                    returns — copy it if you need it later.
 * @property sampleRate Samples per second of [samples] and [audioFile] — read it, do not
 *                    assume 16000: a recording from the phone mic fallback, or an older
 *                    install, can carry another rate.
 * @property durationMs Length of the recording in milliseconds.
 */
data class HeartSignal(
    val samples: ShortArray,
    val audioFile: File,
    val sampleRate: Int,
    val durationMs: Long,
)

/** Whether the segmenter was sure enough of the heart sounds for its numbers to mean anything. */
enum class SignalQuality { GOOD, POOR }

/**
 * Regularity of the beat-to-beat (S1-to-S1) intervals.
 *
 * [UNKNOWN] is a real outcome, not an error: a poor signal, or too few beats to judge.
 */
enum class HeartRhythm { REGULAR, IRREGULAR, UNKNOWN }

/**
 * The murmur classifier's answer.
 *
 * [UNABLE] is a third outcome, not a negative: too few usable beats, or a signal too poor
 * for the segmenter's beat boundaries to be trusted.
 */
enum class MurmurOutcome { DETECTED, NOT_DETECTED, UNABLE }

/**
 * @property score 0..1 from the logistic model; null when it could not be measured. Kept
 *                 on a POOR signal too (for testing) — [outcome] is UNABLE there.
 */
data class MurmurFinding(val outcome: MurmurOutcome, val score: Float?)

/**
 * What the analysis found.
 *
 * No normal/abnormal field: the segmenter locates heart sounds and the murmur
 * classifier ([murmur]) looks for one specific finding. Neither says "healthy".
 *
 * @property quality        [SignalQuality.POOR] means rate and rhythm are withheld —
 *                          the segmenter always emits a tidy-looking cycle, even on noise.
 * @property rhythm         from [bpmVariability]; UNKNOWN when the signal is poor or short.
 * @property heartRateBpm   from the mean S1-to-S1 interval; null with fewer than 2 beats.
 *                          Present on a POOR signal too (for testing) — check [quality].
 * @property beatCount      S1 events detected.
 * @property confidence     the segmenter's mean top probability, 0..1 (~0.95 on clean
 *                          recordings). This is a signal-quality score, not the
 *                          probability that any finding is correct.
 * @property bpmVariability coefficient of variation of the S1 intervals (~0.03 regular,
 *                          >0.2 irregular); null when the signal is poor.
 * @property segmentation   the phase of every frame, for the segmented plot; null from an
 *                          algorithm that does not segment (e.g. the mock).
 * @property murmur         the murmur classifier's result; null from an algorithm without one.
 * @property note           Optional free-text detail for the report (may be null).
 */
data class HeartSoundResult(
    val quality: SignalQuality,
    val rhythm: HeartRhythm,
    val heartRateBpm: Float?,
    val beatCount: Int,
    val confidence: Float,
    val bpmVariability: Float?,
    val segmentation: HeartStates? = null,
    val murmur: MurmurFinding? = null,
    val note: String? = null,
)

/** One phase of the cardiac cycle, numbered as the team's MATLAB numbers them. */
enum class HeartPhase(val code: Int) {
    S1(1), SYSTOLE(2), S2(3), DIASTOLE(4);

    companion object {
        fun of(code: Int): HeartPhase? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The segmenter's decoded phase for every frame: frame `i` is centred on 1-based sample
 * `1 + i*hop` at [sampleRate], i.e. at [timeOf] seconds — the MATLAB's `frameTimes`.
 *
 * Stored on the recording (see [encode]) so the segmented plot can be redrawn, and put in
 * a PDF, without re-running the network.
 */
class HeartStates(
    /** 1 = S1, 2 = systole, 3 = S2, 4 = diastole. */
    val states: ByteArray,
    val hop: Int,
    val sampleRate: Int,
) {
    val size: Int get() = states.size

    fun timeOf(frame: Int): Double = (1 + frame.toLong() * hop) / sampleRate.toDouble()

    /** The phase at [seconds], or null outside the recording. */
    fun phaseAt(seconds: Double): HeartPhase? {
        if (states.isEmpty()) return null
        val i = ((seconds * sampleRate - 1) / hop).let { kotlin.math.round(it).toInt() }
        if (i < 0 || i > states.lastIndex) return null
        return HeartPhase.of(states[i].toInt())
    }

    /**
     * Each maximal run of one phase as (phase, startSeconds, endSeconds). A frame is taken
     * to cover half a hop either side of its centre, so the runs tile the recording.
     */
    fun runs(): List<Triple<HeartPhase, Double, Double>> {
        val out = ArrayList<Triple<HeartPhase, Double, Double>>()
        var i = 0
        val halfHop = hop / 2.0 / sampleRate
        while (i < states.size) {
            var j = i
            while (j + 1 < states.size && states[j + 1] == states[i]) j++
            HeartPhase.of(states[i].toInt())?.let {
                out += Triple(it, (timeOf(i) - halfHop).coerceAtLeast(0.0), timeOf(j) + halfHop)
            }
            i = j + 1
        }
        return out
    }

    /** `"<sampleRate>/<hop>/<one digit per frame>"`, e.g. `"2000/32/444411112222..."`. */
    fun encode(): String = buildString(states.size + 12) {
        append(sampleRate).append('/').append(hop).append('/')
        for (b in states) append(('0' + b.toInt()))
    }

    companion object {
        /** Inverse of [encode]; null for anything malformed rather than a crash on open. */
        fun decode(s: String?): HeartStates? {
            if (s == null) return null
            val parts = s.split('/', limit = 3)
            if (parts.size != 3) return null
            val rate = parts[0].toIntOrNull() ?: return null
            val hop = parts[1].toIntOrNull() ?: return null
            val digits = parts[2]
            if (rate <= 0 || hop <= 0 || digits.any { it !in '1'..'4' }) return null
            return HeartStates(ByteArray(digits.length) { (digits[it] - '0').toByte() }, hop, rate)
        }
    }
}
