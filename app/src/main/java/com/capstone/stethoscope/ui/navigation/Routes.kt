package com.capstone.stethoscope.ui.navigation

/** Central place for navigation routes and argument keys. */
object Routes {
    const val SPLASH = "splash"
    const val LISTEN = "listen"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val TESTING = "testing"
    const val TUTORIAL = "tutorial"

    const val ARG_ID = "recordingId"
    const val REVIEW = "review/{$ARG_ID}"
    const val RESULT = "result/{$ARG_ID}"

    fun review(id: Long) = "review/$id"
    fun result(id: Long) = "result/$id"
}
