package com.capstone.stethoscope.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * The live trace is display-only, but it is what a clinician positions the stethoscope
 * by, so it has to show heart-band content at its true size, hide the board's DC offset,
 * and not drown in hiss.
 */
class LivePcgWindowTest {

    private val rate = 16_000

    /** Feeds [seconds] of `offset + amp·sin(2πf t)` in 32 ms reads, like the monitor does. */
    private fun feed(w: LivePcgWindow, hz: Double, amp: Double, offset: Double = 0.0, seconds: Double = 5.0) {
        val total = (rate * seconds).toInt()
        val chunk = LivePcgWindow.READ_BYTES / 2
        var n = 0
        while (n < total) {
            val count = minOf(chunk, total - n)
            val buf = ByteArray(count * 2)
            for (k in 0 until count) {
                val t = (n + k).toDouble() / rate
                writeSample16(buf, k * 2, (offset + amp * sin(2 * PI * hz * t)).toInt())
            }
            w.push(buf, buf.size)
            n += count
        }
    }

    /** Peak |value| over the second half of the window, past the filters' start-up. */
    private fun settledPeak(w: LivePcgWindow): Float {
        val f = w.frame.value
        var peak = 0f
        for (i in f.columns / 2 until f.columns) peak = max(peak, max(abs(f.mins[i]), abs(f.maxs[i])))
        return peak
    }

    @Test
    fun `heart-band tone is drawn at close to its real amplitude`() {
        val w = LivePcgWindow().apply { reset(rate) }
        feed(w, hz = 100.0, amp = 1000.0)
        val peak = settledPeak(w)
        assertTrue("100 Hz tone peak $peak should be within ~10% of 1000", peak in 880f..1050f)
    }

    @Test
    fun `board DC offset does not show as a displaced trace`() {
        val w = LivePcgWindow().apply { reset(rate) }
        feed(w, hz = 100.0, amp = 1000.0, offset = -2000.0)
        val f = w.frame.value
        var mid = 0f
        for (i in f.columns / 2 until f.columns) mid += (f.mins[i] + f.maxs[i]) / 2f
        mid /= (f.columns - f.columns / 2)
        assertEquals("trace should be centred on zero", 0f, mid, 30f)
    }

    @Test
    fun `hiss well above the PCG band is strongly attenuated`() {
        val w = LivePcgWindow().apply { reset(rate) }
        feed(w, hz = 4000.0, amp = 1000.0)
        assertTrue("4 kHz should be well under a tenth of input", settledPeak(w) < 100f)
    }

    @Test
    fun `window spans four seconds and scale never collapses below the floor`() {
        val w = LivePcgWindow(columns = 320).apply { reset(rate) }
        assertEquals(320, w.frame.value.columns)
        feed(w, hz = 100.0, amp = 0.0, seconds = 1.0)
        assertEquals(LivePcgWindow.MIN_FULL_SCALE, w.frame.value.fullScale, 0f)
    }
}
