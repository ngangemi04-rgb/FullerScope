package com.capstone.stethoscope.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for a recording. Patient identifiers are stored as encrypted Base64
 * ciphertext (`*Enc` fields) — never plaintext at rest. The repository handles
 * encryption/decryption when mapping to/from the [com.capstone.stethoscope.data.model.Recording]
 * domain model.
 */
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Path to the encrypted WAV file (`.wav.enc`). */
    val filePath: String,
    val createdAt: Long,
    val durationMs: Long,
    val sampleRate: Int,

    // Encrypted patient identifiers.
    val patientNameEnc: String? = null,
    val patientIdEnc: String? = null,
    val patientNotesEnc: String? = null,
    /**
     * Free-text note about the recording position. Encrypted, because free text is where
     * identifying detail ends up whatever the field was nominally for.
     */
    val siteNoteEnc: String? = null,
    // Low-sensitivity, kept plaintext.
    val patientAgeYears: Int? = null,
    val patientSex: String? = null,
    /**
     * [com.capstone.stethoscope.data.model.AuscultationSite.key], or null for "not stated".
     *
     * Plaintext: a valve position identifies nobody, and keeping it readable means a
     * future query can group recordings by site without decrypting every row.
     */
    val auscultationSite: String? = null,

    // Analysis result. Enums stored by name: "GOOD"/"POOR", "REGULAR"/"IRREGULAR"/"UNKNOWN".
    val signalQuality: String? = null,
    val rhythm: String? = null,
    val heartRateBpm: Float? = null,
    val beatCount: Int? = null,
    val signalConfidence: Float? = null,
    val bpmVariability: Float? = null,
    /** [com.capstone.stethoscope.analysis.HeartStates.encode] — the segmentation, one digit per frame. */
    val segmentStates: String? = null,
    /** "DETECTED"/"NOT_DETECTED"/"UNABLE". */
    val murmurOutcome: String? = null,
    val murmurScore: Float? = null,
    val analyzedAt: Long? = null,
)
