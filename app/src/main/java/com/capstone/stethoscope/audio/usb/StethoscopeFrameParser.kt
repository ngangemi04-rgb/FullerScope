package com.capstone.stethoscope.audio.usb

/**
 * Decodes the `STH1` frame format the Pico firmware streams over USB CDC.
 *
 * Wire format, little-endian throughout:
 * ```
 *   offset  size   field
 *   0       4      magic       "STH1"
 *   4       2      seq         uint16, increments per frame, wraps
 *   6       2      nsamples    uint16
 *   8       4      sampleRate  uint32, Hz
 *   12      2      flags       uint16, bit0 = ADC FIFO overrun since last frame
 *   14      2      reserved    uint16
 *   16      n*2    samples     uint16, right-aligned 12-bit, 0..4095
 * ```
 *
 * Three things this has to get right, because USB bulk reads respect no frame
 * boundaries whatsoever:
 *
 *  - **Split frames.** A read can end mid-header or mid-payload, so anything
 *    incomplete is carried over to the next call rather than discarded.
 *  - **Resynchronisation.** If the stream is joined mid-frame — which it always is,
 *    since the firmware may already be streaming when we open the device — bytes are
 *    skipped until a plausible magic appears. Fields are sanity-checked so that four
 *    bytes of sample data that happen to spell "STH1" cannot derail the stream for
 *    long.
 *  - **Loss reporting.** [droppedFrames] counts sequence discontinuities (frames lost
 *    to USB backpressure) and [overrunFrames] counts frames the firmware flagged as
 *    having lost samples inside the ADC FIFO. Silent loss would look like an
 *    arrhythmia, so it is surfaced rather than absorbed.
 */
class StethoscopeFrameParser {

    /** Rate declared by the most recent frame, or 0 before the first one decodes. */
    var sampleRate: Int = 0
        private set

    var framesDecoded: Long = 0
        private set

    /** Frames missing from the sequence — data actually lost in transit. */
    var droppedFrames: Long = 0
        private set

    /** Frames the firmware marked as having overrun the ADC FIFO. */
    var overrunFrames: Long = 0
        private set

    /** Bytes discarded while hunting for a frame boundary. */
    var resyncBytes: Long = 0
        private set

    private var pending = ByteArray(INITIAL_CAPACITY)
    private var pendingLen = 0
    private var lastSeq = -1

    /**
     * Feeds [length] bytes from [data] and returns the mono 16-bit little-endian PCM
     * decoded from any complete frames, or an empty array if none completed yet.
     *
     * Samples are centred and scaled on the way out: the firmware sends raw unsigned
     * 12-bit values, and `(raw - 2048) shl 4` maps them onto the full signed 16-bit
     * range. The shift is exact, so this loses nothing — it is the same correction
     * [com.capstone.stethoscope.audio.SignalConditioner] would otherwise apply, done
     * here so that live earbud monitoring plays at a sensible volume instead of 24 dB
     * down.
     */
    fun decode(data: ByteArray, length: Int): ByteArray {
        if (length > 0) append(data, length)
        if (pendingLen < HEADER_LEN) return EMPTY

        // Worst case every pending byte is payload, so this is a safe upper bound.
        val out = ByteArray(pendingLen)
        var outLen = 0
        var read = 0

        while (true) {
            val start = findMagic(read)
            if (start < 0) {
                // No magic anywhere; keep only the tail that might be a split magic.
                resyncBytes += (pendingLen - read - MAGIC_KEEP).coerceAtLeast(0)
                read = (pendingLen - MAGIC_KEEP).coerceAtLeast(read)
                break
            }
            if (start > read) {
                resyncBytes += (start - read)
                read = start
            }
            if (pendingLen - read < HEADER_LEN) break

            val nsamples = u16(read + 6)
            val declaredRate = u32(read + 8)
            val flags = u16(read + 12)

            if (nsamples == 0 || nsamples > MAX_SAMPLES ||
                declaredRate < MIN_RATE || declaredRate > MAX_RATE
            ) {
                // Not a real header — almost certainly payload that looked like magic.
                resyncBytes += 1
                read += 1
                continue
            }

            val frameLen = HEADER_LEN + nsamples * 2
            if (pendingLen - read < frameLen) break // wait for the rest

            val seq = u16(read + 4)
            if (lastSeq >= 0) {
                val gap = (seq - lastSeq) and 0xFFFF
                if (gap != 1) droppedFrames += (gap - 1).coerceAtLeast(0).toLong()
            }
            lastSeq = seq
            sampleRate = declaredRate
            if (flags and FLAG_OVERRUN != 0) overrunFrames++
            framesDecoded++

            var p = read + HEADER_LEN
            repeat(nsamples) {
                val raw = u16(p) and 0x0FFF
                val centred = (raw - MID_SCALE) shl 4
                out[outLen] = (centred and 0xFF).toByte()
                out[outLen + 1] = ((centred shr 8) and 0xFF).toByte()
                outLen += 2
                p += 2
            }
            read += frameLen
        }

        compact(read)
        return if (outLen == 0) EMPTY else out.copyOf(outLen)
    }

