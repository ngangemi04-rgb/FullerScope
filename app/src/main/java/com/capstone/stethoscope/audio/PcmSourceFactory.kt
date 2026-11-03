package com.capstone.stethoscope.audio

import android.content.Context
import android.media.AudioManager
import com.capstone.stethoscope.audio.usb.UsbCdcPcmSource
import com.capstone.stethoscope.audio.usb.UsbStethoscope
import com.capstone.stethoscope.data.AppSettings

/**
 * Chooses where audio comes from: the USB stethoscope when one is attached and
 * permitted, otherwise the built-in microphone.
 *
 * Keeping the microphone as a fallback is deliberate rather than lazy. It lets the whole
 * app — recording, preprocessing, diagnosis, export — be exercised on any phone with no
 * hardware attached, which is how most development happens.
 */
class PcmSourceFactory(
    context: Context,
    private val deviceMonitor: AudioDeviceMonitor,
    private val stethoscope: UsbStethoscope,
    private val settings: AppSettings,
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** True when a real stethoscope is attached and ready, so the UI can say so. */
    fun stethoscopeReady(): Boolean = stethoscope.state.value is UsbStethoscope.State.Ready

    /**
     * Builds a source, preferring the stethoscope. [minBufferBytes] raises the buffer
     * floor for callers that also have an output buffer to satisfy (live passthrough).
     *
     * If the USB device is present but fails to start, the error is deliberately *not*
     * swallowed into a microphone fallback — recording a clinician's chest with the
     * phone's own microphone and presenting the result as auscultation would be worse
     * than failing loudly.
     */
    fun create(requestedRate: Int, minBufferBytes: Int = 0): PcmSource =
        if (stethoscope.state.value is UsbStethoscope.State.Ready) {
            UsbCdcPcmSource(stethoscope, fallbackRate = requestedRate)
        } else {
            MicPcmSource(audioManager, deviceMonitor, settings, requestedRate, minBufferBytes)
        }
}
