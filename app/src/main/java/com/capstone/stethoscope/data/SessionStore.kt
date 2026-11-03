package com.capstone.stethoscope.data

import com.capstone.stethoscope.data.model.PatientDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory state shared across the linear Listen → Record flow. Currently holds
 * the patient draft entered on the Listen screen so it can be attached to the
 * recording that follows. Reset after a recording is saved.
 */
class SessionStore {

    private val _patient = MutableStateFlow(PatientDraft())
    val patient: StateFlow<PatientDraft> = _patient.asStateFlow()

    fun updatePatient(draft: PatientDraft) {
        _patient.value = draft
    }

    fun reset() {
        _patient.value = PatientDraft()
    }
}
