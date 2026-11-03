package com.capstone.stethoscope.analysis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartStatesTest {

    private val s = HeartStates(byteArrayOf(4, 4, 1, 1, 1, 2, 3, 3, 4), hop = 32, sampleRate = 2000)

    @Test
    fun `encode and decode round-trip`() {
        val back = HeartStates.decode(s.encode())!!
        assertEquals("2000/32/441112334", s.encode())
        assertArrayEquals(s.states, back.states)
        assertEquals(32, back.hop)
        assertEquals(2000, back.sampleRate)
    }

    @Test
    fun `malformed strings decode to null rather than crashing`() {
        assertNull(HeartStates.decode(null))
        assertNull(HeartStates.decode("2000/32"))
        assertNull(HeartStates.decode("2000/32/1250"))
        assertNull(HeartStates.decode("x/32/1234"))
    }

    @Test
    fun `frame times follow the MATLAB centres`() {
        // centres = 1:32:N, time = c / 2000
        assertEquals(1 / 2000.0, s.timeOf(0), 1e-12)
        assertEquals(33 / 2000.0, s.timeOf(1), 1e-12)
    }

    @Test
    fun `runs tile the recording in order`() {
        val r = s.runs()
        assertEquals(
            listOf(HeartPhase.DIASTOLE, HeartPhase.S1, HeartPhase.SYSTOLE, HeartPhase.S2, HeartPhase.DIASTOLE),
            r.map { it.first },
        )
        for (k in 1 until r.size) assertEquals(r[k - 1].third, r[k].second, 1e-12)
    }

    @Test
    fun `phase lookup by time`() {
        assertEquals(HeartPhase.S1, s.phaseAt(s.timeOf(3)))
        assertEquals(HeartPhase.S2, s.phaseAt(s.timeOf(6)))
        assertNull(s.phaseAt(10.0))
    }
}
