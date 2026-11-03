package com.capstone.stethoscope.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live "listening" mode used before recording: continuously captures from the
 * stethoscope (or built-in mic), drives a scrolling [waveform], and — when earbuds are
 * connected — plays the audio straight through so the clinician can hear the heartbeat
 * in real time and position the stethoscope.
 *
 * Reads through [PcmSource], so it works with the USB CDC stethoscope as well as the
 * microphone. Playback always runs at whatever rate the source reports, since the USB
 * firmware chooses its own rate and playing back at a mismatched rate would shift the
 * pitch.
 *
 * Passthrough is only enabled when a headset OUTPUT is present, to avoid the
 * microphone-to-speaker feedback howl.
 */
class LiveMonitor(
    private val sources: PcmSourceFactory,
    private val deviceMonitor: AudioDeviceMonitor,
) {
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    private val meter = LevelMeter()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val pcgWindow = LivePcgWindow()
    val pcg: StateFlow<LivePcg> = pcgWindow.frame

    private val _usingUsbDevice = MutableStateFlow(false)
    val usingUsbDevice: StateFlow<Boolean> = _usingUsbDevice.asStateFlow()

    /** Set when monitoring could not start, so the UI can explain why. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun consumeError() {
        _error.value = null
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope, sampleRate: Int) {
        if (_active.value) return
        _active.value = true
        _error.value = null
        meter.reset()

        job = scope.launch(Dispatchers.IO) {
            // Rough floor only; the real output buffer is sized once the source reports
            // the rate it actually negotiated.
            val estimate = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
                .coerceAtLeast(0)
            val source = sources.create(sampleRate, estimate)
            var track: AudioTrack? = null

            try {
                source.start()
                val actualRate = source.sampleRate
                _usingUsbDevice.value = source.isStethoscope
                pcgWindow.reset(actualRate)

                val minOut = AudioTrack.getMinBufferSize(actualRate, channelOut, encoding)
                if (minOut <= 0) {
                    _error.value = "This phone cannot play back at $actualRate Hz."
                    return@launch
                }
                val bufferSize = minOut * 2

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(actualRate)
                            .setChannelMask(channelOut)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                deviceMonitor.headsetOutputDevice()?.let { track.setPreferredDevice(it) }
                track.play()

                // Small fixed reads, not one track-buffer's worth: the trace refreshes once
                // per read, and some phones' track buffers are a sizeable part of a second.
                val buffer = ByteArray(LivePcgWindow.READ_BYTES)
                while (isActive && _active.value) {
                    val read = source.read(buffer, 0, buffer.size)
                    when {
                        read > 0 -> {
                            _amplitude.value = meter.level(buffer, read)
                            pcgWindow.push(buffer, read)
                            // Only play back through earbuds; never the speaker (feedback).
                            if (deviceMonitor.headsetOutputDevice() != null) {
                                track.write(buffer, 0, read)
                            }
                        }
                        read == 0 -> delay(4)
                        else -> break
                    }
                }
            } catch (e: CancellationException) {
                // stop() cancels this job on purpose — that is not an error to show.
                // (Reporting it is what surfaced "StandaloneCoroutine was cancelled".)
                throw e
            } catch (t: Throwable) {
                _error.value = t.message ?: "Could not start listening."
            } finally {
                runCatching { source.stop() }
                runCatching { source.close() }
                runCatching { track?.stop() }
                runCatching { track?.release() }
                _amplitude.value = 0f
                _active.value = false
            }
        }
    }

    fun stop() {
        _active.value = false
        job?.cancel()
        job = null
    }

    /**
     * Stops and waits until the capture source is actually closed.
     *
     * [stop] only *requests* cancellation; the source is closed later, in the job's
     * `finally`. The USB stethoscope can be claimed once, so anything that wants the
     * device next (recording) must use this, or it races the monitor for the claim.
     */
    suspend fun stopAndWait() {
        _active.value = false
        val j = job
        job = null
        j?.cancelAndJoin()
    }
}
