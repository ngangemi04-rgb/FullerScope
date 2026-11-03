package com.capstone.stethoscope.audio

import kotlin.math.abs
import kotlin.math.max

/** Little-endian signed 16-bit sample at byte offset [at]. */
internal fun readSample16(buffer: ByteArray, at: Int): Int =
    ((buffer[at + 1].toInt() shl 8) or (buffer[at].toInt() and 0xFF)).toShort().toInt()

/** Writes [value] as a little-endian signed 16-bit sample at byte offset [at]. */
internal fun writeSample16(buffer: ByteArray, at: Int, value: Int) {
    val clamped = value.coerceIn(-32_768, 32_767)
    buffer[at] = (clamped and 0xFF).toByte()
    buffer[at + 1] = ((clamped shr 8) and 0xFF).toByte()
}

/**
 * Turns raw PCM buffers into a peak magnitude for the live meter and scrolling waveform,
 * together with the auto-range [fullScale] that peak should be drawn against.
 *
 * Two corrections, both of which exist because we cannot assume anything about the
 * scale of the incoming signal:
 *
 *  - **DC removal.** A microcontroller sending unsigned offset-binary samples parks
 *    the signal far away from zero; measuring raw peaks would then report a constant
 *    near-full level regardless of what the patient's heart is doing.
 *  - **Auto-ranging.** A right-aligned 12-bit source only ever reaches a sixteenth of
 *    full scale, which would draw as a flat line. Tracking a decaying ceiling instead
 *    of assuming 32767 keeps the waveform legible whatever the hardware's output
 *    range turns out to be.
 *
 * ## Why this returns a raw peak rather than a 0..1 level
 *
 * The auto-range ceiling moves *while* the trace is on screen. If each bar were
 * normalized as it arrived and then stored, the bars already drawn would keep the scale
 * they were measured against, so one loud beat would rescale the newest bar and leave the
 * previous three seconds looking taller than they should — a trace with two different
 * y-axes on it, which is worse than no auto-ranging at all. Callers therefore hand
 * [WaveformWindow] the unnormalized peak plus the current [fullScale], and the window
 * rescales its whole history whenever the ceiling changes.
 *
 * This affects the *display* only. Samples written to disk are corrected separately
 * and exactly by [SignalConditioner], so what the algorithm receives is never guessed at.
 */
internal class LevelMeter {

    private var dc = 0f
    private var ceiling = MIN_CEILING

    /**
     * Auto-range ceiling the most recent [peak] was measured against — the value that
     * should map to the top of the plot.
     *
     * Read this *after* [peak], since measuring updates it.
     */
    val fullScale: Float get() = ceiling

    fun reset() {
        dc = 0f
        ceiling = MIN_CEILING
    }

    /**
     * DC-corrected peak magnitude of the first [length] bytes of [buffer], in sample
     * units. Also advances the auto-range ceiling, so [fullScale] is valid afterwards.
     */
    fun peak(buffer: ByteArray, length: Int): Float {
        if (length < 2) return 0f

        var sum = 0L
        var count = 0
        var i = 0
        while (i + 1 < length) {
            sum += readSample16(buffer, i)
            count++
            i += 2
        }
        if (count == 0) return 0f

        // Track the offset slowly so a real low-frequency heart sound isn't mistaken
        // for bias and subtracted away.
        dc += (sum.toFloat() / count - dc) * DC_TRACKING

        var peak = 0f
        i = 0
        while (i + 1 < length) {
            peak = max(peak, abs(readSample16(buffer, i) - dc))
            i += 2
        }

        ceiling = max(peak, max(ceiling * CEILING_DECAY, MIN_CEILING))
        return peak
    }

    /** Convenience for the numeric level meter, which has no history to keep consistent. */
    fun level(buffer: ByteArray, length: Int): Float =
        (peak(buffer, length) / ceiling).coerceIn(0f, 1f)

    private companion object {
        /** Per-buffer weight for the running DC estimate. */
        const val DC_TRACKING = 0.05f

        /** How fast the auto-range ceiling falls when the input gets quieter. */
        const val CEILING_DECAY = 0.995f

        /** Stops a silent input from auto-ranging its own noise floor up to full scale. */
        const val MIN_CEILING = 256f
    }
}
