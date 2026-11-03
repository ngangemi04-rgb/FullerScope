package com.capstone.stethoscope.audio.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the USB wire protocol.
 *
 * USB bulk reads respect no frame boundaries, so every awkward case here is one that
 * happens in practice on a real link: a read landing mid-header, joining a stream already
 * in progress, or losing a frame to backpressure. Silent mis-parsing would surface as
 * clicks and discontinuities in the audio, which a rhythm classifier would read as
 * arrhythmia.
 */
class StethoscopeFrameParserTest {

    /** Builds one wire frame with the given raw 12-bit samples. */
    private fun frame(
        seq: Int,
        samples: IntArray,
        rate: Int = 8_000,
        flags: Int = 0,
        magic: ByteArray = StethoscopeFrameParser.MAGIC,
    ): ByteArray {
        val out = ByteArray(StethoscopeFrameParser.HEADER_LEN + samples.size * 2)
        magic.copyInto(out, 0)
        fun put16(at: Int, v: Int) {
            out[at] = (v and 0xFF).toByte()
            out[at + 1] = ((v shr 8) and 0xFF).toByte()
        }
        put16(4, seq)
        put16(6, samples.size)
        put16(8, rate and 0xFFFF)
        put16(10, (rate shr 16) and 0xFFFF)
        put16(12, flags)
        put16(14, 0)
        samples.forEachIndexed { i, s -> put16(StethoscopeFrameParser.HEADER_LEN + i * 2, s) }
        return out
    }

    /** Decodes little-endian int16 PCM back to a list for comparison. */
    private fun pcm(bytes: ByteArray): List<Int> =
        (bytes.indices step 2).map {
            ((bytes[it + 1].toInt() shl 8) or (bytes[it].toInt() and 0xFF)).toShort().toInt()
        }

    // --- happy path ---

    @Test
    fun `decodes a single frame`() {
        val p = StethoscopeFrameParser()
        val f = frame(seq = 0, samples = intArrayOf(2048, 2048, 2048))
        val out = p.decode(f, f.size)

        assertEquals(listOf(0, 0, 0), pcm(out))
        assertEquals(1, p.framesDecoded)
        assertEquals(8_000, p.sampleRate)
        assertEquals(0, p.droppedFrames)
    }

    @Test
    fun `centres and scales 12-bit samples onto the full 16-bit range`() {
        val p = StethoscopeFrameParser()
        // 2048 is mid-scale -> 0; the rails map to near full scale.
        val f = frame(0, intArrayOf(2048, 4095, 0, 3048, 1048))
        val out = pcm(p.decode(f, f.size))

        assertEquals(0, out[0])
        assertEquals((4095 - 2048) shl 4, out[1])   // +32752
        assertEquals((0 - 2048) shl 4, out[2])      // -32768
        assertEquals(16_000, out[3])
        assertEquals(-16_000, out[4])
        // Everything must stay inside int16 without wrapping.
        assertTrue(out.all { it in -32_768..32_767 })
    }

    @Test
    fun `decodes several frames from one read`() {
        val p = StethoscopeFrameParser()
        val a = frame(0, intArrayOf(2148, 2248))
        val b = frame(1, intArrayOf(2348, 2448))
        val both = a + b
        val out = pcm(p.decode(both, both.size))

        assertEquals(4, out.size)
        assertEquals(2, p.framesDecoded)
        assertEquals(0, p.droppedFrames)
    }

    // --- fragmentation ---

    @Test
    fun `carries a frame split across two reads`() {
        val p = StethoscopeFrameParser()
        val f = frame(0, intArrayOf(2148, 2248, 2348))
        val cut = 9 // mid-header

        val first = p.decode(f.copyOfRange(0, cut), cut)
        assertEquals("nothing should decode from a partial header", 0, first.size)

        val rest = f.copyOfRange(cut, f.size)
        val second = pcm(p.decode(rest, rest.size))
        assertEquals(3, second.size)
        assertEquals(1, p.framesDecoded)
    }

    @Test
    fun `carries a frame split mid-payload`() {
        val p = StethoscopeFrameParser()
        val f = frame(0, intArrayOf(2148, 2248, 2348, 2448))
        val cut = StethoscopeFrameParser.HEADER_LEN + 4 // two samples in

        assertEquals(0, p.decode(f.copyOfRange(0, cut), cut).size)
        val rest = f.copyOfRange(cut, f.size)
        assertEquals(4, pcm(p.decode(rest, rest.size)).size)
    }

    @Test
    fun `handles a byte-at-a-time dribble`() {
        val p = StethoscopeFrameParser()
        val f = frame(0, intArrayOf(2148, 2248))
        var total = 0
        for (b in f) {
            total += p.decode(byteArrayOf(b), 1).size
        }
        assertEquals(4, total) // 2 samples * 2 bytes
        assertEquals(1, p.framesDecoded)
    }

    // --- resynchronisation ---

