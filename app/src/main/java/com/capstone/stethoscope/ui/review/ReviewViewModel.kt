package com.capstone.stethoscope.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.capstone.stethoscope.StethoscopeApp
import com.capstone.stethoscope.audio.AudioPlayer
import com.capstone.stethoscope.data.RecordingRepository
import com.capstone.stethoscope.data.SessionStore
import com.capstone.stethoscope.data.model.AuscultationSite
import com.capstone.stethoscope.data.model.Recording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReviewViewModel(
    private val recordingId: Long,
    private val repository: RecordingRepository,
    private val player: AudioPlayer,
    private val session: SessionStore,
) : ViewModel() {

    private val _recording = MutableStateFlow<Recording?>(null)
    val recording: StateFlow<Recording?> = _recording.asStateFlow()

    val isPlaying: StateFlow<Boolean> = player.isPlaying

    /** Why the last play attempt failed, shown under the button; null when it worked. */
    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    /** Decrypted, transient copy used for playback only. */
    private var decryptedFile: File? = null

    init {
        viewModelScope.launch { _recording.value = repository.getById(recordingId) }
    }

    /**
     * Records where on the chest this was taken, and persists it immediately.
     *
     * Saved on each tap rather than behind a confirm button: the screen's two exits are
     * Retake (which deletes the recording anyway) and Analyze (which moves on), so an
     * unsaved edit would simply be lost. It also keeps [SessionStore] in step, so the
     * next recording in the same session starts from the same site — clinicians work
     * through the positions in order, and re-picking every time is the kind of friction
     * that makes people stop bothering.
     */
    fun setSite(site: AuscultationSite) {
        val rec = _recording.value ?: return
        val updated = rec.copy(auscultationSite = site)
        _recording.value = updated
        session.updatePatient(session.patient.value.copy(site = site))
        viewModelScope.launch { repository.update(updated) }
    }

    fun setSiteNote(note: String) {
        val rec = _recording.value ?: return
        val updated = rec.copy(siteNote = note.ifBlank { null })
        _recording.value = updated
        session.updatePatient(session.patient.value.copy(siteNote = note))
        // Debouncing is not worth it here: a Room update on a single row is cheap and
        // the alternative is losing the last few characters when the user taps Analyze.
        viewModelScope.launch { repository.update(updated) }
    }

    fun togglePlayback() {
        val rec = _recording.value ?: return
        if (isPlaying.value) {
            player.stop()
            return
        }
        viewModelScope.launch {
            // Decryption and the phone's media player can both fail; either used to be
            // an uncaught exception that crashed the app instead of a message.
            runCatching {
                val file = decryptedFile ?: withContext(Dispatchers.IO) {
                    repository.decryptToCache(rec)
                }.also { decryptedFile = it }
                player.toggle(file.absolutePath)
            }.onSuccess {
                _playbackError.value = null
            }.onFailure { t ->
                if (t is CancellationException) throw t
                player.stop()
                cleanupDecrypted() // a half-written copy would fail the same way again
                _playbackError.value = t.message ?: t.javaClass.simpleName
            }
        }
    }

    fun retake(onDone: () -> Unit) {
        player.stop()
        viewModelScope.launch {
            _recording.value?.let { repository.delete(it) }
            cleanupDecrypted()
            onDone()
        }
    }

    private fun cleanupDecrypted() {
        decryptedFile?.let { runCatching { it.delete() } }
        decryptedFile = null
    }

    override fun onCleared() {
        player.stop()
        cleanupDecrypted()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as StethoscopeApp
                val id = createSavedStateHandle().get<Long>("recordingId") ?: 0L
                ReviewViewModel(id, app.container.repository, app.container.player, app.container.session)
            }
        }
    }
}
