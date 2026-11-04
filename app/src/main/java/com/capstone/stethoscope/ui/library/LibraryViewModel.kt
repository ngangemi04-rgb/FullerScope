package com.capstone.stethoscope.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.capstone.stethoscope.StethoscopeApp
import com.capstone.stethoscope.data.RecordingRepository
import com.capstone.stethoscope.data.model.Recording
import com.capstone.stethoscope.export.BulkExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(
    private val repository: RecordingRepository,
    private val exporter: BulkExporter,
) : ViewModel() {

    val recordings: StateFlow<List<Recording>> =
        repository.recordings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** What the "Export all" dialog shows; null when no export is running or finished. */
    sealed interface ExportState {
        data class Running(val progress: BulkExporter.Progress) : ExportState
        data class Done(val summary: BulkExporter.Summary) : ExportState
        data class Failed(val message: String) : ExportState
    }

    private val _export = MutableStateFlow<ExportState?>(null)
    val export: StateFlow<ExportState?> = _export.asStateFlow()

    private var exportJob: Job? = null

    fun delete(recording: Recording) {
        viewModelScope.launch { repository.delete(recording) }
    }

    fun exportAll(treeUri: Uri) {
        if (exportJob?.isActive == true) return
        _export.value = ExportState.Running(BulkExporter.Progress(0, recordings.value.size, analyzing = false))
        exportJob = viewModelScope.launch {
            _export.value = try {
                val summary = withContext(Dispatchers.IO) {
                    exporter.export(treeUri) { _export.value = ExportState.Running(it) }
                }
                ExportState.Done(summary)
            } catch (e: CancellationException) {
                null
            } catch (t: Throwable) {
                ExportState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** Stops after the recording in progress; what was written so far stays, minus the CSV. */
    fun cancelExport() {
        exportJob?.cancel()
        _export.value = null
    }

    fun dismissExport() {
        _export.value = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as StethoscopeApp
                val c = app.container
                LibraryViewModel(c.repository, c.bulkExporter)
            }
        }
    }
}
