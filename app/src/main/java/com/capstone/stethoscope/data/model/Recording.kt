package com.capstone.stethoscope.data.model

import com.capstone.stethoscope.analysis.HeartRhythm
import com.capstone.stethoscope.analysis.HeartStates
import com.capstone.stethoscope.analysis.MurmurDriver
import com.capstone.stethoscope.analysis.MurmurOutcome
import com.capstone.stethoscope.analysis.SignalQuality

/**
 * Plaintext domain model used throughout the UI. Persistence is handled by
 * [com.capstone.stethoscope.data.db.RecordingEntity], where patient fields are
 * stored encrypted; the repository maps between the two.
 *
 * [filePath] points to the encrypted WAV on disk (`.wav.enc`).
 */
data class Recording(
    val id: Long = 0,
    val filePath: String,
    /** Epoch millis when the recording was captured. */
    val createdAt: Long,
    val durationMs: Long,
    val sampleRate: Int,

    // Optional patient details (encrypted at rest).
    val patientName: String? = null,
    val patientId: String? = null,
    val patientAgeYears: Int? = null,
    val patientSex: String? = null,
    val patientNotes: String? = null,

    /**
     * Where on the chest this was recorded, chosen on the Review screen.
     *
     * Null means nobody said, which is a real answer and stays distinguishable from
     * "recorded at the apex" — a site nobody recorded is not the same as a site nobody
     * asked about, and a report should not imply otherwise.
     */
    val auscultationSite: AuscultationSite? = null,
    /** Free-text refinement of [auscultationSite] (encrypted at rest). */
    val siteNote: String? = null,

    // Analysis result (populated after analysis). See HeartSoundResult for meanings.
    val signalQuality: SignalQuality? = null,
    val rhythm: HeartRhythm? = null,
    /** Null when the signal was too poor to give one. */
    val heartRateBpm: Float? = null,
    /** S1 events the segmenter detected. */
    val beatCount: Int? = null,
    /** Segmenter's mean top probability, 0..1 — a signal-quality score. */
    val signalConfidence: Float? = null,
    /** Coefficient of variation of the S1-to-S1 intervals. */
    val bpmVariability: Float? = null,
    /** Phase of every frame, for the segmented plot. */
    val segmentation: HeartStates? = null,
    /** Null on recordings analysed before the murmur classifier existed. */
    val murmurOutcome: MurmurOutcome? = null,
    /** Murmur model score, 0..1; null when it could not be measured. */
    val murmurScore: Float? = null,
    /** The feature that mainly decided [murmurOutcome]; null when UNABLE or not analysed. */
    val murmurDriver: MurmurDriver? = null,
    val analyzedAt: Long? = null,
) {
    val isAnalyzed: Boolean get() = analyzedAt != null

    /** Display label (date/time). No patient name — that's shown separately, decrypted. */
    val label: String
        get() = android.text.format.DateFormat.format("MMM d, HH:mm", createdAt).toString()
}
