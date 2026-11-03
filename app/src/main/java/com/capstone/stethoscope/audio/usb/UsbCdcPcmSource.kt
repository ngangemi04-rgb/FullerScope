package com.capstone.stethoscope.audio.usb

import com.capstone.stethoscope.audio.PcmSource

/**
 * [PcmSource] that reads from the stethoscope over USB CDC.
 *
 * This is the real capture path. It bypasses Android's audio framework entirely, which
 * turns out to be an advantage rather than a workaround: there is no HAL in the way to
 * refuse an unusual sample rate or silently resample, so the app receives exactly the
 * samples the ADC produced at exactly the rate the firmware chose.
 *
 * The firmware idles until told to stream, so [start] sends the start command and waits
 * for the first frame before returning — that way a failure surfaces immediately rather
 * than as a recording full of silence.
 */
class UsbCdcPcmSource(
    private val stethoscope: UsbStethoscope,
    /** Rate to assume until the first frame declares the real one. */
    private val fallbackRate: Int,
) : PcmSource {

    private val parser = StethoscopeFrameParser()
    private var connection: UsbCdcConnection? = null
    private var scratch: ByteArray = ByteArray(0)

    /** Decoded PCM waiting to be handed to the caller. */
    private var pcm: ByteArray = ByteArray(0)
    private var pcmLen = 0
    private var pcmRead = 0

    override var sampleRate: Int = fallbackRate
        private set

    override val label: String get() = "USB stethoscope (CDC)"

    override val isStethoscope: Boolean get() = true

    /** Frames the firmware or the link lost, for the diagnostics screen. */
    val droppedFrames: Long get() = parser.droppedFrames
    val overrunFrames: Long get() = parser.overrunFrames
    val framesDecoded: Long get() = parser.framesDecoded

    override val diagnostics: String
        get() = buildString {
            append("USB: ${parser.framesDecoded} frames")
            append(", ${parser.droppedFrames} dropped")
            append(", ${parser.overrunFrames} ADC overruns")
            if (parser.resyncBytes > 0) append(", ${parser.resyncBytes} bytes resynced")
            if (parser.droppedFrames > 0 || parser.overrunFrames > 0) {
                append(" — SAMPLES WERE LOST, treat this recording with suspicion")
            }
        }

    override fun start() {
        val conn = stethoscope.connect().getOrElse { throw it }
        connection = conn
        parser.reset()
        pcmLen = 0
        pcmRead = 0
        scratch = ByteArray(READ_CHUNK.coerceAtLeast(conn.readPacketSize))

        // Clear anything the firmware buffered before we attached, so the first frame we
        // parse is fresh rather than seconds stale.
        repeat(DRAIN_READS) { conn.read(scratch, timeoutMs = 20) }
        parser.reset()

        if (!conn.write(CMD_START)) {
            close()
            error("Could not send the start command to the stethoscope.")
        }

        // Wait for real data before declaring success.
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (pump() > 0) {
                sampleRate = parser.sampleRate.takeIf { it > 0 } ?: fallbackRate
                return
            }
        }
        close()
        error(
            "The stethoscope is connected but sent no data. Check that main.py is " +
                "installed on the board and that it reports STETHOSCOPE ready."
        )
    }

    override fun read(dest: ByteArray, offset: Int, length: Int): Int {
        if (connection == null) return -1
        if (available() == 0 && pump() < 0) return -1
        val n = minOf(length, available())
        if (n <= 0) return 0
        System.arraycopy(pcm, pcmRead, dest, offset, n)
        pcmRead += n
        if (pcmRead >= pcmLen) {
            pcmRead = 0
            pcmLen = 0
        }
        return n
    }

    override fun stop() {
        runCatching { connection?.write(CMD_STOP) }
    }

    override fun close() {
        stop()
        runCatching { connection?.close() }
        connection = null
    }

    private fun available() = pcmLen - pcmRead

    /**
     * Reads one chunk from the device and decodes it. Returns bytes of PCM produced,
     * 0 if the read yielded nothing usable, or -1 if the link is gone.
     */
    private fun pump(): Int {
        val conn = connection ?: return -1
        val n = conn.read(scratch)
        if (n <= 0) return 0
        val decoded = parser.decode(scratch, n)
        if (decoded.isEmpty()) return 0

        // Compact any unread remainder to the front before appending.
        val remaining = available()
        val needed = remaining + decoded.size
        if (pcm.size < needed) pcm = pcm.copyOf(needed.coerceAtLeast(INITIAL_PCM))
        if (remaining > 0 && pcmRead > 0) System.arraycopy(pcm, pcmRead, pcm, 0, remaining)
        System.arraycopy(decoded, 0, pcm, remaining, decoded.size)
        pcmRead = 0
        pcmLen = needed

        if (sampleRate != parser.sampleRate && parser.sampleRate > 0) {
            sampleRate = parser.sampleRate
        }
        return decoded.size
    }

    private companion object {
        val CMD_START = byteArrayOf('S'.code.toByte())
        val CMD_STOP = byteArrayOf('X'.code.toByte())

        /** Comfortably larger than one 1040-byte frame so reads are efficient. */
        const val READ_CHUNK = 4096
        const val INITIAL_PCM = 16384

        const val DRAIN_READS = 8
        const val START_TIMEOUT_MS = 3_000L
    }
}
