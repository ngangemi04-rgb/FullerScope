package com.capstone.stethoscope

import android.app.Application
import com.capstone.stethoscope.di.AppContainer

class StethoscopeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Begin monitoring for audio-device and USB stethoscope connect/disconnect for
        // the app's lifetime.
        container.deviceMonitor.start()
        container.usbStethoscope.start()
        // Firmware is NOT checked or uploaded on attach any more (it was causing problems);
        // this only resets the firmware status on unplug. Uploading is manual, from
        // Settings → Hardware diagnostics.
        container.firmware.start()
    }
}
