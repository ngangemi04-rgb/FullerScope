package com.capstone.stethoscope.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/**
 * Records mono 16-bit PCM to a WAV file from whichever [PcmSource] is available.
 *
 * The source abstraction matters here: the stethoscope is an RP2040 running MicroPython,
 * which presents a USB CDC serial port rather than a USB Audio Class device, so
 * [android.media.AudioRecord] cannot see it. [PcmSourceFactory] picks the USB transport
 * when the board is attached and falls back to the built-in microphone otherwise, and
 * this class does not need to know which it got.
 *
 * The finished clip is measured by [SignalConditioner] but deliberately not altered by it
 * — the recording this writes is the one the algorithm reads, and it is handed over
 * unconditioned.
 */
class StethoscopeRecorder(
    private val sources: PcmSourceFactory,
) {
    private val bitsPerSample = 16

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    /** Normalized 0f..1f input level for the live meter. */
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    /** Rolling window of recent amplitudes for the scrolling waveform. */
    private val waveformWindow = WaveformWindow()
    val waveform: StateFlow<List<Float>> = waveformWindow.values

    /** True while the real USB stethoscope (not the built-in mic) is in use. */
    private val _usingUsbDevice = MutableStateFlow(false)
    val usingUsbDevice: StateFlow<Boolean> = _usingUsbDevice.asStateFlow()

    /** Which source the last run actually used, for the UI and diagnostics. */
    private val _sourceLabel = MutableStateFlow("")
    val sourceLabel: StateFlow<String> = _sourceLabel.asStateFlow()

    /**
     * Transport health from the last run — frames decoded, dropped, and ADC overruns for
     * the USB source. Null when the source had nothing to report.
     */
    private val _transportDiagnostics = MutableStateFlow<String?>(null)
    val transportDiagnostics: StateFlow<String?> = _transportDiagnostics.asStateFlow()

    /**
     * What conditioning had to do to the most recent recording. Surfaced on the
     * Diagnostics screen: a gain of x16 is the giveaway that the source is a
     * right-aligned 12-bit ADC.
     */
    private val _lastSignalStats = MutableStateFlow(SignalConditioner.Stats.EMPTY)
    val lastSignalStats: StateFlow<SignalConditioner.Stats> = _lastSignalStats.asStateFlow()

    /**
     * Rate actually captured on the last run. With the USB source this is whatever the
     * firmware declared in its frame headers; with the microphone it is whatever Android
     * agreed to open.
     */
    private val _actualSampleRate = MutableStateFlow(0)
    val actualSampleRate: StateFlow<Int> = _actualSampleRate.asStateFlow()

    private val meter = LevelMeter()

    @Volatile
    private var stopRequested = false

    /** Ask the active recording loop to finish early (e.g. clinician taps Stop). */
    fun requestStop() {
        stopRequested = true
    }

    sealed interface Result {
        data class Success(val durationMs: Long, val sampleRate: Int) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Records into [outputFile] until [maxDurationMs] elapses or [requestStop] is
     * called. Suspends until finished; runs the capture loop on the IO dispatcher.
     */
    suspend fun record(
        outputFile: File,
        sampleRate: Int,
        maxDurationMs: Long,
    ): Result = withContext(Dispatchers.IO) {
        stopRequested = false
        _elapsedMs.value = 0L
        _amplitude.value = 0f
        waveformWindow.clear()
        meter.reset()

        val source = sources.create(sampleRate)
        var raf: RandomAccessFile? = null
        var monoBytes = 0L
        try {
            source.start()
            val actualRate = source.sampleRate
            _actualSampleRate.value = actualRate
            _usingUsbDevice.value = source.isStethoscope
            _sourceLabel.value = source.label

            raf = RandomAccessFile(outputFile, "rw")
            raf.setLength(0)
            raf.write(ByteArray(WavHeader.SIZE)) // reserve header, patched on stop

            val buffer = ByteArray(READ_BUFFER_BYTES)
            val bytesPerMs = actualRate * (bitsPerSample / 8) / 1000.0

            while (coroutineContext.isActive && !stopRequested) {
                val read = source.read(buffer, 0, buffer.size)
                when {
                    read > 0 -> {
                        raf.write(buffer, 0, read)
                        monoBytes += read
                        // Raw peak plus the current auto-range reference: WaveformWindow
                        // rescales its whole history when the ceiling moves, so the trace
                        // never carries two scales at once.
                        val peak = meter.peak(buffer, read)
                        val fullScale = meter.fullScale
                        _amplitude.value = (peak / fullScale).coerceIn(0f, 1f)
                        waveformWindow.push(peak, fullScale)
                        val elapsed = (monoBytes / bytesPerMs).toLong()
                        _elapsedMs.value = elapsed
                        if (elapsed >= maxDurationMs) break
                    }
                    // Nothing available yet — yield rather than busy-spinning, which
                    // would peg a core for the whole recording.
                    read == 0 -> delay(2)
                    else -> break // negative: the source is gone
                }
            }

            source.stop()
            _transportDiagnostics.value = source.diagnostics
            WavHeader.write(raf, actualRate, channels = 1, bitsPerSample, monoBytes)
            raf.close()
            raf = null

            if (monoBytes == 0L) {
                return@withContext Result.Failure(
                    "No audio was captured from ${source.label}."
                )
            }

            // Measure how the clip uses the 16-bit range, for the diagnostics screen.
            // This used to rescale a 12-bit source in place; it no longer touches the
            // audio, because that file is what the algorithm reads. See SignalConditioner.
            _lastSignalStats.value = SignalConditioner.measure(outputFile)

            Result.Success((monoBytes / bytesPerMs).toLong(), actualRate)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.Failure(t.message ?: "Recording failed")
        } finally {
            _amplitude.value = 0f
            runCatching { raf?.close() }
            runCatching { source.close() }
        }
    }

    private companion object {
        /** ~0.25 s of 8 kHz mono audio; large enough to be efficient, small enough to keep the meter responsive. */
        const val READ_BUFFER_BYTES = 4096
    }
}
