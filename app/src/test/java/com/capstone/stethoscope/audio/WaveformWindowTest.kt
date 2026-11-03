package com.capstone.stethoscope.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the behaviour the live plot was changed for: **a rescale applies to the whole
 * trace, not just the newest bar.**
 *
 * The old version stored already-normalized values, so every bar kept whatever scale was
 * current when it was pushed. That is easy to reintroduce by "simplifying" push() back to
 * a single argument, and the symptom on a phone is subtle — the trace just looks wrong in
 * a way nobody can quite point at — so it is worth an explicit test.
 */
class WaveformWindowTest {

    @Test
    fun `raising the full scale lowers every earlier bar proportionally`() {
        val w = WaveformWindow(capacity = 4)

        // Three quiet buffers measured against a ceiling of 1000.
        repeat(3) { w.push(magnitude = 500f, fullScale = 1_000f) }
        assertTrue(w.values.value.takeLast(3).all { it == 0.5f })

        // A loud beat pushes the auto-range ceiling to 2000. Every bar must now be drawn
        // against 2000 — the earlier ones included.
        w.push(magnitude = 2_000f, fullScale = 2_000f)

        val values = w.values.value
        assertEquals(4, values.size)
        // The three 500-magnitude bars: 500/2000.
        assertEquals(0.25f, values[0], 1e-6f)
        assertEquals(0.25f, values[1], 1e-6f)
        assertEquals(0.25f, values[2], 1e-6f)
        assertEquals(1.0f, values[3], 1e-6f)
    }

    @Test
    fun `lowering the full scale raises every earlier bar`() {
        val w = WaveformWindow(capacity = 3)
        repeat(3) { w.push(magnitude = 250f, fullScale = 1_000f) }
        assertTrue(w.values.value.all { it == 0.25f })

        // Ceiling decays as the input gets quieter; the history follows it up.
        w.push(magnitude = 250f, fullScale = 500f)
        assertTrue(w.values.value.all { it == 0.5f })
    }

    @Test
    fun `values stay within the drawable range`() {
        val w = WaveformWindow(capacity = 3)
        // A magnitude above the stated full scale can happen for one buffer, since the
        // meter raises its ceiling from the same measurement. It must clamp, not overflow
        // the plot.
        w.push(magnitude = 5_000f, fullScale = 1_000f)
        w.push(magnitude = -20f, fullScale = 1_000f)
        assertTrue(w.values.value.all { it in 0f..1f })
    }

    @Test
    fun `the window scrolls and keeps its width`() {
        val w = WaveformWindow(capacity = 3)
        w.push(1f, 10f)
        w.push(2f, 10f)
        w.push(3f, 10f)
        w.push(4f, 10f)

        val values = w.values.value
        assertEquals(3, values.size)
        // Oldest dropped off the left; newest is on the right.
        assertEquals(0.2f, values[0], 1e-6f)
        assertEquals(0.4f, values[2], 1e-6f)
    }

    @Test
    fun `clear resets to a flat baseline`() {
        val w = WaveformWindow(capacity = 4)
        w.push(900f, 1_000f)
        w.clear()
        assertEquals(4, w.values.value.size)
        assertTrue(w.values.value.all { it == 0f })
    }

    @Test
    fun `a zero full scale cannot produce a divide by zero`() {
        val w = WaveformWindow(capacity = 2)
        // Reachable in principle if a meter ever reported silence as its own ceiling.
        w.push(0f, 0f)
        assertTrue(w.values.value.all { it.isFinite() })
    }
}
