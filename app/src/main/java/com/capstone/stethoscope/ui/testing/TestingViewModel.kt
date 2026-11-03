package com.capstone.stethoscope.ui.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.capstone.stethoscope.StethoscopeApp
import com.capstone.stethoscope.analysis.pcg.PcgPreprocessor
import com.capstone.stethoscope.analysis.pcg.PcgSpec
import com.capstone.stethoscope.analysis.pcg.Spectrum
import com.capstone.stethoscope.audio.StethoscopeRecorder
import com.capstone.stethoscope.audio.usb.StethoscopeFrameParser
import com.capstone.stethoscope.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.log10

/**
 * Drives the testing-mode scope: captures or loads a signal, then exposes it at each
 * stage of the pipeline together with that stage's spectrum.
 *
 * The point is verification rather than diagnosis. Everything here shows *what the
 * pipeline actually did*, which is why the stages are produced by calling the pipeline's
 * own functions rather than by reimplementing them — a scope that agreed with a
 * reimplementation and disagreed with production would be worse than no scope at all.
 */
class TestingViewModel(private val container: AppContainer) : ViewModel() {

    /**
     * One step of the chain, in the order it is applied, plus the chain's own response.
     *
     * There used to be an "After 600 Hz LP" stage between the two. The app no longer
     * filters (see [PcgSpec]), so there is no intermediate signal to show: the capture
     * goes to 2 kHz and nothing else happens to it.
     */
    enum class Stage(val label: String) {
        RAW("Raw capture"),

        /**
         * The phonocardiogram: 2 kHz, exactly the bytes the classifier is handed and
         * exactly what the exported report draws. Named for what it *is* rather than for
         * the operation that produced it ("Decimated to 2 kHz") — this is the trace
         * anyone reading the app is actually looking for.
         */
        PCG("PCG (2 kHz)"),

        /**
         * The same capture read as volts at the ADC pin, against the 0-3.3 V rails.
         * Answers a different question from every other view here: not "what shape is
         * the signal" but "is the analog front end biased and scaled sensibly".
         */
        VOLTAGE("Voltage"),

        RESPONSE("Chain response"),
    }

    enum class Phase { EMPTY, CAPTURING, ANALYZING, READY, ERROR }

    /**
     * How to read a stage's samples as volts, when that is knowable.
     *
     * Null is not a detail to paper over: a microphone capture never had a voltage behind
     * it, and showing a millivolt axis for one would be inventing a measurement.
     *
     * Every stage of a USB capture now carries the calibration, including the output.
     * That is new — the output used to be peak-normalized, which destroyed the level — and
     * it is the practical benefit of handing the algorithm unconditioned samples: the
     * millivolts on the last plot are the millivolts at the ADC pin.
     */
    class Calibration(
        val voltsPerCount: Double,
        val supplyVolts: Double = StethoscopeFrameParser.SUPPLY_VOLTS,
    ) {
        fun millivolts(counts: Double): Double = counts * voltsPerCount * 1_000.0

        /** Half the supply — where a correctly biased front end should idle. */
        val midRailVolts: Double get() = supplyVolts / 2.0

        /**
         * Absolute potential at the ADC pin, in volts.
         *
         * Sample values are centred, because the USB path maps the ADC's unsigned
         * 0..4095 onto signed 16-bit with `(raw - 2048) shl 4`. So zero counts is not
         * zero volts, it is mid-rail — which is exactly the thing a voltage view exists
         * to check, and exactly the thing [millivolts] (a swing, not a potential) cannot
         * show.
         */
        fun volts(counts: Double): Double = midRailVolts + counts * voltsPerCount
    }

    /** A stage's samples plus its spectrum, both already computed off the main thread. */
    class Trace(
        val samples: DoubleArray,
        val sampleRate: Int,
        val spectrum: Spectrum.Result,
        val calibration: Calibration? = null,
    ) {
        val durationSeconds: Double =
            if (sampleRate > 0) samples.size.toDouble() / sampleRate else 0.0

        /** Loudest sample magnitude, in the trace's own units. */
        val peakCounts: Double = samples.maxOfOrNull { abs(it) } ?: 0.0

        /** Symmetric amplitude bound for the y-axis, never zero. */
        val amplitudeBound: Double = peakCounts.coerceAtLeast(1.0) * 1.05
    }

