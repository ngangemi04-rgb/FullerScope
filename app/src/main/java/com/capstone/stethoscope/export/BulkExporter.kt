package com.capstone.stethoscope.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.capstone.stethoscope.analysis.RecordingAnalyzer
import com.capstone.stethoscope.analysis.murmur.MurmurModel
import com.capstone.stethoscope.data.RecordingRepository
import com.capstone.stethoscope.data.model.Recording
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Exports every recording at once, for a test session: into a new
 * `FullerScope_export_<date>_<time>` folder inside the folder the user picks, one WAV and
 * one PDF per recording plus `recordings.csv` indexing them.
 *
 * The CSV is the point. It carries the name field **exactly as typed** — filenames go
 * through [ExportNaming]'s cleanup, which drops `.` and recases words, so "gain 1.5"
 * cannot be recovered from a filename — and `tools/organize_export.py` sorts the export
 * into condition/replicate folders from it.
 *
 * Recordings not yet analysed (or analysed under an older murmur model) are analysed on
 * the way, so every row has results. That is the slow part: ~15 s per minute of audio on
 * a moto g7.
 *
 * Blocking: call it off the main thread.
 */
class BulkExporter(
    private val context: Context,
    private val repository: RecordingRepository,
    private val analyzer: RecordingAnalyzer,
    private val murmurModel: MurmurModel?,
) {

    /** Where the export is, for the progress UI. */
    data class Progress(val done: Int, val total: Int, val analyzing: Boolean)

    data class Summary(val folderName: String, val exported: Int, val failures: List<String>)

    suspend fun export(treeUri: Uri, onProgress: (Progress) -> Unit): Summary {
        val recordings = repository.all()
        val folderName = "FullerScope_export_" + STAMP.format(Date())
        val folder = createDocument(rootOf(treeUri), DocumentsContract.Document.MIME_TYPE_DIR, folderName)

        val rows = ArrayList<List<String>>()
        val failures = ArrayList<String>()
        for ((i, original) in recordings.withIndex()) {
            coroutineContext.ensureActive()
            var rec = original
            val stem = "%03d_%s".format(i + 1, ExportNaming.baseName(rec))
            var error = ""
            try {
                if (!analyzer.isComplete(rec)) {
                    onProgress(Progress(i, recordings.size, analyzing = true))
                    rec = analyzer.analyze(rec)
                }
                onProgress(Progress(i, recordings.size, analyzing = false))

                val wav = repository.decryptToCache(rec)
                try {
                    write(folder, "audio/wav", "$stem.wav") { out -> wav.inputStream().use { it.copyTo(out) } }
                } finally {
                    runCatching { wav.delete() }
                }
                val pcm = analyzer.rawPcm(rec)
                val pdf = ReportExporter.buildReportPdf(
                    context, rec, File(context.cacheDir, "bulk_report.pdf"), pcg = pcm,
                    pcgSampleRate = rec.sampleRate, segmentPlot = analyzer.plotFor(rec, pcm),
                    murmurModel = murmurModel,
                )
                try {
                    write(folder, "application/pdf", "$stem.pdf") { out -> pdf.inputStream().use { it.copyTo(out) } }
                } finally {
                    runCatching { pdf.delete() }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                error = t.message ?: t.javaClass.simpleName
                failures += "${rec.patientName ?: rec.label}: $error"
            }
            rows += row(i + 1, stem, rec, error)
        }

        // Last, so a CSV that exists describes files that exist.
        write(folder, "text/csv", "recordings.csv") { out ->
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // BOM: Excel reads UTF-8
            val text = (listOf(HEADER) + rows).joinToString("\r\n") { r -> r.joinToString(",") { csv(it) } }
            out.write(text.toByteArray(Charsets.UTF_8))
        }
        onProgress(Progress(recordings.size, recordings.size, analyzing = false))
        return Summary(folderName, recordings.size - failures.size, failures)
    }

    private fun row(index: Int, stem: String, r: Recording, error: String): List<String> = listOf(
        index.toString(),
        r.patientName.orEmpty(),
        r.patientId.orEmpty(),
        r.patientNotes.orEmpty(),
        ISO.format(Date(r.createdAt)),
        "%.2f".format(Locale.US, r.durationMs / 1000.0),
        r.sampleRate.toString(),
        r.auscultationSite?.key.orEmpty(),
        r.siteNote.orEmpty(),
        r.patientSex.orEmpty(),
        r.patientAgeYears?.toString().orEmpty(),
        r.signalQuality?.name.orEmpty(),
        r.signalConfidence?.let { "%.3f".format(Locale.US, it) }.orEmpty(),
        r.heartRateBpm?.let { "%.1f".format(Locale.US, it) }.orEmpty(),
        r.rhythm?.name.orEmpty(),
        r.bpmVariability?.let { "%.3f".format(Locale.US, it) }.orEmpty(),
        r.beatCount?.toString().orEmpty(),
        r.murmurOutcome?.name.orEmpty(),
        r.murmurScore?.let { "%.4f".format(Locale.US, it) }.orEmpty(),
        murmurModel?.threshold?.let { "%.4f".format(Locale.US, it) }.orEmpty(),
        r.murmurDriver?.encode().orEmpty(),
        murmurModel?.name.orEmpty(),
        if (error.isEmpty()) "$stem.wav" else "",
        if (error.isEmpty()) "$stem.pdf" else "",
        error,
    )

    private fun rootOf(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun createDocument(parent: Uri, mime: String, name: String): Uri =
        DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
            ?: error("Could not create $name in the chosen folder")

    private fun write(folder: Uri, mime: String, name: String, block: (OutputStream) -> Unit) {
        val doc = createDocument(folder, mime, name)
        context.contentResolver.openOutputStream(doc)?.use(block)
            ?: error("Could not write $name")
    }

    companion object {
        val HEADER = listOf(
            "index", "name", "patient_id", "notes", "recorded_at", "duration_s", "sample_rate_hz",
            "site", "site_note", "sex", "age_years",
            "signal_quality", "signal_confidence", "heart_rate_bpm", "rhythm", "bpm_cv", "beats",
            "murmur_outcome", "murmur_score", "murmur_threshold", "murmur_driver", "murmur_model",
            "wav_file", "pdf_file", "error",
        )

        private val STAMP = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)

        /** RFC 4180 quoting: names with commas or quotes must survive into Excel. */
        internal fun csv(field: String): String =
            if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + field.replace("\"", "\"\"") + "\""
            } else {
                field
            }
    }
}
