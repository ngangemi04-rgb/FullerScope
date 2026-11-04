package com.capstone.stethoscope.ui.result

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.capstone.stethoscope.StethoscopeApp
import com.capstone.stethoscope.analysis.HeartSignal
import com.capstone.stethoscope.analysis.HeartbeatClassifier
import com.capstone.stethoscope.analysis.segmenter.HeartSoundSegmenter
import com.capstone.stethoscope.analysis.segmenter.SegmentedSignal
import com.capstone.stethoscope.analysis.pcg.PcgPreprocessor
import com.capstone.stethoscope.data.RecordingRepository
import com.capstone.stethoscope.data.model.Recording
import com.capstone.stethoscope.analysis.murmur.MurmurModel
import com.capstone.stethoscope.export.ExportNaming
import com.capstone.stethoscope.export.ReportExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ResultViewModel(
    private val recordingId: Long,
    private val repository: RecordingRepository,
    private val classifier: HeartbeatClassifier,
    private val appContext: Context,
    /** Threshold and reported performance, shown beside the murmur result. */
    val murmurModel: MurmurModel? = null,
) : ViewModel() {

    enum class Phase { LOADING, ANALYZING, READY, ERROR }

    private val _phase = MutableStateFlow(Phase.LOADING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _recording = MutableStateFlow<Recording?>(null)
    val recording: StateFlow<Recording?> = _recording.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The segmented plot's data. Null until computed, or when the algorithm does not segment. */
    private val _plot = MutableStateFlow<SegmentedSignal?>(null)
    val plot: StateFlow<SegmentedSignal?> = _plot.asStateFlow()

    init {
        viewModelScope.launch {
            val rec = repository.getById(recordingId)
            if (rec == null) {
                _error.value = "Recording not found"
                _phase.value = Phase.ERROR
                return@launch
            }
            _recording.value = rec
            // Analysed before segmentations (2026-10-23) or murmur results (2026-10-31)
            // were stored: analyse again so there is something to show.
            if (rec.isAnalyzed && rec.segmentation != null && rec.murmurOutcome != null) {
                _plot.value = withContext(Dispatchers.Default) { plotFor(rec) }
                _phase.value = Phase.READY
            } else {
                analyze(rec)
            }
        }
    }

    fun retry() {
        _recording.value?.let { analyze(it) }
    }

    private fun analyze(rec: Recording) {
        _phase.value = Phase.ANALYZING
        _error.value = null
        viewModelScope.launch {
            try {
                // Hand the classifier the raw capture exactly as recorded: capture rate
                // (normally 16 kHz), no resampling, no filtering, no DC removal, no
                // normalization. Anything the algorithm needs done to the signal —
                // including decimating to the rate its model was trained at — is the
                // algorithm's job (moved there 2026-09-25), so replacing the algorithm
                // cannot leave a stale preprocessing step behind in the app.
                val result = withContext(Dispatchers.IO) {
                    val raw = repository.decryptToCache(rec)
                    try {
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
                _recording.value = updated
                _plot.value = withContext(Dispatchers.Default) { plotFor(updated) }
                _phase.value = Phase.READY
            } catch (t: Throwable) {
                _error.value = t.message ?: "Analysis failed"
                _phase.value = Phase.ERROR
            }
        }
    }

    /**
     * Re-derives the segmenter's input from the stored capture — cheap, no network — so
     * the plot is always of the same recording the stored phases came from. Null on any
     * failure: a missing plot should not cost the clinician the rest of the screen.
     */
    private fun plotFor(rec: Recording): SegmentedSignal? = runCatching {
        val states = rec.segmentation ?: return null
        val raw = pcgFor(rec) ?: return null
        val pre = HeartSoundSegmenter.preprocess(raw, rec.sampleRate)
        SegmentedSignal(pre.normalised, HeartSoundSegmenter.TARGET_FS, states)
    }.getOrNull()

    fun exportReport(destination: Uri, onResult: (Boolean) -> Unit) =
        runExport(onResult) { rec ->
            val pdf = ReportExporter.buildReportPdf(
                appContext, rec, pcg = pcgFor(rec), pcgSampleRate = rec.sampleRate,
                segmentPlot = _plot.value, murmurModel = murmurModel,
            )
            ReportExporter.copyToUri(appContext, pdf, destination)
        }

    /**
     * The exact signal the classifier was handed — the raw capture — for the report's trace.
     *
     * Regenerated from the stored capture rather than cached at analysis time, so the
     * picture on the report can never be of a different recording than the finding
     * printed above it.
     *
     * Returns null on failure rather than throwing: a report without a trace is still a
     * useful report, and losing the whole export because the waveform could not be drawn
     * would be a poor trade.
     */
    private fun pcgFor(rec: Recording): ShortArray? = runCatching {
        val raw = repository.decryptToCache(rec)
        try {
            readPcm16(raw)
        } finally {
            runCatching { raw.delete() }
        }
    }.getOrNull()

    /** 16-bit PCM out of a stored capture. Exact: the doubles were int16 to begin with. */
    private fun readPcm16(wav: File): ShortArray {
        val x = PcgPreprocessor.readPcm16AsDoubles(wav)
        return ShortArray(x.size) { x[it].toInt().toShort() }
    }

    fun exportAudio(destination: Uri, onResult: (Boolean) -> Unit) =
        runExport(onResult) { rec ->
            val wav = repository.decryptToCache(rec)
            try {
                ReportExporter.copyToUri(appContext, wav, destination)
            } finally {
                runCatching { wav.delete() }
            }
        }

    private fun runExport(onResult: (Boolean) -> Unit, block: (Recording) -> Unit) {
        val rec = _recording.value ?: return onResult(false)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { block(rec) }.isSuccess }
            onResult(ok)
        }
    }

    /**
     * Builds the PDF report and a decrypted WAV under clinician-readable names,
     * returning FileProvider Uris ready to attach.
     *
     * Note: shared files are necessarily plaintext — that is what exporting means —
     * but they are staged in a cache subdirectory that is wiped at the start of every
     * share, so one patient's audio is never left sitting next to the next one's.
     */
    fun prepareShare(onReady: (ArrayList<Uri>) -> Unit) {
        val rec = _recording.value ?: return
        viewModelScope.launch {
            val uris = withContext(Dispatchers.IO) {
                val authority = "${appContext.packageName}.fileprovider"
                val list = ArrayList<Uri>()
                // Each attachment is guarded separately, and a failure is reported rather
                // than swallowed. Wrapping both in one runCatching meant a WAV that failed
                // to decrypt would silently send a PDF-only share that looked successful.
                runCatching {
                    val dir = repository.shareDir()
                    val base = ExportNaming.baseName(rec)
                    // PDF first: it is the human-readable summary, so it should be the
                    // top attachment in the WhatsApp thread.
                    val pdf = ReportExporter.buildReportPdf(
                        appContext, rec, File(dir, "$base.pdf"), pcg = pcgFor(rec),
                        pcgSampleRate = rec.sampleRate, segmentPlot = _plot.value,
                        murmurModel = murmurModel,
                    )
                    list.add(FileProvider.getUriForFile(appContext, authority, pdf))
                    val wav = repository.decryptTo(rec, File(dir, "$base.wav"))
                    list.add(FileProvider.getUriForFile(appContext, authority, wav))
                }.onFailure { t ->
                    _error.value = "Only part of the report could be prepared: ${t.message}"
                }
                list
            }
            onReady(uris)
        }
    }

    // NOTE: there is deliberately no message-text helper here any more.
    //
    // Attaching EXTRA_TEXT to a file-sharing intent makes WhatsApp discard every
    // attachment and send only the text — so a summary caption produced a message that
    // claimed files were attached when none were. The summary lives in the PDF instead,
    // and the clinician types their own note. See WhatsAppSharer.share().

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as StethoscopeApp
                val id = createSavedStateHandle().get<Long>("recordingId") ?: 0L
                ResultViewModel(
                    id, app.container.repository, app.container.classifier, app,
                    runCatching { app.container.murmurModel }.getOrNull(),
                )
            }
        }
    }
}
