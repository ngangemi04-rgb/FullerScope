package com.capstone.stethoscope.export

import com.capstone.stethoscope.data.model.Recording
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the filenames used for exported/shared files.
 *
 * These names matter more than they look: once a file lands in a WhatsApp thread
 * it is identified by its filename alone, so `play_7.wav` is useless to the
 * clinician on the other end. We aim for `StethoScope_Heart_Kwame-Mensah_2026-07-27_14-32-05.wav`,
 * falling back to the patient ID and then the recording ID when no name was entered.
 */
object ExportNaming {

    /** Date and time to the second: sorts chronologically, and two clips of one patient a minute apart no longer share a name. */
    private val STAMP = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /**
     * Filename stem (no extension) for [recording], e.g.
     * `StethoScope_Heart_R-Layna-Auster_2026-08-03_13-16-42`.
     */
    fun baseName(recording: Recording): String {
        val who = sanitize(recording.patientName)
            ?: sanitize(recording.patientId)
            ?: "Recording-${recording.id}"
        return "StethoScope_Heart_${who}_${STAMP.format(Date(recording.createdAt))}"
    }

    /**
     * Makes a patient name safe for a filename **without making it unreadable**.
     *
     * Drops characters that are awkward on FAT/exFAT, then title-cases each word and joins
     * with hyphens, so word boundaries survive. Simply deleting whitespace — the previous
     * behaviour — turned "r layna auster" into "rlaynaauster", which is technically
     * identifying and practically illegible in a chat thread.
     *
     * Returns null when nothing usable is left, so the caller can fall back.
     */
    private fun sanitize(raw: String?): String? {
        val cleaned = raw?.trim()
            ?.replace(Regex("[^\\p{L}\\p{N}\\s_-]"), "")
            ?.split(Regex("[\\s_-]+"))
            ?.filter { it.isNotBlank() }
            ?.joinToString("-") { token ->
                token.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
            }
            ?.take(48)
            ?.trimEnd('-') // a truncated tail must not leave a dangling separator
        return cleaned?.takeIf { it.isNotBlank() }
    }
}
