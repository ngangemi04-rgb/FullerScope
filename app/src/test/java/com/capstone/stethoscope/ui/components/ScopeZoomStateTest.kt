package com.capstone.stethoscope.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the scope's zoom arithmetic, which is the part of the plot that is easy to get
 * subtly wrong and impossible to eyeball.
 *
 * Two properties carry the whole feel of the gesture: the window never escapes the
 * domain, and whatever is under the fingers stays under the fingers while pinching. Get
 * the second wrong and the plot slides away from you exactly when you are trying to read
 * a cutoff off it.
 */
class ScopeZoomStateTest {

    @Test
    fun `unzoomed shows the whole domain`() {
        val (lo, hi) = ScopeZoomState().window()
        assertEquals(0f, lo, 1e-6f)
        assertEquals(1f, hi, 1e-6f)
    }

    @Test
    fun `zooming about the centre keeps the centre`() {
        val z = ScopeZoomState()
        z.zoomAbout(factor = 2f, focusFraction = 0.5f)

        val (lo, hi) = z.window()
        assertEquals(0.25f, lo, 1e-5f)
        assertEquals(0.75f, hi, 1e-5f)
        assertEquals(2f, z.scale, 1e-5f)
    }

    @Test
    fun `zooming holds the point under the fingers in place`() {
        val z = ScopeZoomState()
        // Pinch at 25% across the plot, which unzoomed is domain position 0.25.
        z.zoomAbout(factor = 4f, focusFraction = 0.25f)

        val (lo, hi) = z.window()
        // That domain point must still sit at 25% across the visible window.
        assertEquals(0.25f, lo + 0.25f * (hi - lo), 1e-4f)
    }

    @Test
    fun `the window never escapes the domain`() {
        val z = ScopeZoomState()
        z.zoomAbout(factor = 8f, focusFraction = 0f) // pinch hard at the very left edge
        var (lo, hi) = z.window()
        assertTrue("lo was $lo", lo >= -1e-5f)
        assertTrue("hi was $hi", hi <= 1f + 1e-5f)

        // And panning far past the end clamps rather than scrolling into nothing.
        repeat(20) { z.pan(-5f) }
        val w = z.window()
        lo = w.first
        hi = w.second
        assertEquals(1f, hi, 1e-4f)
        assertTrue(lo >= 0f)
    }

    @Test
    fun `cannot zoom out past the full domain`() {
        val z = ScopeZoomState()
        z.zoomAbout(factor = 0.1f, focusFraction = 0.5f)
        assertEquals(1f, z.scale, 1e-6f)
        assertTrue(!z.isZoomed)
    }

    @Test
    fun `zoom is capped so the window cannot collapse to nothing`() {
        val z = ScopeZoomState(maxScale = 100f)
        repeat(20) { z.zoomAbout(factor = 4f, focusFraction = 0.5f) }
        assertEquals(100f, z.scale, 1e-4f)
        val (lo, hi) = z.window()
        assertTrue("Window collapsed to ${hi - lo}", hi - lo > 0f)
    }

    @Test
    fun `panning moves by a fraction of the visible window, not the domain`() {
        val z = ScopeZoomState()
        z.zoomAbout(factor = 4f, focusFraction = 0.5f) // window is 0.375..0.625
        val before = z.window()

        // Dragging the content half a screen to the right moves the window half a screen
        // to the left — a quarter-domain window, so 0.125 of the domain.
        z.pan(0.5f)
        val after = z.window()
        assertEquals(before.first - 0.125f, after.first, 1e-4f)
    }

    @Test
    fun `reset returns to the full domain`() {
        val z = ScopeZoomState()
        z.zoomAbout(factor = 20f, focusFraction = 0.8f)
        assertTrue(z.isZoomed)

        z.reset()
        assertTrue(!z.isZoomed)
        val (lo, hi) = z.window()
        assertEquals(0f, lo, 1e-6f)
        assertEquals(1f, hi, 1e-6f)
    }
}
