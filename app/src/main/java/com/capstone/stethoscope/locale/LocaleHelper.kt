package com.capstone.stethoscope.locale

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Per-app language switching that works on every supported API level, independent
 * of the phone's system language — important for shared clinic devices in Ghana.
 *
 * The chosen language is stored in SharedPreferences and applied in
 * [com.capstone.stethoscope.MainActivity.attachBaseContext] before any UI inflates.
 */
object LocaleHelper {

    private const val PREFS = "stethoscope_settings"
    private const val KEY_LANGUAGE = "language"

    /** BCP-47 tags for the languages we ship string resources for. */
    val SUPPORTED = listOf(
        Language("en", "English"),
        Language("tw", "Twi"),
    )

    data class Language(val tag: String, val displayName: String)

    fun getLanguageTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "en") ?: "en"

    fun setLanguageTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, tag)
            .apply()
    }

    /** Wraps [base] with the saved locale; call from attachBaseContext. */
    fun applyLocale(base: Context): Context {
        val locale = Locale.forLanguageTag(getLanguageTag(base))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
