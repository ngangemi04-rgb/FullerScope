package com.capstone.stethoscope.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.capstone.stethoscope.data.AppSettings

/**
 * Opens an [AudioRecord] against the stethoscope, coping with hardware that will only
 * give us two channels.
 *
 * The app records mono — that is what the diagnostic algorithm expects — but we can't
 * insist the hardware agrees. A microcontroller with two outputs may enumerate as a
 * stereo input and refuse a mono request outright, so this negotiates a working
 * configuration and reports how many channels it actually got. Callers fold the result
 * down with [foldToMono].
 *
 * Shared by [StethoscopeRecorder] and [LiveMonitor] so the Listen screen and the
 * Record screen can never disagree about what the hardware supports.
 */
internal object AudioCapture {

    data class Session(
        val record: AudioRecord,
        /** Channels actually being captured: 1 or 2. */
        val channels: Int,
        /** Rate actually opened, which may not be the rate that was asked for. */
        val sampleRate: Int,
        val bufferSize: Int,
        /** True when routed to a USB input rather than the built-in mic. */
        val usingUsb: Boolean,
    )

    /**
     * Rates to fall back on, in preference order, when the requested one is refused.
     *
     * 16 kHz leads because that is the rate the signal chain is specified at
     * ([com.capstone.stethoscope.analysis.pcg.PcgSpec.CAPTURE_SAMPLE_RATE]): lowpass at
     * 600 Hz, then an exact ÷8 to the 2 kHz PCG output. The rest are ordered by how
     * cleanly they reach 2000 — 8000 ÷ 4 and 48000 ÷ 24 resample down with short, cheap
     * filters, whereas 44100 → 2000 needs a 20/441 rational conversion with a filter two
     * orders of magnitude longer.
     *
     * Android only guarantees 44100 Hz, so a fallback is always possible; the rate
     * actually opened is stored on the recording and flagged on the diagnostics screen,
     * so nothing downstream is misled about it.
     */
    private val FALLBACK_RATES = listOf(16_000, 8_000, 48_000, 4_000, 44_100, 22_050, 11_025)

    /**
     * Body sounds are quiet and low-frequency; automatic gain control and noise
     * suppression treat them as junk and would filter out the very thing we record.
     */
    fun preferredSource(audioManager: AudioManager): Int =
        if (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }

    /**
     * Returns a started-but-not-recording session, or null if nothing worked at any
     * rate. [minBufferBytes] raises the buffer floor when the caller also has an output
     * buffer to satisfy (live passthrough).
     *
     * [sampleRate] is tried first, then whatever the device itself advertises, then
     * [FALLBACK_RATES]. Check [Session.sampleRate] for what was actually opened —
     * assuming it matches the request is how a recording ends up mislabelled.
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is enforced by the UI before we get here.
    fun open(
        audioManager: AudioManager,
        deviceMonitor: AudioDeviceMonitor,
        sampleRate: Int,
        minBufferBytes: Int = 0,
    ): Session? {
        val device = deviceMonitor.usbInputDevice()
        val source = preferredSource(audioManager)

        for (rate in ratePreference(sampleRate, device?.sampleRates)) {
            for (channels in channelPreference(device?.channelCounts)) {
                val mask =
                    if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
                val min = AudioRecord.getMinBufferSize(rate, mask, ENCODING)
                if (min <= 0) continue

                val bufferSize = maxOf(min, minBufferBytes) * 2
                val record = AudioRecord(source, rate, mask, ENCODING, bufferSize)
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    continue
                }
                if (device != null) record.setPreferredDevice(device)
                return Session(record, channels, rate, bufferSize, usingUsb = device != null)
            }
        }
        return null
    }

    /**
     * Rates to try, best first: the requested one, then rates the device advertises
     * (which is where a 4 kHz source announces itself), then the usual suspects.
     */
    private fun ratePreference(requested: Int, advertised: IntArray?): List<Int> =
        (listOf(requested) + (advertised?.toList() ?: emptyList()) + FALLBACK_RATES).distinct()

    /**
     * Channel counts to try, best first. An empty [supported] list means the device
     * didn't declare a restriction, in which case mono is worth trying first — it is
     * what we ultimately want and avoids a pointless fold.
     */
    private fun channelPreference(supported: IntArray?): List<Int> = when {
        supported == null || supported.isEmpty() -> listOf(1, 2)
        !supported.contains(1) && supported.contains(2) -> listOf(2)
        else -> listOf(1, 2)
    }

    /**
     * Collapses interleaved stereo in [src] into mono in [dst], honouring [channel].
     * Returns the number of bytes written to [dst].
     *
     * Only whole 4-byte frames are consumed; [frameBytes] left over at the end are the
     * caller's problem to carry into the next read. Getting that wrong swaps left and
     * right for the rest of the recording, which is why it isn't silently truncated.
     */
    fun foldToMono(
        src: ByteArray,
        length: Int,
        dst: ByteArray,
        channel: AppSettings.InputChannel,
    ): Int {
        var read = 0
        var written = 0
        while (read + 3 < length) {
            val left = readSample16(src, read)
            val right = readSample16(src, read + 2)
            val value = when (channel) {
                AppSettings.InputChannel.LEFT -> left
                AppSettings.InputChannel.RIGHT -> right
                // Average rather than sum: summing two correlated channels clips.
                AppSettings.InputChannel.MIX -> (left + right) / 2
            }
            writeSample16(dst, written, value)
            read += 4
            written += 2
        }
        return written
    }

    /** Bytes in one interleaved stereo frame. */
    const val STEREO_FRAME_BYTES = 4

    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
}
