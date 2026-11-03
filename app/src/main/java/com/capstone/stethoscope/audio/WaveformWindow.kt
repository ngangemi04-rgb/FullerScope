package com.capstone.stethoscope.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A fixed-width rolling window of recent amplitude values that drives a scrolling
 * waveform. Newest sample is pushed at the right; the oldest drops off the left,
 * producing the leftward scroll. Pre-filled with zeros so the display spans the
 * full width immediately.
 *
 * ## Rescaling applies to the whole trace
 *
 * Magnitudes are stored **unnormalized**, in raw sample units, alongside the current
 * full-scale reference from [LevelMeter]. [values] is derived from both, so when the
 * auto-range ceiling moves, every bar on screen moves with it.
 *
 * Storing pre-normalized values instead — which is what this did originally — meant a
 * rescale only affected bars pushed after it. The trace then carried a mix of scales:
 * the moment a loud beat lifted the ceiling, the three seconds of quiet history behind it
 * stayed drawn against the old, smaller reference and so appeared far louder than the
 * beat that had just rescaled everything. An axis that only applies to the right-hand
 * edge of a plot is a misleading plot, not a partially-updated one.
 */
class WaveformWindow(private val capacity: Int = 96) {

    /** Raw DC-corrected peak magnitudes, oldest first. */
    private val magnitudes = ArrayDeque<Float>(capacity).apply {
        repeat(capacity) { add(0f) }
    }

    /** Magnitude that maps to the top of the plot. Never zero, so division is safe. */
    private var fullScale = 1f

    private val _values = MutableStateFlow(List(capacity) { 0f })
    val values: StateFlow<List<Float>> = _values.asStateFlow()

    /**
     * Appends [magnitude] (raw sample units) and adopts [fullScale] as the reference for
     * the *entire* window.
     */
    @Synchronized
    fun push(magnitude: Float, fullScale: Float) {
        if (magnitudes.size >= capacity) magnitudes.removeFirst()
        magnitudes.addLast(magnitude.coerceAtLeast(0f))
        this.fullScale = fullScale.coerceAtLeast(1f)
        emit()
    }

    @Synchronized
    fun clear() {
        magnitudes.clear()
        repeat(capacity) { magnitudes.addLast(0f) }
        fullScale = 1f
        emit()
    }

    private fun emit() {
        val scale = fullScale
        _values.value = magnitudes.map { (it / scale).coerceIn(0f, 1f) }
    }
}
