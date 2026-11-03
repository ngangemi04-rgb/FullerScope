package com.capstone.stethoscope.audio

import java.io.File
import kotlin.math.abs

/**
 * Measures how a freshly recorded WAV uses the 16-bit range, and reports it.
 *
 * ## This no longer changes the audio (2026-09-23)
 *
 * It used to: it subtracted the DC offset and applied a power-of-two gain, in place, to
 * the same file the classifier later reads. The algorithm team asked for the signal
 * unconditioned — see [com.capstone.stethoscope.analysis.pcg.PcgPreprocessor] — and a
 * silent ×16 here would have been exactly the kind of conditioning they meant, so [measure]
 * now only looks.
 *
 * The measurement is still worth having, and arguably worth more than the correction was:
 * the diagnostics screen can now tell you *that* the front end is right-aligned 12-bit or
 * parked on a DC offset, instead of quietly papering over it.
 *
 * Reverting is one line — restore the rewrite at the end of [measure] — but note what it
 * would mean: the analysis path would be DC-corrected and gained again.
 *
 * ## What the numbers mean
 *
 * Android's [android.media.AudioRecord] always hands us 16-bit PCM. When the sound
 * originates from a 12-bit ADC, the firmware has to fit 12 bits into that 16-bit
 * word, and there are two common ways to do it:
 *
 *  - **Left-aligned / scaled** — samples already span the full ±32767 range. Nothing
 *    to do; this pass leaves them alone (gain 1).
 *  - **Right-aligned** — samples only ever reach ±2047, using one sixteenth of the
 *    available range. The recording is ~24 dB quieter than it should be, the
 *    waveform is a flat line on screen, and the classifier sees a signal whose
 *    amplitude depends on wiring rather than on the patient.
 *
 * A third variant, **unsigned offset binary** (0..4095 centred on 2048), shows up as
 * an enormous DC offset that swamps the heart sounds entirely.
 *
 * [Stats.gain] is therefore the gain that *would* restore full scale, reported so the
 * number is visible, not applied.
 */
object SignalConditioner {

    /** Never amplify more than 16x: that is the full 12-bit → 16-bit correction. */
    private const val MAX_GAIN = 16

    /** Loudest value a signed 16-bit sample can hold. */
    private const val FULL_SCALE = 32_767

    /** Largest magnitude a 12-bit sample can reach. */
    private const val TWELVE_BIT_PEAK = 4_095

    /**
     * Below this peak there is no real signal (stethoscope unplugged, mic muted), and
     * amplifying would just make the noise floor loud and alarming.
     */
    private const val SILENCE_PEAK = 16

    data class Stats(
        /** Mean sample value. Large values imply offset-binary data. */
        val dcOffset: Int,
        /** Loudest sample, measured about the mean. */
        val peakBefore: Int,
        /**
         * Power-of-two gain that *would* bring [peakBefore] up to full scale. Reported
         * only — nothing applies it. 1 means the source already fills 16 bits.
         */
        val gain: Int,
        /** Number of samples inspected. */
        val sampleCount: Int,
    ) {
        /** Rough ADC width implied by the peak — 12 suggests a right-aligned 12-bit source. */
        val impliedBits: Int
            get() {
                if (peakBefore <= 0) return 0
                var bits = 1
                while ((1 shl bits) - 1 < peakBefore && bits < 16) bits++
                return bits + 1 // + sign bit
            }

        /** One-line summary for the diagnostics screen. */
        fun describe(): String = when {
            sampleCount == 0 -> "No audio captured."
            peakBefore < SILENCE_PEAK -> "Silent — peak $peakBefore. Check the stethoscope connection."
            gain > 1 ->
                "peak $peakBefore (~$impliedBits-bit), DC offset $dcOffset — " +
                    "x$gain of headroom unused, audio left untouched"
            else -> "peak $peakBefore (~$impliedBits-bit), DC offset $dcOffset, audio untouched"
        }

        companion object {
            val EMPTY = Stats(dcOffset = 0, peakBefore = 0, gain = 1, sampleCount = 0)
        }
    }

    /**
     * Reads [wav] and reports how it uses the 16-bit range. **The file is not modified.**
     */
    fun measure(wav: File): Stats {
        if (!wav.exists() || wav.length() <= WavHeader.SIZE) return Stats.EMPTY

        val bytes = wav.readBytes()
        val count = (bytes.size - WavHeader.SIZE) / 2
        if (count == 0) return Stats.EMPTY

        // Pass 1: mean (DC offset).
        var sum = 0L
        forEachSample(bytes, count) { sum += it }
        val dc = (sum / count).toInt()

        // Pass 2: peak once the offset is gone.
        var peak = 0
        forEachSample(bytes, count) { raw ->
            val magnitude = abs(raw - dc)
            if (magnitude > peak) peak = magnitude
        }

        // Deliberately no third pass. This is where the DC subtraction and the gain used
        // to be written back into the file; see the class doc.
        return Stats(dcOffset = dc, peakBefore = peak, gain = chooseGain(peak), sampleCount = count)
    }

    /**
     * Largest power of two that would bring [peak] up towards full scale, or 1 when the
     * signal already fills the range. Reported, not applied.
     *
     * The guard is deliberately narrow: a gain is only suggested when the loudest sample
     * in the entire clip still fits inside 12 bits. Over a 60-second auscultation that is
     * not a quiet patient, it is a right-aligned 12-bit ADC — so a number above 1 here
     * points at wiring, not at the chest.
     *
     * Powers of two only, so if it is ever applied again it is an exact bit shift back to
     * the ADC's true scale rather than an arbitrary float multiply.
     */
    private fun chooseGain(peak: Int): Int {
        if (peak < SILENCE_PEAK || peak > TWELVE_BIT_PEAK) return 1
        var gain = 1
        while (gain < MAX_GAIN && peak * gain * 2 <= FULL_SCALE) gain *= 2
        return gain
    }

    private inline fun forEachSample(bytes: ByteArray, count: Int, action: (Int) -> Unit) {
        var b = WavHeader.SIZE
        for (i in 0 until count) {
            action(readSample(bytes, b))
            b += 2
        }
    }

    /** Little-endian signed 16-bit read. */
    private fun readSample(bytes: ByteArray, at: Int): Int =
        ((bytes[at + 1].toInt() shl 8) or (bytes[at].toInt() and 0xFF)).toShort().toInt()
}
