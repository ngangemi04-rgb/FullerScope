package com.capstone.stethoscope.ui.listen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.capstone.stethoscope.StethoscopeApp
import com.capstone.stethoscope.audio.StethoscopeRecorder
import com.capstone.stethoscope.audio.usb.FirmwareManager
import com.capstone.stethoscope.audio.usb.UsbStethoscope
import com.capstone.stethoscope.data.model.PatientDraft
import com.capstone.stethoscope.data.model.Recording
import com.capstone.stethoscope.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class RecordState { IDLE, RECORDING, SAVING }

/**
 * Drives the single screen that handles listening *and* recording.
 *
 * These were two screens until recording was folded in: tapping Record used to navigate
 * away, which meant losing sight of the device-status banner and the patient details at
 * exactly the moment they matter. Now recording happens in place and only the timer
 * changes.
 *
 * One hard constraint shapes this: listening and recording both open a
 * [com.capstone.stethoscope.audio.PcmSource], and the USB stethoscope can only be claimed
 * once. Starting a recording therefore stops live monitoring first — they can never run
 * concurrently.
 */
class ListenViewModel(private val container: AppContainer) : ViewModel() {

    private val monitor = container.liveMonitor
    private val recorder = container.recorder

    val listening: StateFlow<Boolean> = monitor.active
    val patient: StateFlow<PatientDraft> = container.session.patient

    /**
     * State of the USB stethoscope — not AudioDeviceMonitor.usbConnected, which only sees
     * USB Audio Class devices and would report this CDC device as absent.
     */
    val stethoscope: StateFlow<UsbStethoscope.State> = container.usbStethoscope.state

    /** Firmware check/flash progress, surfaced so the screen can explain a busy device. */
    val firmware: StateFlow<FirmwareManager.State> = container.firmware.state

    /**
     * True while the firmware check or flash holds the stethoscope.
     *
     * Capture and the firmware check contend for the same single-claim CDC interface, so
     * rather than let a tap fail with "the stethoscope is busy", the buttons go quiet for
     * the second or so the check takes and say why.
     */
    val firmwareBusy: StateFlow<Boolean> = container.firmware.state
        .map { it is FirmwareManager.State.Checking || it is FirmwareManager.State.Installing }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _recordState = MutableStateFlow(RecordState.IDLE)
    val recordState: StateFlow<RecordState> = _recordState.asStateFlow()

    val elapsedMs: StateFlow<Long> = recorder.elapsedMs
    val maxDurationMs: Long get() = container.settings.recordingDurationMs

    /** Whichever waveform is live right now, so the screen does not have to choose. */
    val waveform: StateFlow<List<Float>> =
        combine(_recordState, monitor.waveform, recorder.waveform) { state, listen, record ->
            if (state == RecordState.IDLE) listen else record
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // Surface monitoring failures through the same channel as recording failures.
        viewModelScope.launch {
            monitor.error.collect { if (it != null) _error.value = it }
        }
    }

    fun consumeError() {
        _error.value = null
        monitor.consumeError()
    }

    /** True when capture will use the stethoscope rather than the phone's microphone. */
    val willUseStethoscope: Boolean
        get() = stethoscope.value is UsbStethoscope.State.Ready

    /** Whether to offer the engineering scope from this screen. */
    val testingMode: StateFlow<Boolean> = container.settings.testingModeFlow

    fun requestUsbPermission() = container.usbStethoscope.requestPermission()

    fun refreshUsb() = container.usbStethoscope.refresh()

    fun updatePatient(block: (PatientDraft) -> PatientDraft) {
        container.session.updatePatient(block(container.session.patient.value))
    }

    // --- listening ---

    fun startListening() {
        if (_recordState.value != RecordState.IDLE) return
        monitor.start(viewModelScope, container.settings.sampleRate)
    }

    fun stopListening() {
        monitor.stop()
    }

    // --- recording ---

    /**
     * Records for the configured duration, encrypts the result, stores it, and hands the
     * new recording's id to [onSaved]. Stops live monitoring first — and waits for it
     * to let go of the device, since the USB stethoscope can only be claimed once.
     */
    fun startRecording(onSaved: (Long) -> Unit) {
        if (_recordState.value != RecordState.IDLE) return
        _recordState.value = RecordState.RECORDING

        viewModelScope.launch {
            monitor.stopAndWait()
            val now = System.currentTimeMillis()
            // Record to a temporary plaintext file, then encrypt it into storage.
            val tempWav = File(container.cacheDir, "rec_temp_$now.wav")
            val sampleRate = container.settings.sampleRate

            when (val result = recorder.record(tempWav, sampleRate, maxDurationMs)) {
                is StethoscopeRecorder.Result.Success -> {
                    _recordState.value = RecordState.SAVING
                    val p = container.session.patient.value
                    val encrypted = withContext(Dispatchers.IO) {
                        container.repository.encryptRecording(tempWav, now)
                    }
                    val id = container.repository.insert(
                        Recording(
                            filePath = encrypted.absolutePath,
                            createdAt = now,
                            durationMs = result.durationMs,
                            sampleRate = result.sampleRate,
                            patientName = p.name.ifBlank { null },
                            patientId = p.id.ifBlank { null },
                            patientAgeYears = p.ageYears,
                            patientSex = p.sex,
                            patientNotes = p.notes.ifBlank { null },
                        )
                    )
                    _recordState.value = RecordState.IDLE
                    onSaved(id)
                }

                is StethoscopeRecorder.Result.Failure -> {
                    runCatching { tempWav.delete() }
                    _error.value = result.message
                    _recordState.value = RecordState.IDLE
                }
            }
        }
    }

    fun stopRecording() {
        recorder.requestStop()
    }

    /** Stops whatever is running. Used when the screen leaves or the app backgrounds. */
    fun stopAll() {
        monitor.stop()
        if (_recordState.value == RecordState.RECORDING) recorder.requestStop()
    }

    override fun onCleared() {
        stopAll()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as StethoscopeApp
                ListenViewModel(app.container)
            }
        }
    }
}
