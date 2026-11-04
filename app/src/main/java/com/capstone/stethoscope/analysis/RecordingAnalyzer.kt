package com.capstone.stethoscope.analysis

import com.capstone.stethoscope.analysis.pcg.PcgPreprocessor
import com.capstone.stethoscope.analysis.segmenter.HeartSoundSegmenter
import com.capstone.stethoscope.analysis.segmenter.SegmentedSignal
import com.capstone.stethoscope.data.RecordingRepository
import com.capstone.stethoscope.data.model.Recording
import java.io.File

/**
 * Runs the algorithm on a stored recording and keeps what it found. Shared by the
 * Analysis screen and the bulk export, so a recording analysed by either is analysed
 * identically.
 *
 * Blocking: call it off the main thread.
 */
class RecordingAnalyzer(
    private val repository: RecordingRepository,
    private val classifier: HeartbeatClassifier,
) {

    /** True when [rec] already has everything the Analysis screen and report show. */
    fun isComplete(rec: Recording): Boolean =
        rec.isAnalyzed && rec.segmentation != null && rec.murmurOutcome != null

    /**
     * Analyses [rec], saves the result, and returns the updated recording.
     *
     * The classifier gets the raw capture exactly as recorded: capture rate (normally
     * 16 kHz), no resampling, filtering, DC removal or normalization. Anything the
     * algorithm needs done to the signal — including decimating to the rate its model was
     * trained at — is the algorithm's job (moved there 2026-09-25), so replacing the
     * algorithm cannot leave a stale preprocessing step behind in the app.
     */
    suspend fun analyze(rec: Recording): Recording {
        val raw = repository.decryptToCache(rec)
        val result = try {
            val samples = readPcm16(raw)
            classifier.classify(
                HeartSignal(
                    samples = samples,
                    audioFile = raw,
                    sampleRate = rec.sampleRate,
                    durationMs = samples.size * 1000L / rec.sampleRate,
                )
            )
        } finally {
            runCatching { raw.delete() }
        }
        val updated = rec.copy(
            signalQuality = result.quality,
            rhythm = result.rhythm,
            heartRateBpm = result.heartRateBpm,
            beatCount = result.beatCount,
            signalConfidence = result.confidence,
            bpmVariability = result.bpmVariability,
            segmentation = result.segmentation,
            murmurOutcome = result.murmur?.outcome,
            murmurScore = result.murmur?.score,
            murmurDriver = result.murmur?.driver,
            analyzedAt = System.currentTimeMillis(),
        )
        repository.update(updated)
        return updated
    }

    /**
     * The exact signal the classifier was handed — the raw capture — for the report's trace.
     *
     * Regenerated from the stored capture rather than cached at analysis time, so the
     * picture on a report can never be of a different recording than the finding printed
     * above it. Null on failure: a report without a trace is still a useful report.
     */
    fun rawPcm(rec: Recording): ShortArray? = runCatching {
        val raw = repository.decryptToCache(rec)
        try {
            readPcm16(raw)
        } finally {
            runCatching { raw.delete() }
        }
    }.getOrNull()

    /**
     * Re-derives the segmenter's input from the stored capture — cheap, no CNN — so the
     * plot is always of the same recording the stored phases came from. Null on any
     * failure: a missing plot should not cost the rest of the screen or report.
     */
    fun plotFor(rec: Recording, raw: ShortArray? = rawPcm(rec)): SegmentedSignal? = runCatching {
        val states = rec.segmentation ?: return null
        val pcm = raw ?: return null
        val pre = HeartSoundSegmenter.preprocess(pcm, rec.sampleRate)
        SegmentedSignal(pre.normalised, HeartSoundSegmenter.TARGET_FS, states)
    }.getOrNull()

    /** 16-bit PCM out of a stored capture. Exact: the doubles were int16 to begin with. */
    private fun readPcm16(wav: File): ShortArray {
        val x = PcgPreprocessor.readPcm16AsDoubles(wav)
        return ShortArray(x.size) { x[it].toInt().toShort() }
    }
}