    @Test
    fun `resynchronises after leading garbage`() {
        val p = StethoscopeFrameParser()
        // Joining a stream mid-frame, or the MicroPython banner still in the buffer.
        val junk = "MicroPython v1.28.0 on RP2040\r\n>>> ".toByteArray()
        val f = frame(0, intArrayOf(2148, 2248))
        val data = junk + f

        val out = pcm(p.decode(data, data.size))
        assertEquals(2, out.size)
        assertEquals(1, p.framesDecoded)
        assertTrue("should have reported skipped bytes", p.resyncBytes >= junk.size)
    }

    @Test
    fun `rejects a false magic in the payload`() {
        val p = StethoscopeFrameParser()
        // A frame whose samples happen to spell "STH1" (0x5453, 0x3148).
        val f = frame(0, intArrayOf(0x453, 0x148, 2048))
        val out = p.decode(f, f.size)

        // The real frame must still decode; the embedded magic must not derail it.
        assertEquals(1, p.framesDecoded)
        assertEquals(3, pcm(out).size)
    }

    @Test
    fun `ignores a header with an implausible sample count`() {
        val p = StethoscopeFrameParser()
        val bad = frame(0, IntArray(0)).copyOf(StethoscopeFrameParser.HEADER_LEN)
        // nsamples = 0 is invalid; a real frame follows.
        val good = frame(1, intArrayOf(2148))
        val data = bad + good

        val out = pcm(p.decode(data, data.size))
        assertEquals(1, out.size)
        assertEquals(1, p.framesDecoded)
    }

    @Test
    fun `ignores a header with an implausible sample rate`() {
        val p = StethoscopeFrameParser()
        val bad = frame(0, intArrayOf(2048), rate = 1)   // below the sanity floor
        val good = frame(1, intArrayOf(2148), rate = 8_000)
        val data = bad + good

        pcm(p.decode(data, data.size))
        assertEquals("only the plausible frame should count", 1, p.framesDecoded)
        assertEquals(8_000, p.sampleRate)
    }

    // --- loss reporting ---

    @Test
    fun `counts frames missing from the sequence`() {
        val p = StethoscopeFrameParser()
        val a = frame(10, intArrayOf(2048))
        val b = frame(14, intArrayOf(2048)) // 11, 12, 13 lost
        p.decode(a, a.size)
        p.decode(b, b.size)

        assertEquals(3, p.droppedFrames)
        assertEquals(2, p.framesDecoded)
    }

    @Test
    fun `sequence wraparound is not counted as loss`() {
        val p = StethoscopeFrameParser()
        val a = frame(0xFFFF, intArrayOf(2048))
        val b = frame(0x0000, intArrayOf(2048))
        p.decode(a, a.size)
        p.decode(b, b.size)

        assertEquals(0, p.droppedFrames)
    }

    @Test
    fun `reports firmware overrun flags`() {
        val p = StethoscopeFrameParser()
        val clean = frame(0, intArrayOf(2048))
        val over = frame(1, intArrayOf(2048), flags = StethoscopeFrameParser.FLAG_OVERRUN)
        p.decode(clean, clean.size)
        p.decode(over, over.size)

        assertEquals(1, p.overrunFrames)
    }

    // --- state ---

    @Test
    fun `reset clears buffered bytes and sequence state`() {
        val p = StethoscopeFrameParser()
        val f = frame(0, intArrayOf(2048, 2048))
        p.decode(f.copyOfRange(0, 8), 8) // leave a partial header pending
        p.reset()

        // A later frame with a distant sequence number must not report a huge gap.
        val next = frame(500, intArrayOf(2048))
        assertEquals(1, pcm(p.decode(next, next.size)).size)
        assertEquals(0, p.droppedFrames)
    }

    @Test
    fun `empty input is harmless`() {
        val p = StethoscopeFrameParser()
        assertArrayEquals(ByteArray(0), p.decode(ByteArray(0), 0))
        assertEquals(0, p.framesDecoded)
    }

    @Test
    fun `sustained stream decodes without loss`() {
        // Mimics a real link: 512-sample frames arriving in oddly sized chunks.
        val p = StethoscopeFrameParser()
        val samples = IntArray(512) { 2048 + (it % 200) - 100 }
        val stream = (0 until 20).fold(ByteArray(0)) { acc, i -> acc + frame(i, samples) }

        var decoded = 0
        var offset = 0
        val sizes = intArrayOf(1, 7, 63, 4096, 511, 1040, 3)
        var si = 0
        while (offset < stream.size) {
            val n = minOf(sizes[si++ % sizes.size], stream.size - offset)
            val chunk = stream.copyOfRange(offset, offset + n)
            decoded += p.decode(chunk, n).size
            offset += n
        }

        assertEquals(20, p.framesDecoded)
        assertEquals(0, p.droppedFrames)
        assertEquals(20 * 512 * 2, decoded)
    }
}
