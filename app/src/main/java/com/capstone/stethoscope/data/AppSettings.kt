package com.capstone.stethoscope.data

import android.content.Context
import com.capstone.stethoscope.analysis.pcg.PcgSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight synchronous settings (SharedPreferences) for recording defaults.
 * Language is handled separately in [com.capstone.stethoscope.locale.LocaleHelper].
 */
class AppSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("stethoscope_settings", Context.MODE_PRIVATE)

    init {
        migrate()
    }

    /**
     * Brings a settings file written by an older build up to date.
     *
     * The sample rate is the reason this exists. Changing [DEFAULT_SAMPLE_RATE] only
     * affects installs that have never stored a value — any phone that ran an earlier
     * build still had 4000 Hz written to prefs, so it would have gone on capturing at
     * 4 kHz while the rest of the app assumed the 16 kHz it is now specified for. Nothing
     * would have failed; the recordings would just have been quietly built on a quarter
     * of the intended resolution, which is the worst kind of bug to ship.
     *
     * Only the rate is forced, and only once, so a deliberate choice made after this
     * upgrade survives.
     */
    private fun migrate() {
        if (prefs.getInt(KEY_SETTINGS_VERSION, 0) >= SETTINGS_VERSION) return
        prefs.edit()
            .putInt(KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE)
            .putInt(KEY_SETTINGS_VERSION, SETTINGS_VERSION)
            .apply()
    }

    /** Recording length in milliseconds. Defaults to the 60-second clinical clip. */
    var recordingDurationMs: Long
        get() = prefs.getLong(KEY_DURATION, 60_000L)
        set(value) = prefs.edit().putLong(KEY_DURATION, value).apply()

    /**
     * PCM sample rate to request.
     *
     * Defaults to [PcgSpec.CAPTURE_SAMPLE_RATE] — 16 kHz — which is what the whole signal
     * chain is specified around: capture at 16 kHz, lowpass at 600 Hz, decimate ÷8 to the
     * 2 kHz PCG output. The firmware streams at 16 kHz too, so on the USB path this is the
     * rate that actually arrives.
     *
     * The other options exist for microphone-only development and for the testing screen;
     * anything other than 16 kHz makes the decimation a worse ratio and, on the microphone
     * path, may not be honoured at all. Android only guarantees 44100 Hz, so
     * [com.capstone.stethoscope.audio.AudioCapture] falls back to a supported rate and
     * reports the one it actually used.
     */
    var sampleRate: Int
        get() = prefs.getInt(KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE)
        set(value) = prefs.edit().putInt(KEY_SAMPLE_RATE, value).apply()

    /**
     * Shows the engineering scope: PCG with real axes, spectrum, per-stage filter taps.
     *
     * Off by default because it exposes intermediate signals that are meaningless to a
     * clinician, and a bench tool presented next to a diagnosis invites the two being
     * confused.
     *
     * Backed by a flow, unlike the other settings, because the Listen screen has to show
     * or hide its shortcut the moment this changes. The other settings are only read when
     * a capture starts, so a plain getter is enough for them; this one is read while a
     * screen the user just navigated back from is already composed, and
     * SharedPreferences does not announce writes.
     */
    var testingMode: Boolean
        get() = _testingMode.value
        set(value) {
            prefs.edit().putBoolean(KEY_TESTING_MODE, value).apply()
            _testingMode.value = value
        }

    private val _testingMode = MutableStateFlow(prefs.getBoolean(KEY_TESTING_MODE, false))
    val testingModeFlow: StateFlow<Boolean> = _testingMode.asStateFlow()

    /**
     * Which channel to keep when the hardware delivers two. Recordings are always
     * saved as mono — the diagnostic algorithm expects a single channel — so this
     * decides how a stereo capture is folded down.
     */
    var inputChannel: InputChannel
        get() = runCatching { InputChannel.valueOf(prefs.getString(KEY_INPUT_CHANNEL, null) ?: "") }
            .getOrDefault(InputChannel.LEFT)
        set(value) = prefs.edit().putString(KEY_INPUT_CHANNEL, value.name).apply()

    enum class InputChannel { LEFT, RIGHT, MIX }

    /** Set once the first-run tutorial has been finished or skipped; it is replayable from Settings. */
    var tutorialSeen: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_TUTORIAL_SEEN, value).apply()

    companion object {
        private const val KEY_TUTORIAL_SEEN = "tutorial_seen"
        private const val KEY_DURATION = "recording_duration_ms"
        private const val KEY_SAMPLE_RATE = "sample_rate"
        private const val KEY_INPUT_CHANNEL = "input_channel"
        private const val KEY_TESTING_MODE = "testing_mode"
        private const val KEY_SETTINGS_VERSION = "settings_version"

        /** Bump to force a re-migration. 1 = the move to a 16 kHz capture rate. */
        private const val SETTINGS_VERSION = 1

        /** The rate the signal chain is designed around. */
        const val DEFAULT_SAMPLE_RATE = PcgSpec.CAPTURE_SAMPLE_RATE

        /** 16 kHz first, because it is the only one the pipeline is specified for. */
        val SAMPLE_RATE_OPTIONS = listOf(16_000, 8_000, 4_000, 44_100)
    }
}
