package com.capstone.stethoscope.audio

import android.media.AudioManager
import com.capstone.stethoscope.data.AppSettings
import java.io.Closeable

/**
 * A source of mono 16-bit little-endian PCM.
 *
 * Exists because the stethoscope hardware does not reach the phone through the audio
 * framework. The RP2040 runs MicroPython, whose USB stack offers CDC (serial) and MSC
 * only — no USB Audio Class — so [android.media.AudioRecord] cannot see it at all. The
 * real capture path is a USB Host bulk transfer, and this interface lets the recorder
 * and the live monitor treat that identically to the built-in microphone.
 *
 * Implementations always emit **mono** samples at [sampleRate], whatever the underlying
 * device does about channels, so everything downstream stays simple.
 */
interface PcmSource : Closeable {

    /** Rate of the samples actually being produced, which may not be what was asked for. */
    val sampleRate: Int

    /** Short description for diagnostics and the UI. */
    val label: String

    /** True when this is the real stethoscope rather than a fallback. */
    val isStethoscope: Boolean

    /**
     * One line of transport health for the diagnostics screen, or null if the source has
     * nothing interesting to report. The USB source uses this to surface dropped frames
     * and ADC overruns — loss that would otherwise be invisible but would read as an
     * arrhythmia.
     */
    val diagnostics: String? get() = null

    /** Begins producing samples. Throws with a user-presentable message on failure. */
    fun start()

    /**
     * Reads up to [length] bytes of mono 16-bit PCM into [dest] at [offset].
     *
     * Returns the number of bytes written, `0` if nothing is available yet (the caller
     * should yield rather than spin), or a negative value on an unrecoverable error.
     */
    fun read(dest: ByteArray, offset: Int, length: Int): Int

    fun stop()
}

/**
 * [PcmSource] backed by [android.media.AudioRecord] — the built-in microphone, or a USB
 * Audio Class device if one ever appears.
 *
 * Owns the stereo-to-mono fold so that callers never have to think about channels.
 */
class MicPcmSource(
    private val audioManager: AudioManager,
    private val deviceMonitor: AudioDeviceMonitor,
    private val settings: AppSettings,
    private val requestedRate: Int,
    private val minBufferBytes: Int = 0,
) : PcmSource {

    private var session: AudioCapture.Session? = null
    private var scratch: ByteArray = ByteArray(0)
    private var folded: ByteArray = ByteArray(0)

    /** Partial stereo frame carried between reads; dropping one would swap channels. */
    private val carry = ByteArray(AudioCapture.STEREO_FRAME_BYTES)
    private var carryLen = 0

    override var sampleRate: Int = requestedRate
        private set

    override val label: String
        get() = if (session?.usingUsb == true) "USB audio device" else "Built-in microphone"

    override val isStethoscope: Boolean
        get() = session?.usingUsb == true

    override fun start() {
        val opened = AudioCapture.open(audioManager, deviceMonitor, requestedRate, minBufferBytes)
            ?: error(
                "Could not open the audio input at $requestedRate Hz. " +
                    "Try another sample rate, or check Settings › Hardware diagnostics."
            )
        session = opened
        sampleRate = opened.sampleRate
        scratch = ByteArray(opened.bufferSize)
        folded = if (opened.channels == 2) ByteArray(opened.bufferSize / 2) else ByteArray(0)
        carryLen = 0
        opened.record.startRecording()
    }

    override fun read(dest: ByteArray, offset: Int, length: Int): Int {
        val s = session ?: return -1
        val want = minOf(length, if (s.channels == 2) scratch.size - carryLen else scratch.size)
        if (want <= 0) return 0

        val read = s.record.read(scratch, carryLen, want)
        if (read < 0) return -1
        if (read == 0 && carryLen == 0) return 0

        if (s.channels != 2) {
            val n = minOf(read, length)
            System.arraycopy(scratch, 0, dest, offset, n)
            return n
        }

        // Prepend the leftover bytes from the previous read, fold, then keep whatever
        // partial frame is left at the end.
        if (carryLen > 0) System.arraycopy(carry, 0, scratch, 0, carryLen)
        val available = read + carryLen
        val written = AudioCapture.foldToMono(scratch, available, folded, settings.inputChannel)
        carryLen = available % AudioCapture.STEREO_FRAME_BYTES
        if (carryLen > 0) System.arraycopy(scratch, available - carryLen, carry, 0, carryLen)

        val n = minOf(written, length)
        System.arraycopy(folded, 0, dest, offset, n)
        return n
    }

    override fun stop() {
        runCatching { session?.record?.stop() }
    }

    override fun close() {
        runCatching { session?.record?.release() }
        session = null
    }
}
