package com.capstone.stethoscope.export

import com.capstone.stethoscope.data.model.Recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Filenames are the only thing identifying a recording once it lands in a WhatsApp
 * thread, so they are worth pinning down. A name that is technically unique but
 * illegible — the previous "rlaynaauster" — is a real failure, not a cosmetic one.
 */
class ExportNamingTest {

    /** Fixed local timestamp so the formatted stamp is deterministic. */
    private fun createdAt(): Long = Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply {
        clear()
        set(2026, Calendar.AUGUST, 3, 13, 16, 0)
    }.timeInMillis

    private fun rec(name: String? = null, id: String? = null, rowId: Long = 7) = Recording(
        id = rowId,
        filePath = "/x/y.wav.enc",
        createdAt = createdAt(),
        durationMs = 60_000,
        sampleRate = 8_000,
        patientName = name,
        patientId = id,
    )

    @Test
    fun `word boundaries survive as hyphens`() {
        val base = ExportNaming.baseName(rec(name = "r layna auster"))
        assertEquals("StethoScope_Heart_R-Layna-Auster_2026-08-03_1316", base)
    }

    @Test
    fun `does not squash a name into an illegible run`() {
        val base = ExportNaming.baseName(rec(name = "kwame mensah"))
        assertTrue(base.contains("Kwame-Mensah"))
        assertFalse("regression: name was squashed", base.contains("kwamemensah"))
    }

    @Test
    fun `already capitalised names are normalised, not doubled up`() {
        assertTrue(ExportNaming.baseName(rec(name = "KWAME MENSAH")).contains("Kwame-Mensah"))
        assertTrue(ExportNaming.baseName(rec(name = "Kwame Mensah")).contains("Kwame-Mensah"))
    }

    @Test
    fun `punctuation that is unsafe in filenames is removed`() {
        val base = ExportNaming.baseName(rec(name = "O'Brien, Mary-Jane (Mrs.)"))
        // No characters that would break FAT/exFAT or a shell.
        assertFalse(base.any { it in "/\\:*?\"<>|'(),." })
        assertTrue(base.contains("Obrien"))
        assertTrue(base.contains("Mary"))
    }

    @Test
    fun `falls back to patient id when no name was entered`() {
        val base = ExportNaming.baseName(rec(name = "  ", id = "GH-4471"))
        assertTrue(base.contains("Gh-4471"))
    }

    @Test
    fun `falls back to the row id when nothing was entered`() {
        val base = ExportNaming.baseName(rec(name = null, id = null, rowId = 42))
        assertTrue(base.contains("Recording-42"))
    }

    @Test
    fun `an over-long name is truncated without a dangling separator`() {
        val long = (1..30).joinToString(" ") { "Name$it" }
        val base = ExportNaming.baseName(rec(name = long))
        assertFalse("truncation left a trailing hyphen", base.contains("-_"))
    }

    @Test
    fun `name identifies both what it is and when`() {
        val base = ExportNaming.baseName(rec(name = "Ama Osei"))
        assertTrue(base.startsWith("StethoScope_Heart_"))
        assertTrue(base.contains("2026-08-03"))
        assertTrue(base.endsWith("1316"))
    }
}
