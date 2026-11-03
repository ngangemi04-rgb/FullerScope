package com.capstone.stethoscope.analysis.segmenter

import com.capstone.stethoscope.analysis.HeartStates

/**
 * What a segmented plot draws, on screen or in the PDF: the signal as the segmenter saw
 * it ([HeartSoundSegmenter.preprocess] — 2 kHz, bandpassed, normalised) and the phase of
 * each frame.
 */
class SegmentedSignal(val signal: DoubleArray, val sampleRate: Int, val states: HeartStates) {
    val durationSec: Double get() = signal.size / sampleRate.toDouble()
}
