package com.capstone.stethoscope.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks audio-device connections live and exposes them as [StateFlow]s the UI can
 * observe:
 *  - [usbConnected]        — a USB Audio Class input (the stethoscope) is attached.
 *  - [headsetConnected]    — an earbud/headphone OUTPUT is attached, so live
 *                            monitoring can be played back without speaker feedback.
 *
 * Registered once for the app's lifetime in [com.capstone.stethoscope.StethoscopeApp].
 */
class AudioDeviceMonitor(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _usbConnected = MutableStateFlow(usbInputDevice() != null)
    val usbConnected: StateFlow<Boolean> = _usbConnected.asStateFlow()

    private val _headsetConnected = MutableStateFlow(headsetOutputDevice() != null)
    val headsetConnected: StateFlow<Boolean> = _headsetConnected.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    private fun refresh() {
        _usbConnected.value = usbInputDevice() != null
        _headsetConnected.value = headsetOutputDevice() != null
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    /** The connected USB input (stethoscope), or null. Used to route recording. */
    fun usbInputDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

    /** A connected earbud/headphone output, or null. Used to route live monitoring. */
    fun headsetOutputDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type in HEADSET_OUTPUT_TYPES
        }

    private companion object {
        val HEADSET_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
