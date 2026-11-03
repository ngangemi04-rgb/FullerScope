package com.capstone.stethoscope.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.capstone.stethoscope.analysis.HeartPhase
import com.capstone.stethoscope.analysis.MurmurOutcome
import com.capstone.stethoscope.analysis.segmenter.SegmentedSignal
import com.capstone.stethoscope.data.model.AuscultationSite
import com.capstone.stethoscope.data.model.Recording
import com.capstone.stethoscope.ui.components.CHEST_DOT_RADIUS
import com.capstone.stethoscope.ui.components.PHASE_ARGB
import com.capstone.stethoscope.ui.components.PHASE_BAND_ALPHA
import com.capstone.stethoscope.ui.components.chestArt
import com.capstone.stethoscope.ui.components.siteCentre
import com.capstone.stethoscope.ui.components.reportLabel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a one-page PDF analysis report and copies files to a user-chosen
 * location (via the Storage Access Framework Uri passed from the UI).
 */
object ReportExporter {

    /**
     * Renders a PDF report for [recording] and returns the file. Writes to the cache dir
     * by default; pass [outFile] to control the name (sharing uses a clinician-readable
     * one — see [ExportNaming]).
     *
     * [pcg] is the phonocardiogram the classifier was given — the raw capture, mono int16
     * at [pcgSampleRate] (normally 16 kHz), unprocessed. When present it is
     * drawn as a trace on the report. That matters more than it looks: the finding is one
     * word, and a doctor receiving it over WhatsApp has no other way to judge whether the
     * recording behind that word was a clean auscultation or thirty seconds of a shirt
     * rubbing the bell. Pass null and the panel is simply omitted.
     */
    fun buildReportPdf(
        context: Context,
        recording: Recording,
        outFile: File? = null,
        pcg: ShortArray? = null,
        pcgSampleRate: Int,
        segmentPlot: SegmentedSignal? = null,
    ): File {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 @ 72dpi
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas

        val title = Paint().apply { textSize = 22f; isFakeBoldText = true }
        val heading = Paint().apply { textSize = 14f; isFakeBoldText = true }
        val body = Paint().apply { textSize = 12f }

        val left = 40f
        var y = 60f
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        canvas.drawText("StethoScope — Auscultation Report", left, y, title)
        y += 34f

        // Patient block (only what was entered).
        val hasPatient = !recording.patientName.isNullOrBlank() ||
            !recording.patientId.isNullOrBlank() ||
            recording.patientAgeYears != null ||
            !recording.patientSex.isNullOrBlank() ||
            recording.auscultationSite != null ||
            !recording.siteNote.isNullOrBlank()
        if (hasPatient) {
            canvas.drawText("Patient", left, y, heading); y += 20f
            recording.patientName?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText("Name: $it", left, y, body); y += 16f
            }
            recording.patientId?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText("ID: $it", left, y, body); y += 16f
            }
            val ageSex = listOfNotNull(
                recording.patientAgeYears?.let { "Age: $it" },
                recording.patientSex?.takeIf { it.isNotBlank() }?.let { "Sex: $it" },
            ).joinToString("   ")
            if (ageSex.isNotBlank()) { canvas.drawText(ageSex, left, y, body); y += 16f }
            recording.patientNotes?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText("Notes: $it", left, y, body); y += 16f
            }
            // Where the bell was. Spelled out rather than abbreviated, because the same
            // heart sounds different at each position and a reader who cannot tell
            // "Mitral" from "Pulmonic" cannot judge the trace below.
            recording.auscultationSite?.let {
                canvas.drawText("Auscultation site: ${it.reportLabel()}", left, y, body); y += 16f
            }
            recording.siteNote?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText("Site note: $it", left, y, body); y += 16f
            }
            // The same chest the clinician tapped, with their choice marked. A reader
            // skimming a report takes in a marked diagram faster than they parse
            // "4th ICS, lower left sternal border", and the two cannot disagree —
            // both come from AuscultationSite's own coordinates.
            recording.auscultationSite?.let {
                y = drawChestDiagram(context, canvas, it, recording.patientSex, left + 6f, y + 4f, width = 180f)
            }
            y += 14f
        }

        canvas.drawText("Recording type: Heart", left, y, body); y += 18f
        canvas.drawText("Recorded: ${dateFmt.format(Date(recording.createdAt))}", left, y, body); y += 18f
        canvas.drawText(
            "Duration: ${recording.durationMs / 1000}s   Sample rate: ${recording.sampleRate} Hz",
            left, y, body,
        ); y += 30f

        // Segmentation only, for now — matching the Analysis screen. Heart rate, rhythm and
        // signal quality are still stored on the recording but not printed.
        if (segmentPlot != null) {
            canvas.drawText("Heart sound segmentation", left, y, heading); y += 20f
            val d = segmentPlot.durationSec
            // Whole recording first, then the opening seconds large enough to see each
            // phase: at 60 s across 515 pt a 100 ms systole is under a point wide.
            y = drawSegmented(canvas, segmentPlot, 0.0, d, left, y, 515f, 95f, "Whole recording")
            y += 14f
            val detail = minOf(d, 5.0)
            y = drawSegmented(
                canvas, segmentPlot, 0.0, detail, left, y, 515f, 120f,
                "First %.0f s".format(detail),
            )
            y += 12f
            y = drawPhaseLegend(canvas, left, y)
            recording.murmurOutcome?.let {
                y += 26f
                canvas.drawText("Murmur screen: ${murmurLabel(it)}", left, y, heading); y += 4f
            }
        } else if (pcg != null && pcg.isNotEmpty()) {
            // Not analysed yet: the plain trace is still worth having.
            // 20f, not 8f: drawPcg prints its caption just above the plot, and at 8f that
            // caption collided with this heading.
            canvas.drawText("Phonocardiogram (not yet analyzed)", left, y, heading); y += 20f
            y = drawPcg(canvas, pcg, pcgSampleRate, left, y, width = 515f, height = 130f)
        } else {
            canvas.drawText("Not yet analyzed.", left, y, body); y += 18f
        }

        y += 24f
        val fine = Paint().apply { textSize = 9f }
        canvas.drawText(
            "The segmenter locates S1, systole, S2 and diastole (S1 detection F1 0.95 on 862 held-out patients).",
            left, y, fine,
        ); y += 12f
        canvas.drawText(
            "The murmur screen is a development model: on unseen patients it caught 57% of murmurs and cleared",
            left, y, fine,
        ); y += 12f
        canvas.drawText(
            "69% of recordings without one. \"No murmur detected\" does not rule a murmur out.",
            left, y, fine,
        ); y += 12f
        canvas.drawText("Generated by StethoScope.", left, y, fine)

        doc.finishPage(page)
        val out = outFile ?: File(context.cacheDir, "report_${recording.id}.pdf")
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }

    /** English, like the rest of the report. */
    private fun murmurLabel(outcome: MurmurOutcome) = when (outcome) {
        MurmurOutcome.DETECTED -> "possible murmur detected"
        MurmurOutcome.NOT_DETECTED -> "no murmur detected"
        MurmurOutcome.UNABLE -> "unable to analyze (too few clear heartbeats)"
    }

    /**
     * Draws a small chest with the recorded site marked, and returns the y to continue
     * from.
     *
     * The same artwork as the on-screen picker (male or female, per [chestArt]), with the marker
     * placed by [siteCentre] from [AuscultationSite.x]/[AuscultationSite.y] — the constants
     * `ui/components/ChestDiagram.kt` draws and hit-tests against. So the ring on this
     * report circles the same dot the clinician tapped, by construction. All five dots
     * stay visible: "here, and not at the other four" is the useful statement when the
     * reader is judging whether the trace below looks right for that position.
     */
    private fun drawChestDiagram(
        context: Context,
        canvas: android.graphics.Canvas,
        site: AuscultationSite,
        patientSex: String?,
        left: Float,
        top: Float,
        width: Float,
    ): Float {
        val art = chestArt(patientSex)
        val w = width
        val h = width / art.aspect

        val image = BitmapFactory.decodeResource(context.resources, art.res)
        canvas.drawBitmap(
            image,
            null,
            RectF(left, top, left + w, top + h),
            Paint().apply { isFilterBitmap = true; isAntiAlias = true },
        )
        image.recycle()

        val centre = siteCentre(site, w)
        val cx = left + centre.x
        val cy = top + centre.y
        val r = w * CHEST_DOT_RADIUS
        canvas.drawCircle(cx, cy, r * 1.6f, Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = r * 0.7f
            color = 0xFFFFFFFF.toInt()
        })
        canvas.drawCircle(cx, cy, r * 1.6f, Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = r * 0.4f
            color = 0xFF1B5E20.toInt()
        })
        val caption = Paint().apply { isAntiAlias = true; textSize = 7.5f; color = 0xFF424242.toInt() }

        // "Patient's right" spelled out, because a front-facing diagram mirrors the
        // patient and a reader who assumes otherwise reads every site backwards.
        canvas.drawText("patient's right", left, top + h + 9f, caption)
        return top + h + 14f
    }

    /**
     * Draws the phonocardiogram trace and returns the y to continue from.
     *
     * Column-wise min/max rather than a polyline through sampled points. A 30-second
     * recording at 2 kHz is 60 000 samples in about 500 points of page width, so a
     * polyline would have to skip 120 samples for every one it drew — and S1 is a
     * transient a few milliseconds wide, exactly the thing that falls between those
     * skips. Min/max per column cannot miss it: whatever the loudest sample in that
     * column was, the ink reaches it.
     *
     * The vertical scale is the clip's own peak, printed beside the axis. It has to be:
     * the app no longer normalizes, so amplitude depends on the preamp and the chest,
     * and a fixed full-scale axis would render most real recordings as a flat line.
     */
    /**
     * The segmenter's input between [t0] and [t1] seconds, each stretch shaded by its
     * phase — the PDF twin of the on-screen SegmentedPlot, same colours. Returns the y to
     * continue from.
     */
    private fun drawSegmented(
        canvas: android.graphics.Canvas,
        plot: SegmentedSignal,
        t0: Double,
        t1: Double,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        caption: String,
    ): Float {
        val axisText = Paint().apply { textSize = 8f; color = 0xFF616161.toInt() }
        val frame = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 0.6f; color = 0xFF9E9E9E.toInt()
        }
        val trace = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 0.6f; color = 0xFF263238.toInt()
        }
        canvas.drawText(caption, left, top, axisText)
        val plotTop = top + 4f
        val plotBottom = plotTop + height
        val span = (t1 - t0).takeIf { it > 0 } ?: 1.0
        fun px(t: Double) = left + ((t - t0) / span * width).toFloat()

        val band = Paint().apply { style = Paint.Style.FILL }
        for ((phase, start, end) in plot.states.runs()) {
            if (end < t0 || start > t1) continue
            band.color = PHASE_ARGB.getValue(phase)
            band.alpha = (PHASE_BAND_ALPHA * 255).toInt()
            canvas.drawRect(px(maxOf(start, t0)), plotTop, maxOf(px(minOf(end, t1)), px(maxOf(start, t0)) + 0.3f), plotBottom, band)
        }

        val sig = plot.signal
        val rate = plot.sampleRate
        val i0 = (t0 * rate).toInt().coerceIn(0, sig.size)
        val i1 = (t1 * rate).toInt().coerceIn(i0, sig.size)
        var peak = 1e-9
        for (i in i0 until i1) peak = maxOf(peak, kotlin.math.abs(sig[i]))
        val mid = (plotTop + plotBottom) / 2f
        val scale = (height / 2f - 2f) / peak
        val columns = width.toInt()
        val perColumn = (i1 - i0).toDouble() / columns
        val lines = FloatArray(columns * 4)
        var n = 0
        for (c in 0 until columns) {
            val from = i0 + (c * perColumn).toInt()
            if (from >= i1) break
            val to = minOf(i0 + ((c + 1) * perColumn).toInt(), i1).coerceAtLeast(from + 1)
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (i in from until to) {
                if (sig[i] < lo) lo = sig[i]
                if (sig[i] > hi) hi = sig[i]
            }
            val x = left + c + 0.5f
            lines[n++] = x; lines[n++] = mid - (hi * scale).toFloat()
            lines[n++] = x; lines[n++] = mid - (lo * scale).toFloat() + 0.3f
        }
        canvas.drawLines(lines, 0, n, trace)
        canvas.drawRect(left, plotTop, left + width, plotBottom, frame)

        canvas.drawText("%.1f s".format(t0), left, plotBottom + 10f, axisText)
        val endLabel = "%.1f s".format(t1)
        canvas.drawText(endLabel, left + width - axisText.measureText(endLabel), plotBottom + 10f, axisText)
        return plotBottom + 14f
    }

    /** Colour key for [drawSegmented]. Returns the y to continue from. */
    private fun drawPhaseLegend(canvas: android.graphics.Canvas, left: Float, top: Float): Float {
        val text = Paint().apply { textSize = 9f; color = 0xFF212121.toInt() }
        val swatch = Paint().apply { style = Paint.Style.FILL }
        var x = left
        for ((phase, name) in listOf(
            HeartPhase.S1 to "S1", HeartPhase.SYSTOLE to "Systole",
            HeartPhase.S2 to "S2", HeartPhase.DIASTOLE to "Diastole",
        )) {
            swatch.color = PHASE_ARGB.getValue(phase)
            canvas.drawRect(x, top, x + 9f, top + 9f, swatch)
            canvas.drawText(name, x + 13f, top + 8f, text)
            x += 13f + text.measureText(name) + 18f
        }
        return top + 12f
    }

    private fun drawPcg(
        canvas: android.graphics.Canvas,
        samples: ShortArray,
        sampleRate: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ): Float {
        val frame = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.6f
            color = 0xFF9E9E9E.toInt()
        }
        val trace = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.7f
            color = 0xFF1B5E20.toInt()
        }
        val axisText = Paint().apply { textSize = 8f; color = 0xFF616161.toInt() }

        val plotTop = top + 4f
        val plotBottom = plotTop + height
        val mid = (plotTop + plotBottom) / 2f
        canvas.drawRect(left, plotTop, left + width, plotBottom, frame)
        canvas.drawLine(left, mid, left + width, mid, frame)

        var peak = 1
        for (s in samples) {
            val m = if (s.toInt() == Short.MIN_VALUE.toInt()) 32767 else kotlin.math.abs(s.toInt())
            if (m > peak) peak = m
        }
        val scale = (height / 2f - 2f) / peak

        val columns = width.toInt()
        val perColumn = samples.size.toDouble() / columns
        val lines = FloatArray(columns * 4)
        var i = 0
        for (c in 0 until columns) {
            val from = (c * perColumn).toInt()
            val to = minOf(((c + 1) * perColumn).toInt().coerceAtLeast(from + 1), samples.size)
            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            for (k in from until to) {
                val v = samples[k].toInt()
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            if (lo > hi) continue
            val x = left + c
            lines[i++] = x
            lines[i++] = mid - hi * scale
            lines[i++] = x
            lines[i++] = mid - lo * scale
        }
        canvas.drawLines(lines, 0, i, trace)

        val seconds = samples.size.toDouble() / sampleRate
        canvas.drawText("0 s", left + 1f, plotBottom + 10f, axisText)
        canvas.drawText(
            String.format(Locale.US, "%.1f s", seconds),
            left + width - 24f, plotBottom + 10f, axisText,
        )
        canvas.drawText(
            "peak ±$peak counts · $sampleRate Hz · unfiltered, not normalized",
            left + 1f, plotTop - 3f, axisText,
        )
        return plotBottom + 14f
    }

    /** Copies [source] bytes into the [destination] Uri chosen by the user. */
    fun copyToUri(context: Context, source: File, destination: Uri) {
        context.contentResolver.openOutputStream(destination)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: error("Could not open destination for writing")
    }
}