    /**
     * What the whole digital chain did to the signal, in dB, against what it is supposed
     * to do — which since 2026-09-23 is **nothing**.
     *
     * This is the one honest *gain* measurement the screen can make. Every other plot
     * shows an output whose input nobody recorded — you cannot get the gain of the analog
     * front end or of the chest coupling from a capture alone, that needs a known stimulus
     * and a signal generator. The digital chain is the exception: the app owns both ends
     * of it. [measuredDbAt] is `20·log10(|output| / |input|)` from the same clip through
     * the same estimator, so it is a real Bode magnitude with no calibration and no
     * assumptions.
     *
     * The reference curve is now a flat 0 dB line, and that makes this plot *more* useful
     * than it was, not less. It used to check a 600 Hz Butterworth against its own
     * textbook formula; it now checks the claim the algorithm team is relying on — that
     * the app hands them the signal unaltered. Any departure from 0 dB below the 1 kHz
     * fold is the app changing the recording, which is a bug.
     *
     * Only the decimation's anti-alias filter should show, and only as it approaches
     * 1 kHz. Comparing across two sample rates needs care: the spectra are calibrated for
     * tones, and broadband content — which is what a heart and a noise floor are — reads
     * higher in wider bins. [input] and [output] must therefore share a bin width (see
     * [Spectrum.matchedFftSize]); the ratio is taken of densities so any rounding left
     * over cancels too. Dividing plain amplitudes at 3.9 Hz vs 0.49 Hz bins read an
     * unaltered signal as about −9 dB on a real phone.
     */
    class Response(
        private val input: Spectrum.Result,
        private val output: Spectrum.Result,
        val nyquistHz: Double,
    ) {
        /** Bins this quiet in the input cannot measure a gain; they would divide by noise. */
        private val floor = input.peakAmplitude * 1e-9

        fun measuredDbAt(hz: Double): Double {
            val x = input.densityAt(hz)
            val y = output.densityAt(hz)
            if (input.amplitudeAt(hz) <= floor || x <= 0.0 || y <= 0.0) return Spectrum.FLOOR_DB
            return (20.0 * log10(y / x)).coerceAtLeast(Spectrum.FLOOR_DB)
        }

        /** Unity. The app is specified to leave the signal alone. */
        fun idealDbAt(hz: Double): Double = if (hz <= nyquistHz) 0.0 else Spectrum.FLOOR_DB

        /** How far the measurement is from the specification at [hz]. */
        fun errorDbAt(hz: Double): Double = measuredDbAt(hz) - idealDbAt(hz)
    }

    class Capture(
        val sourceLabel: String,
        val capturedRate: Int,
        val traces: Map<Stage, Trace>,
        val response: Response?,
        val truncatedFrom: Double?,
    )

    private val _phase = MutableStateFlow(Phase.EMPTY)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _capture = MutableStateFlow<Capture?>(null)
    val capture: StateFlow<Capture?> = _capture.asStateFlow()

    private val _stage = MutableStateFlow(Stage.PCG)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Progress of a live capture, so the button can show a countdown. */
    val elapsedMs: StateFlow<Long> = container.recorder.elapsedMs

    val requestedRate: Int get() = container.settings.sampleRate

    fun selectStage(stage: Stage) {
        _stage.value = stage
    }

    fun consumeError() {
        _error.value = null
    }

    /**
     * Records [seconds] from the real capture path and analyses it.
     *
     * Uses [StethoscopeRecorder] rather than opening a [com.capstone.stethoscope.audio.PcmSource]
     * directly, so the scope sees the same transport and the same negotiated rate a
     * clinical recording would. A bench tool that bypassed part of the path could show a
     * clean signal while the real one was broken.
     */
    fun captureNow(seconds: Int) {
        if (_phase.value == Phase.CAPTURING || _phase.value == Phase.ANALYZING) return
        _phase.value = Phase.CAPTURING
        _error.value = null

        viewModelScope.launch {
            val wav = File(container.cacheDir, "scope_capture.wav")
            when (val result = container.recorder.record(wav, requestedRate, seconds * 1000L)) {
                is StethoscopeRecorder.Result.Success -> analyze(
                    wav = wav,
                    sampleRate = result.sampleRate,
                    sourceLabel = container.recorder.sourceLabel.value.ifBlank { "capture" },
                    calibration = calibrationForLastCapture(),
                    deleteAfter = true,
                )

                is StethoscopeRecorder.Result.Failure -> {
                    runCatching { wav.delete() }
                    _error.value = result.message
                    _phase.value = Phase.ERROR
                }
            }
        }
    }

    fun stopCapture() = container.recorder.requestStop()

    /**
     * Volts per count for the capture that just finished, or null if the samples are not
     * traceable to the ADC.
     *
     * The source must be the real board — a phone microphone produces PCM with no defined
     * voltage behind it. Beyond that the conversion is exact: the USB path's
     * `(raw - 2048) shl 4` is lossless, and nothing rescales the clip afterwards now that
     * [com.capstone.stethoscope.audio.SignalConditioner] only measures.
     */
    private fun calibrationForLastCapture(): Calibration? {
        if (!container.recorder.usingUsbDevice.value) return null
        return Calibration(StethoscopeFrameParser.VOLTS_PER_COUNT)
    }

