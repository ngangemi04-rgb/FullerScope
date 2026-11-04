package com.capstone.stethoscope.data

import com.capstone.stethoscope.analysis.HeartRhythm
import com.capstone.stethoscope.analysis.HeartStates
import com.capstone.stethoscope.analysis.MurmurDriver
import com.capstone.stethoscope.analysis.MurmurOutcome
import com.capstone.stethoscope.analysis.SignalQuality
import com.capstone.stethoscope.data.db.RecordingDao
import com.capstone.stethoscope.data.db.RecordingEntity
import com.capstone.stethoscope.data.model.AuscultationSite
import com.capstone.stethoscope.data.model.Recording
import com.capstone.stethoscope.security.Crypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Single source of truth for recordings. Owns the Room rows and the WAV files on
 * disk, and enforces at-rest encryption:
 *  - patient identifiers are encrypted into the DB row ([Crypto.encryptString]),
 *  - WAV audio is stored encrypted (`.wav.enc`) and only decrypted to a temporary
 *    cache file when it needs to be played, analyzed, or exported.
 */
class RecordingRepository(
    private val dao: RecordingDao,
    /** Directory where encrypted WAV files live (created if missing). */
    private val recordingsDir: File,
    /** Scratch dir for transient decrypted files (app cache). */
    private val cacheDir: File,
) {
    init {
        if (!recordingsDir.exists()) recordingsDir.mkdirs()
    }

    val recordings: Flow<List<Recording>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getById(id: Long): Recording? = dao.getById(id)?.toDomain()

    /** Most recently created recording, or null when there are none. */
    suspend fun latest(): Recording? = dao.latest()?.toDomain()

    suspend fun insert(recording: Recording): Long = dao.insert(recording.toEntity())

    suspend fun update(recording: Recording) = dao.update(recording.toEntity())

    suspend fun delete(recording: Recording) {
        dao.getById(recording.id)?.let { dao.delete(it) }
        runCatching { File(recording.filePath).delete() }
    }

    // --- WAV file encryption helpers ---

    /** Path for a new encrypted recording. */
    fun newEncryptedFile(timestamp: Long): File =
        File(recordingsDir, "rec_$timestamp.wav.enc")

    /** Encrypt a freshly recorded plaintext WAV into storage, deleting the plaintext. */
    fun encryptRecording(plaintextWav: File, timestamp: Long): File {
        val dst = newEncryptedFile(timestamp)
        Crypto.encryptToFile(plaintextWav.readBytes(), dst)
        runCatching { plaintextWav.delete() }
        return dst
    }

    /** Decrypt a recording's WAV to a temporary cache file for playback/analysis/export. */
    fun decryptToCache(recording: Recording): File =
        decryptTo(recording, File(cacheDir, "play_${recording.id}.wav"))

    /**
     * Decrypt a recording's WAV to [outFile]. Sharing uses this to get a
     * clinician-readable filename, which is all the recipient sees in a WhatsApp
     * thread.
     */
    fun decryptTo(recording: Recording, outFile: File): File {
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(Crypto.decryptFile(File(recording.filePath)))
        return outFile
    }

    /**
     * Scratch dir for decrypted files staged for sharing. Cleared before each share
     * so stale plaintext copies of previous patients don't accumulate.
     */
    fun shareDir(): File = File(cacheDir, "share").apply {
        if (exists()) listFiles()?.forEach { it.delete() } else mkdirs()
    }

    // --- Mapping with field-level encryption ---

    private fun Recording.toEntity() = RecordingEntity(
        id = id,
        filePath = filePath,
        createdAt = createdAt,
        durationMs = durationMs,
        sampleRate = sampleRate,
        patientNameEnc = patientName?.let { Crypto.encryptString(it) },
        patientIdEnc = patientId?.let { Crypto.encryptString(it) },
        patientNotesEnc = patientNotes?.let { Crypto.encryptString(it) },
        siteNoteEnc = siteNote?.let { Crypto.encryptString(it) },
        patientAgeYears = patientAgeYears,
        patientSex = patientSex,
        auscultationSite = auscultationSite?.key,
        signalQuality = signalQuality?.name,
        rhythm = rhythm?.name,
        heartRateBpm = heartRateBpm,
        beatCount = beatCount,
        signalConfidence = signalConfidence,
        bpmVariability = bpmVariability,
        segmentStates = segmentation?.encode(),
        murmurOutcome = murmurOutcome?.name,
        murmurScore = murmurScore,
        murmurDriver = murmurDriver?.encode(),
        analyzedAt = analyzedAt,
    )

    private fun RecordingEntity.toDomain() = Recording(
        id = id,
        filePath = filePath,
        createdAt = createdAt,
        durationMs = durationMs,
        sampleRate = sampleRate,
        patientName = patientNameEnc?.let { Crypto.decryptString(it) },
        patientId = patientIdEnc?.let { Crypto.decryptString(it) },
        patientNotes = patientNotesEnc?.let { Crypto.decryptString(it) },
        siteNote = siteNoteEnc?.let { Crypto.decryptString(it) },
        patientAgeYears = patientAgeYears,
        patientSex = patientSex,
        auscultationSite = AuscultationSite.fromKey(auscultationSite),
        signalQuality = signalQuality?.let { runCatching { SignalQuality.valueOf(it) }.getOrNull() },
        rhythm = rhythm?.let { runCatching { HeartRhythm.valueOf(it) }.getOrNull() },
        heartRateBpm = heartRateBpm,
        beatCount = beatCount,
        signalConfidence = signalConfidence,
        bpmVariability = bpmVariability,
        segmentation = HeartStates.decode(segmentStates),
        murmurOutcome = murmurOutcome?.let { runCatching { MurmurOutcome.valueOf(it) }.getOrNull() },
        murmurScore = murmurScore,
        murmurDriver = MurmurDriver.decode(murmurDriver),
        analyzedAt = analyzedAt,
    )
}
