package com.capstone.stethoscope.di

import android.content.Context
import com.capstone.stethoscope.analysis.HeartbeatClassifier
import com.capstone.stethoscope.analysis.MockHeartbeatClassifier
import com.capstone.stethoscope.analysis.segmenter.HeartSoundSegmenter
import com.capstone.stethoscope.audio.AudioDeviceMonitor
import com.capstone.stethoscope.audio.AudioPlayer
import com.capstone.stethoscope.audio.LiveMonitor
import com.capstone.stethoscope.audio.PcmSourceFactory
import com.capstone.stethoscope.audio.StethoscopeRecorder
import com.capstone.stethoscope.audio.usb.FirmwareManager
import com.capstone.stethoscope.audio.usb.UsbStethoscope
import com.capstone.stethoscope.data.AppSettings
import com.capstone.stethoscope.data.RecordingRepository
import com.capstone.stethoscope.data.SessionStore
import com.capstone.stethoscope.data.db.AppDatabase
import java.io.File

/**
 * Manual dependency container (lightweight alternative to Hilt for a capstone).
 * Created once in [com.capstone.stethoscope.StethoscopeApp] and reached by
 * ViewModels through their factories.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** App-private cache dir, used for transient plaintext audio (recording, playback). */
    val cacheDir: File = appContext.cacheDir

    val settings: AppSettings = AppSettings(appContext)

    val deviceMonitor: AudioDeviceMonitor = AudioDeviceMonitor(appContext)

    /** Finds the RP2040 stethoscope on the USB bus and holds its runtime permission. */
    val usbStethoscope: UsbStethoscope = UsbStethoscope(appContext)

    /**
     * Verifies — and if necessary reflashes — the board's firmware whenever it is plugged
     * in, so the app is never reading 8 kHz frames from a stale `main.py` while assuming
     * the 16 kHz the signal chain is specified for.
     */
    val firmware: FirmwareManager = FirmwareManager(appContext, usbStethoscope)

    /** Picks the USB stethoscope when it is available, the built-in mic otherwise. */
    val sources: PcmSourceFactory =
        PcmSourceFactory(appContext, deviceMonitor, usbStethoscope, settings)

    val recorder: StethoscopeRecorder = StethoscopeRecorder(sources)

    val liveMonitor: LiveMonitor = LiveMonitor(sources, deviceMonitor)

    val player: AudioPlayer = AudioPlayer()

    /** Shared state across the Listen → Record flow (e.g. patient draft). */
    val session: SessionStore = SessionStore()

    val repository: RecordingRepository = RecordingRepository(
        dao = AppDatabase.getInstance(appContext).recordingDao(),
        recordingsDir = File(appContext.filesDir, "recordings"),
        cacheDir = appContext.cacheDir,
    )

    /**
     * The analysis algorithm — a Kotlin port of the algorithm team's
     * `segmentHeartSound.m`: heart rate, rhythm regularity and signal quality. The trained
     * weights ship verbatim as `assets/segmenter/model.json`; to update the model, replace
     * that file (and the test vectors) and re-run `SegmenterParityTest`.
     *
     * The murmur classifier (`classifyMurmur.m`) runs on the segmenter's output; its
     * constants ship verbatim as `assets/murmur/model.json`, checked by `MurmurParityTest`.
     *
     * Swap in [MockHeartbeatClassifier] to demo the UI without real analysis; it answers
     * from the recording's length rather than its audio.
     */
    val classifier: HeartbeatClassifier = HeartSoundSegmenter(
        modelSource = { appContext.assets.open("segmenter/model.json") },
        murmurSource = { appContext.assets.open("murmur/model.json") },
    )
}
