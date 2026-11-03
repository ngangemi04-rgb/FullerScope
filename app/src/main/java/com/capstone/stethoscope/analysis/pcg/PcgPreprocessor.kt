package com.capstone.stethoscope.analysis.pcg

import com.capstone.stethoscope.audio.WavHeader
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Puts a raw capture into the PhysioNet-compatible container the diagnostic algorithm
 * reads, **without altering the signal**.
 *
 * ```
 * raw at 16 kHz ──► decimate to 2 kHz ──► int16 ──► canonical 44-byte 2 kHz mono WAV
 *                   (anti-alias only)     (clamped)
 * ```
 *
 * ### Why there is nothing else in that diagram (2026-09-23)
 *
 * The algorithm team asked for the signal unfiltered: they do their own conditioning, and
 * two conditioning stages in series would put the features somewhere neither side
 * designed for. So the 600 Hz zero-phase lowpass, the DC removal and the peak
 * normalization this stage used to apply are all gone. What reaches the classifier is the
 * ADC's own sample values, resampled and nothing else.
 *
 * ### Why the resampler is still here
 *
 * Rate conversion is not conditioning, and its anti-alias filter is not a choice. Taking
 * every eighth sample of a 16 kHz capture folds everything between 1 kHz and 8 kHz down
 * onto the heart sounds, where no later filter — theirs or ours — can separate it again.
 * [Resampler]'s FIR is linear-phase and group-delay compensated, so it costs no phase
 * distortion; it removes only content that cannot survive a 2 kHz file in the first place.
 *
 * If the algorithm team would rather resample themselves, the honest answer is to hand
 * them the 16 kHz capture and delete this class — not to decimate without the filter.
 *
 * ### Consequences worth stating
 *
 *  - **DC offset and baseline wander reach the output.** Deliberate.
 *  - **Absolute level is preserved**, so two recordings are comparable in loudness. That
 *    is new; peak normalization used to destroy it.
 *  - **Nothing guarantees the file is loud.** A quiet capture produces a quiet file, which
 *    is a truthful representation of a quiet chest.
 *  - The classifier's own causal 25–400 Hz Butterworth inside
 *    [com.capstone.stethoscope.analysis.heartsound.HeartSoundFeatures] still runs. It is
 *    part of the ported MATLAB *feature extractor*, not of the app's signal chain, and its
 *    coefficients are what the trained weights were fitted against.
 */
object PcgPreprocessor {

    /** What the pipeline did, for logging and for the diagnostics screen. */
    data class Report(
        val inputSampleRate: Int,
        val inputSamples: Int,
        val outputSamples: Int,
        val durationMs: Long,
        /** Loudest output sample, in the input's own units — the level is now preserved. */
        val peak: Double,
        /** Samples the int16 range could not hold. Non-zero means the front end clipped. */
        val clipped: Int,
    )

    data class Result(val file: File, val samples: ShortArray, val report: Report) {
        // Data class equals/hashCode on a ShortArray compares references, which is
        // never what a caller means. Identity is the right semantic here.
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /**
     * Reads the 16-bit mono WAV at [source] (captured at [sourceSampleRate]), resamples to
     * [PcgSpec.SAMPLE_RATE], and writes a spec-compliant file to [destination].
     *
     * The sample values are not otherwise touched. See the class doc for why.
     */
    fun process(source: File, sourceSampleRate: Int, destination: File): Result =
        processSamples(
            raw = readPcm16AsDoubles(source),
            sourceSampleRate = sourceSampleRate,
            destination = destination,
        )

    /** Same pipeline, starting from samples already in memory. Exposed for tests. */
    fun processSamples(
        raw: DoubleArray,
        sourceSampleRate: Int,
        destination: File,
    ): Result {
        val x = Resampler.resample(raw, sourceSampleRate, PcgSpec.SAMPLE_RATE)
        val samples = toInt16(x)
        writeCanonicalWav(destination, samples)

        var peak = 0.0
        var clipped = 0
        for (v in x) {
            val magnitude = abs(v)
            if (magnitude > peak) peak = magnitude
            if (v > MAX_INT16 || v < MIN_INT16) clipped++
        }

        return Result(
            file = destination,
            samples = samples,
            report = Report(
                inputSampleRate = sourceSampleRate,
                inputSamples = raw.size,
                outputSamples = samples.size,
                durationMs = samples.size.toLong() * 1000 / PcgSpec.SAMPLE_RATE,
                peak = peak,
                clipped = clipped,
            ),
        )
    }

    /**
     * Rounds to int16, clamping rather than wrapping.
     *
     * Works in **int16 units** (±32768) throughout, not the ±1.0 float convention the
     * Python reference uses: the input comes straight off a 16-bit WAV, so int16 units are
     * the natural domain. Getting this wrong is silent and total — clamping int16-scale
     * values to ±1.0 first would saturate every sample above 1 LSB to full scale.
     *
     * Clamping matters on its own terms now that nothing normalizes ahead of it. Wrapping
     * would turn a loud S1 into a full-scale negative spike, which looks exactly like an
     * arrhythmia to a classifier; clipping merely flattens the peak, which is visible as
     * clipping and reported in [Report.clipped]. The resampler can also overshoot the
     * input's own peak slightly (Gibbs ringing around a sharp transient), so a capture that
     * already sat at full scale can produce a handful of clipped output samples.
     */
    private fun toInt16(x: DoubleArray): ShortArray =
        ShortArray(x.size) { i -> x[i].coerceIn(MIN_INT16, MAX_INT16).roundToInt().toShort() }

    private const val MAX_INT16 = 32_767.0
    private const val MIN_INT16 = -32_768.0

    /**
     * Reads 16-bit little-endian mono PCM out of a canonical 44-byte-header WAV.
     *
     * Public so the testing-mode scope reads recordings exactly the way the pipeline does
     * — a second reader that disagreed about, say, header size would show a signal the
     * pipeline never saw.
     */
    fun readPcm16AsDoubles(file: File): DoubleArray {
        val bytes = file.readBytes()
        if (bytes.size <= WavHeader.SIZE) return DoubleArray(0)
        val count = (bytes.size - WavHeader.SIZE) / 2
        val out = DoubleArray(count)
        var b = WavHeader.SIZE
        for (i in 0 until count) {
            out[i] = (((bytes[b + 1].toInt() shl 8) or (bytes[b].toInt() and 0xFF)).toShort()).toDouble()
            b += 2
        }
        return out
    }

    private fun writeCanonicalWav(destination: File, samples: ShortArray) {
        destination.parentFile?.mkdirs()
        val dataBytes = samples.size.toLong() * PcgSpec.BYTES_PER_SAMPLE
        FileOutputStream(destination).use { out ->
            out.write(
                WavHeader.toByteArray(
                    sampleRate = PcgSpec.SAMPLE_RATE,
                    channels = PcgSpec.CHANNELS,
                    bitsPerSample = PcgSpec.BITS_PER_SAMPLE,
                    audioDataBytes = dataBytes,
                )
            )
            val body = ByteArray(samples.size * 2)
            var b = 0
            for (s in samples) {
                val v = s.toInt()
                body[b] = (v and 0xFF).toByte()
                body[b + 1] = ((v shr 8) and 0xFF).toByte()
                b += 2
            }
            out.write(body)
        }
    }
}