    /** Forgets buffered bytes and sequence state, e.g. when restarting a capture. */
    fun reset() {
        pendingLen = 0
        lastSeq = -1
    }

    // --- buffer plumbing ---

    private fun append(data: ByteArray, length: Int) {
        if (pendingLen + length > pending.size) {
            var cap = pending.size.coerceAtLeast(INITIAL_CAPACITY)
            while (cap < pendingLen + length) cap *= 2
            pending = pending.copyOf(cap)
        }
        System.arraycopy(data, 0, pending, pendingLen, length)
        pendingLen += length
    }

    private fun compact(consumed: Int) {
        if (consumed <= 0) return
        val remaining = pendingLen - consumed
        if (remaining > 0) System.arraycopy(pending, consumed, pending, 0, remaining)
        pendingLen = remaining
    }

    private fun findMagic(from: Int): Int {
        var i = from
        val limit = pendingLen - MAGIC.size
        while (i <= limit) {
            if (pending[i] == MAGIC[0] && pending[i + 1] == MAGIC[1] &&
                pending[i + 2] == MAGIC[2] && pending[i + 3] == MAGIC[3]
            ) {
                return i
            }
            i++
        }
        return -1
    }

    private fun u16(at: Int): Int =
        (pending[at].toInt() and 0xFF) or ((pending[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(at: Int): Int =
        (pending[at].toInt() and 0xFF) or
            ((pending[at + 1].toInt() and 0xFF) shl 8) or
            ((pending[at + 2].toInt() and 0xFF) shl 16) or
            ((pending[at + 3].toInt() and 0xFF) shl 24)

    companion object {
        const val HEADER_LEN = 16
        const val FLAG_OVERRUN = 1

        /** Midpoint of the RP2040's unsigned 12-bit ADC output. */
        const val MID_SCALE = 2048

        /**
         * ADC reference, which on this board is the 3.3 V rail the whole front end runs
         * from. The ADC is biased at mid-rail, so the largest signal it can represent is
         * ±1.65 V about that point.
         */
        const val SUPPLY_VOLTS = 3.3

        /**
         * Volts represented by one int16 count in the PCM this parser emits.
         *
         * The `(raw - 2048) shl 4` mapping above is exact and lossless, so the 12-bit
         * span lands precisely on the full 16-bit range: ±32768 counts *is* ±1.65 V.
         * That is what lets the testing scope put a real voltage axis on the signal
         * rather than an arbitrary one — but it holds only while nothing else rescales
         * the samples afterwards, which is why
         * [com.capstone.stethoscope.audio.SignalConditioner]'s gain has to be divided
         * back out before this constant is applied.
         */
        const val VOLTS_PER_COUNT = SUPPLY_VOLTS / 65_536.0

        val MAGIC = byteArrayOf('S'.code.toByte(), 'T'.code.toByte(), 'H'.code.toByte(), '1'.code.toByte())

        /** Sanity bounds so a false magic in payload data is rejected quickly. */
        private const val MAX_SAMPLES = 4096
        private const val MIN_RATE = 500
        private const val MAX_RATE = 192_000

        /** Bytes retained when no magic is found, in case one is split across reads. */
        private const val MAGIC_KEEP = 3

        private const val INITIAL_CAPACITY = 8192
        private val EMPTY = ByteArray(0)
    }
}
