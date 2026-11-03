package com.capstone.stethoscope.audio

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Simple WAV playback for the review screen. One recording at a time. */
class AudioPlayer {

    private var player: MediaPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Resets the button when playback ends, as a backstop to the completion listener.
     *
     * The listener alone left the button stuck on Stop after the clip finished: some
     * devices' MediaPlayer never delivers onCompletion for low-rate PCM WAVs. `isPlaying`
     * goes false at end of stream regardless, so poll it while a clip is playing.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            val stillPlaying = runCatching { p.isPlaying }.getOrDefault(false)
            if (stillPlaying) handler.postDelayed(this, WATCHDOG_MS) else stop()
        }
    }

    fun toggle(filePath: String) {
        if (_isPlaying.value) stop() else play(filePath)
    }

    /**
     * Throws if the phone cannot open the file. setDataSource/prepare fail outright on
     * some devices' media stacks rather than reporting through the error listener, and
     * uncaught that took the whole app down; the half-built player is released first so
     * a retry starts clean.
     */
    private fun play(filePath: String) {
        stop()
        val p = MediaPlayer()
        try {
            p.setDataSource(filePath)
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, _, _ -> stop(); true }
            p.prepare()
            p.start()
        } catch (t: Throwable) {
            runCatching { p.release() }
            throw t
        }
        player = p
        _isPlaying.value = true
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    fun stop() {
        handler.removeCallbacks(watchdog)
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            it.release()
        }
        player = null
        _isPlaying.value = false
    }

    private companion object {
        const val WATCHDOG_MS = 250L
    }
}
