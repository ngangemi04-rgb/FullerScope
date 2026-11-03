package com.capstone.stethoscope.analysis.pcg

import java.io.File

/**
 * The PCG (phonocardiogram) file format the diagnostic algorithm consumes:
 * **byte-compatible with the PhysioNet/CinC Challenge 2016 heart-sound dataset**.
 *
 * RIFF/WAVE, PCM (fmt tag 1), 16-bit signed, **2000 Hz**, mono, canonical 44-byte
 * header, little-endian.
 *
 * The rate is fixed deliberately. Whatever the stethoscope hardware or the phone
 * negotiates on the way in, [PcgPreprocessor] lands here, so the algorithm never has
 * to ask what rate it is being handed — a guarantee worth more than matching the
 * capture hardware.
 */
object PcgSpec {

    const val SAMPLE_RATE = 2_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    const val HEADER_SIZE = 44

    /**
     * Rate the signal is captured at, before decimation to [SAMPLE_RATE].
     *
     * 16 kHz is chosen, not inherited: it is an exact ÷8 to 2 kHz (so the decimation is a
     * short integer-ratio filter rather than a 20/441-style rational monster), it is a
     * rate every Android device will open, and it oversamples the RP2040's ~9-effective-bit
     * ADC by 8× — noise that averages down in the decimation filter and buys back real
     * resolution.
     */
    const val CAPTURE_SAMPLE_RATE = 16_000

    // ------------------------------------------------------------------------------
    /*
     * **The app does not filter.** (2026-09-23, at the algorithm team's request: they
     * handle all filtering on their side.)
     *
     * Nothing shapes the signal between the ADC and the file the classifier reads except
     * the anti-alias filter inside [Resampler], and that one is not a choice — decimating
     * 16 kHz to 2 kHz without it folds everything from 1 kHz to 8 kHz onto the heart
     * sounds, irreversibly. There is no 600 Hz lowpass any more, no DC removal and no
     * peak normalization: sample values reaching the algorithm are the ADC's own, resampled
     * and nothing else.
     *
     * What that costs, so nobody is surprised by it:
     *  - **Baseline wander and DC offset reach the file.** A 12-bit offset-binary source
     *    parks the signal near 2048 (or near 0 after the USB path's exact `(raw-2048) shl 4`
     *    centring) and it stays there.
     *  - **Absolute level is preserved**, which is the upside: two recordings are now
     *    comparable in loudness, where peak normalization used to destroy that.
     *  - **The classifier's own causal 25–400 Hz Butterworth inside `HeartSoundFeatures`
     *    still runs.** That is part of the ported MATLAB feature extractor, not of the
     *    app's signal chain — its coefficients are what the trained weights were fitted
     *    against.
     *
     * [Butterworth] still exists and is still tested; nothing in the pipeline calls it.
     */
    // ------------------------------------------------------------------------------

    /** Result of [verify]; mirrors the dict returned by `verify()` in `pcg_wav.py`. */
    data class Compliance(
        val compliant: Boolean,
        val sampleRate: Int,
        val bits: Int,
        val channels: Int,
        val frames: Int,
        val seconds: Double,
        /** Why it failed, empty when compliant. Beats a bare false. */
        val problems: List<String>,
    )

    /**
     * Checks [file] against the target spec by reading the header bytes directly,
     * the same way `verify()` does in the Python reference. Used by tests and worth
     * calling on real output when debugging an algorithm that rejects a file.
     */
    fun verify(file: File): Compliance {
        val problems = mutableListOf<String>()
        val head = ByteArray(HEADER_SIZE)
        val read = file.inputStream().use { it.read(head) }
        if (read < HEADER_SIZE) {
            return Compliance(false, 0, 0, 0, 0, 0.0, listOf("File shorter than a WAV header"))
        }

        fun ascii(from: Int, to: Int) = String(head, from, to - from, Charsets.US_ASCII)
        fun u16(at: Int) = (head[at].toInt() and 0xFF) or ((head[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = (head[at].toInt() and 0xFF) or
            ((head[at + 1].toInt() and 0xFF) shl 8) or
            ((head[at + 2].toInt() and 0xFF) shl 16) or
            ((head[at + 3].toInt() and 0xFF) shl 24)

        if (ascii(0, 4) != "RIFF") problems += "Missing RIFF magic"
        if (ascii(8, 12) != "WAVE") problems += "Missing WAVE magic"
        if (ascii(12, 16) != "fmt ") problems += "Missing fmt chunk"
        if (u32(16) != 16) problems += "fmt chunk size is ${u32(16)}, expected 16 (canonical PCM)"
        if (u16(20) != 1) problems += "Format tag is ${u16(20)}, expected 1 (PCM)"
        if (ascii(36, 40) != "data") problems += "data chunk not at offset 36"

        val channels = u16(22)
        val rate = u32(24)
        val bits = u16(34)
        if (channels != CHANNELS) problems += "$channels channels, expected $CHANNELS"
        if (rate != SAMPLE_RATE) problems += "$rate Hz, expected $SAMPLE_RATE"
        if (bits != BITS_PER_SAMPLE) problems += "$bits-bit, expected $BITS_PER_SAMPLE"

        val dataBytes = u32(40)
        val frames = dataBytes / (channels.coerceAtLeast(1) * (bits.coerceAtLeast(8) / 8))
        val actualDataBytes = file.length() - HEADER_SIZE
        if (dataBytes.toLong() != actualDataBytes) {
            problems += "data chunk claims $dataBytes bytes, file holds $actualDataBytes"
        }

        return Compliance(
            compliant = problems.isEmpty(),
            sampleRate = rate,
            bits = bits,
            channels = channels,
            frames = frames,
            seconds = if (rate > 0) frames.toDouble() / rate else 0.0,
            problems = problems,
        )
    }
}
