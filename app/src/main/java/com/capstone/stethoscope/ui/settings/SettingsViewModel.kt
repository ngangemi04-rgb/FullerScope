package com.capstone.stethoscope.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.capstone.stethoscope.StethoscopeApp
import com.capstone.stethoscope.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel(private val settings: AppSettings) : ViewModel() {

    private val _sampleRate = MutableStateFlow(settings.sampleRate)
    val sampleRate: StateFlow<Int> = _sampleRate.asStateFlow()

    private val _inputChannel = MutableStateFlow(settings.inputChannel)
    val inputChannel: StateFlow<AppSettings.InputChannel> = _inputChannel.asStateFlow()

    /**
     * Read straight off the settings' own flow rather than mirrored into a local one, so
     * the Listen screen's shortcut and this switch can never disagree about the value.
     */
    val testingMode: StateFlow<Boolean> = settings.testingModeFlow

    fun setSampleRate(value: Int) {
        settings.sampleRate = value
        _sampleRate.value = value
    }

    fun setInputChannel(value: AppSettings.InputChannel) {
        settings.inputChannel = value
        _inputChannel.value = value
    }

    fun setTestingMode(value: Boolean) {
        settings.testingMode = value
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as StethoscopeApp
                SettingsViewModel(app.container.settings)
            }
        }
    }
}
