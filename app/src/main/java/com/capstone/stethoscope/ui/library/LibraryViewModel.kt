package com.capstone.stethoscope.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.capstone.stethoscope.StethoscopeApp
import com.capstone.stethoscope.data.RecordingRepository
import com.capstone.stethoscope.data.model.Recording
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val repository: RecordingRepository) : ViewModel() {

    val recordings: StateFlow<List<Recording>> =
        repository.recordings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun delete(recording: Recording) {
        viewModelScope.launch { repository.delete(recording) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as StethoscopeApp
                LibraryViewModel(app.container.repository)
            }
        }
    }
}