    /** Loads the most recent stored recording, so a clip can be re-examined later. */
    fun loadLatestRecording() {
        if (_phase.value == Phase.CAPTURING || _phase.value == Phase.ANALYZING) return
        _phase.value = Phase.ANALYZING
        _error.value = null

        viewModelScope.launch {
            val rec = container.repository.latest()
            if (rec == null) {
                _error.value = "No recordings stored yet — capture one first."
                _phase.value = Phase.ERROR
                return@launch
            }
            val plain = withContext(Dispatchers.IO) {
                runCatching { container.repository.decryptToCache(rec) }
            }.getOrElse { t ->
                _error.value = "Could not decrypt the recording: ${t.message}"
                _phase.value = Phase.ERROR
                return@launch
            }
            // No calibration for a stored clip: nothing records which transport it came
            // from, and a microphone recording has no volts behind it. The counts are now
            // untouched end to end, so this could be recovered by storing the source with
            // the recording — worth doing if the mV axis turns out to matter here.
            analyze(
                wav = plain,
                sampleRate = rec.sampleRate,
                sourceLabel = "stored recording #${rec.id}",
                calibration = null,
                deleteAfter = true,
            )
        }
    }

    private suspend fun analyze(
        wav: File,
        sampleRate: Int,
        sourceLabel: String,
        calibration: Calibration?,
        deleteAfter: Boolean,
    ) {
        _phase.value = Phase.ANALYZING
        try {
            val built = withContext(Dispatchers.IO) {
                buildCapture(wav, sampleRate, sourceLabel, calibration)
            }
            _capture.value = built
            _phase.value = Phase.READY
        } catch (t: Throwable) {
            _error.value = t.message ?: "Could not analyse the capture."
            _phase.value = Phase.ERROR
        } finally {
            if (deleteAfter) runCatching { wav.delete() }
        }
    }

    private fun buildCapture(
        wav: File,
        sampleRate: Int,
        sourceLabel: String,
        calibration: Calibration?,
    ): Capture {
        val full = PcgPreprocessor.readPcm16AsDoubles(wav)
        if (full.isEmpty()) error("The capture contained no audio.")

        // Cap the working length. At 16 kHz a 60-second clip is 960k samples per stage,
        // and three stages of boxed doubles plus their spectra is enough to matter on a
        // phone with 2 GB of RAM. The first 20 seconds answer every question this screen
        // is for.
        val limit = MAX_SECONDS * sampleRate
        val truncated = full.size > limit
        val raw = if (truncated) full.copyOfRange(0, limit) else full

        // Run the real pipeline for the final stage, rather than resampling here, so the
        // trace labelled "what the algorithm receives" is byte-for-byte that.
        val scratch = File(container.cacheDir, "scope_pcg.wav")
        val pcg = try {
            PcgPreprocessor.processSamples(raw, sampleRate, scratch)
        } finally {
            runCatching { scratch.delete() }
        }

        val rawTrace = trace(raw, sampleRate, calibration)
        // The output keeps the calibration: nothing rescales it any more, so its counts
        // mean the same volts the raw trace's do.
        val pcgSamples = DoubleArray(pcg.samples.size) { pcg.samples[it].toDouble() }
        val pcgTrace = trace(pcgSamples, PcgSpec.SAMPLE_RATE, calibration)
        // The PCG trace's own spectrum keeps its fine 0.49 Hz bins for the plot; the
        // response needs one whose bins match the raw spectrum's. See [Response].
        val pcgForResponse = Spectrum.estimate(
            pcgSamples,
            PcgSpec.SAMPLE_RATE,
            fftSize = Spectrum.matchedFftSize(PcgSpec.SAMPLE_RATE, sampleRate),
        )

        return Capture(
            sourceLabel = sourceLabel,
            capturedRate = sampleRate,
            truncatedFrom = if (truncated) full.size.toDouble() / sampleRate else null,
            response = responseOf(rawTrace.spectrum, pcgForResponse),
            traces = mapOf(Stage.RAW to rawTrace, Stage.PCG to pcgTrace),
        )
    }

    private fun trace(
        samples: DoubleArray,
        rate: Int,
        calibration: Calibration?,
    ) = Trace(samples, rate, Spectrum.estimate(samples, rate), calibration)

    /**
     * Divides the output spectrum by the input one to recover what the chain did.
     *
     * Legitimate because both spectra come from the same samples through the same
     * estimator, and both are calibrated in int16 counts — everything except the chain
     * cancels, including the capture's spectral shape, the hardware's response and any
     * calibration error. What is left should be 0 dB up to the fold.
     *
     * The comparison is bounded by the *output's* Nyquist: above 1 kHz the 2 kHz file has
     * nothing to say, and a ratio there would just be the input divided by the floor.
     */
    private fun responseOf(input: Spectrum.Result, output: Spectrum.Result): Response? {
        if (input.amplitude.isEmpty() || output.amplitude.isEmpty()) return null
        return Response(input = input, output = output, nyquistHz = output.nyquistHz)
    }

    companion object {
        /**
         * Working length cap. See [buildCapture] — three stages of a 60-second 16 kHz clip
         * plus their spectra is more memory than a bench screen deserves.
         */
        private const val MAX_SECONDS = 20

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as StethoscopeApp
                TestingViewModel(app.container)
            }
        }
    }
}
