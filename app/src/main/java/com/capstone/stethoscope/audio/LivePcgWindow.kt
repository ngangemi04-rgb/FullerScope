package com.capstone.stethoscope.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * One frame of the live phonocardiogram: per-column min/max of the last [WINDOW_SECONDS]
 * of signal, oldest column first, plus the value that maps to the top of the plot.
 *
 * Deliberately not a data class: StateFlow compares with equals(), and each frame must
 * count as new even if two happen to hold identical numbers.
 */
class LivePcg(val mins: FloatArray, val maxs: FloatArray, val fullScale: Float) {
    val columns: Int get() = mins.size

    companion object {
        val EMPTY = LivePcg(FloatArray(0), FloatArray(0), 1f)
    }
}

/**
 * Rolling window behind the live PCG trace on the Listen screen.
 *
 * Replaces the old one-bar-per-buffer loudness envelope, which had two problems: it was
 * not a PCG (no S1/S2 shape, just a peak level), and its refresh rate was whatever size
 * the phone's audio buffer happened to be — a fraction of a second on some phones,
 * which is what made the trace feel laggy. Here every sample lands in a column, and the
 * caller emits a frame per small read, so the scroll is smooth on any phone.
 *
 * ## Display-only conditioning
 *
 * Samples pass through a causal 20–400 Hz band-pass before being drawn: DC/baseline
 * removal (the USB board idles off-centre) and a low-pass so broadband hiss does not
 * paint the trace as a solid band. This is the conventional PCG display band. It touches
 * **only what is drawn** — the recorder writes the raw samples to the WAV before they
 * reach this class, so the algorithm still receives the capture unaltered.
 *
 * ## Whole-trace rescaling
 *
 * Columns are stored in raw sample units and [LivePcg.fullScale] is recomputed over the
 * whole window on every frame, so a rescale moves the entire trace together — the same
 * rule [LevelMeter] exists to keep (never two y-axes on one plot).
 */
class LivePcgWindow(private val columns: Int = DEFAULT_COLUMNS) {

    private val mins = FloatArray(columns)
    private val maxs = FloatArray(columns)
    private var head = 0 // next column to write

    private var samplesPerColumn = 1
    private var inColumn = 0
    private var colMin = Float.POSITIVE_INFINITY
    private var colMax = Float.NEGATIVE_INFINITY

    // Filter state: one-pole high-pass, then two one-pole low-passes.
    private var hpAlpha = 0f
    private var lpAlpha = 0f
    private var prevX = 0f
    private var hp = 0f
    private var lp1 = 0f
    private var lp2 = 0f
    private var primed = false

    private var ceiling = MIN_FULL_SCALE

    private val _frame = MutableStateFlow(LivePcg.EMPTY)
    val frame: StateFlow<LivePcg> = _frame.asStateFlow()

    /** Clears the trace and sets the rate subsequent samples arrive at. */
    @Synchronized
    fun reset(sampleRate: Int) {
        val rate = sampleRate.coerceAtLeast(1)
        samplesPerColumn = (rate * WINDOW_SECONDS / columns).toInt().coerceAtLeast(1)
        hpAlpha = exp(-2.0 * PI * HIGHPASS_HZ / rate).toFloat()
        lpAlpha = (1.0 - exp(-2.0 * PI * LOWPASS_HZ / rate)).toFloat()
        mins.fill(0f)
        maxs.fill(0f)
        head = 0
        inColumn = 0
        colMin = Float.POSITIVE_INFINITY
        colMax = Float.NEGATIVE_INFINITY
        prevX = 0f; hp = 0f; lp1 = 0f; lp2 = 0f
        primed = false
        ceiling = MIN_FULL_SCALE
        emit()
    }

    /** Adds [length] bytes of 16-bit little-endian mono PCM and publishes a new frame. */
    @Synchronized
    fun push(buffer: ByteArray, length: Int) {
        var i = 0
        while (i + 1 < length) {
            addSample(readSample16(buffer, i).toFloat())
            i += 2
        }
        emit()
    }

    private fun addSample(x: Float) {
        if (!primed) {
            // Start the high-pass at the first sample, or a large DC offset would ring
            // through as a giant initial transient that sets the scale for 4 seconds.
            prevX = x
            primed = true
        }
        hp = hpAlpha * (hp + x - prevX)
        prevX = x
        lp1 += lpAlpha * (hp - lp1)
        lp2 += lpAlpha * (lp1 - lp2)
        val y = lp2

        if (y < colMin) colMin = y
        if (y > colMax) colMax = y
        if (++inColumn >= samplesPerColumn) {
            mins[head] = colMin
            maxs[head] = colMax
            head = (head + 1) % columns
            inColumn = 0
            colMin = Float.POSITIVE_INFINITY
            colMax = Float.NEGATIVE_INFINITY
        }
    }

    private fun emit() {
        val outMin = FloatArray(columns)
        val outMax = FloatArray(columns)
        var peak = 0f
        for (k in 0 until columns) {
            val src = (head + k) % columns
            outMin[k] = mins[src]
            outMax[k] = maxs[src]
            peak = max(peak, max(abs(mins[src]), abs(maxs[src])))
        }
        // Rise instantly, fall slowly, so the trace does not pump as beats scroll out.
        ceiling = max(peak, max(ceiling * CEILING_DECAY, MIN_FULL_SCALE))
        _frame.value = LivePcg(outMin, outMax, ceiling)
    }

    companion object {
        /** 120 points a second: fine enough that the line follows S1/S2, not a staircase. */
        const val DEFAULT_COLUMNS = 480
        const val WINDOW_SECONDS = 4.0
        const val HIGHPASS_HZ = 20.0
        const val LOWPASS_HZ = 400.0

        /** Per-frame decay of the auto-range ceiling (~30 frames/s → halves in ~2 s). */
        const val CEILING_DECAY = 0.988f

        /** Keeps a silent input from auto-ranging its own noise floor up to full scale. */
        const val MIN_FULL_SCALE = 64f

        /**
         * Bytes per capture read while a live trace is showing: 512 samples, 32 ms at
         * 16 kHz, i.e. ~30 frames a second. Fixed rather than derived from the phone's
         * audio buffer size, which is what made the old trace's refresh rate vary.
         */
        const val READ_BYTES = 1024
    }
}
