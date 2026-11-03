package com.capstone.stethoscope.audio.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the two pieces of parsing that decide whether the app reflashes the board.
 *
 * Both are worth pinning because both failure modes are silent and bad in opposite
 * directions: a source-version parse that picks up a mention of `FW_VERSION` from the
 * module docstring would compare against nonsense and reflash on every plug-in, and a
 * banner parse that returned null for a healthy board would do the same.
 */
class StethoscopeFirmwareTest {

    @Test
    fun `reads the version out of firmware source`() {
        val source = """
            FW_VERSION = "2.0.0"
            SAMPLE_RATE = 16000
        """.trimIndent()
        assertEquals("2.0.0", StethoscopeFirmware.parseSourceVersion(source))
    }

    @Test
    fun `ignores mentions of FW_VERSION inside the docstring`() {
        // This is the real file's shape: the module docstring talks about FW_VERSION
        // several times before the assignment appears. Anchoring to the start of a line
        // and requiring '=' is what keeps those out.
        val source = """
            ""${'"'}
            Stethoscope firmware.

              * FW_VERSION below is parsed out of the source text by the app.
              * The banner must keep printing "fw=<FW_VERSION>".
            ""${'"'}

            FW_VERSION = "3.1"
        """.trimIndent()
        assertEquals("3.1", StethoscopeFirmware.parseSourceVersion(source))
    }

    @Test
    fun `accepts single quotes and loose spacing`() {
        assertEquals("1.2.3", StethoscopeFirmware.parseSourceVersion("FW_VERSION='1.2.3'"))
        assertEquals("1.2.3", StethoscopeFirmware.parseSourceVersion("FW_VERSION   =   \"1.2.3\""))
    }

    @Test
    fun `source with no version declaration parses as null`() {
        assertNull(StethoscopeFirmware.parseSourceVersion("SAMPLE_RATE = 16000"))
    }

    @Test
    fun `reads version and rate out of the running firmware's banner`() {
        val banner =
            "STETHOSCOPE ready fw=2.0.0 proto=STH1 rate=16000 frame=1024 bits=12 align=right pin=GP26"
        assertEquals("2.0.0", StethoscopeFirmware.parseBannerVersion(banner))
        assertEquals(16_000, StethoscopeFirmware.parseBannerRate(banner))
    }

    @Test
    fun `a bare MicroPython REPL banner is not a stethoscope`() {
        // What the board actually says with no firmware installed. Reporting a version
        // here would mean never flashing an empty board.
        val repl = "MicroPython v1.28.0 on 2026-01-01; Raspberry Pi Pico with RP2040"
        assertNull(StethoscopeFirmware.parseBannerVersion(repl))
    }

    @Test
    fun `an old banner with no fw field parses as null so the board gets reflashed`() {
        // Version 1 of the firmware printed no fw= field at all. It must read as
        // "unknown", not as "matches", or a v1 board would keep streaming 8 kHz forever.
        val old = "STETHOSCOPE ready proto=STH1 rate=8000 frame=512 bits=12 align=right pin=GP26"
        assertNull(StethoscopeFirmware.parseBannerVersion(old))
        assertEquals(8_000, StethoscopeFirmware.parseBannerRate(old))
    }

    @Test
    fun `payload checksum covers every byte unsigned`() {
        // Bytes above 0x7F are negative in a Kotlin ByteArray; summing them as signed
        // would disagree with MicroPython's sum(), and every flash would fail
        // verification.
        val payload = StethoscopeFirmware.Payload(
            version = "test",
            source = byteArrayOf(0x00, 0x7F, 0xFF.toByte(), 0x80.toByte()),
        )
        assertEquals(4, payload.sizeBytes)
        assertEquals(0L + 0 + 127 + 255 + 128, payload.byteSum)
    }
}
