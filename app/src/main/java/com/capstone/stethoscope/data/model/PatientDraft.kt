package com.capstone.stethoscope.data.model

/**
 * Light, optional patient details entered before recording. Carried through the
 * session and stored on the [Recording], then passed to the diagnostic algorithm.
 */
data class PatientDraft(
    val name: String = "",
    val id: String = "",
    val ageYears: Int? = null,
    val sex: String? = null,   // "Male" / "Female" / "Other" / null
    val notes: String = "",
    /**
     * Where on the chest the recording was taken.
     *
     * Unlike the rest of this class it is not filled in beforehand: the clinician picks
     * it on the Review screen, after the recording exists, because that is when they know
     * where they actually put the bell rather than where they meant to.
     */
    val site: AuscultationSite? = null,
    /** Free-text refinement of [site], e.g. "2 cm lateral, patient leaning forward". */
    val siteNote: String = "",
) {
    val isEmpty: Boolean
        get() = name.isBlank() && id.isBlank() && ageYears == null && sex == null &&
            notes.isBlank() && site == null && siteNote.isBlank()
}
