package com.capstone.stethoscope.audio.usb

/**
 * Drives MicroPython's **raw REPL** over the stethoscope's USB CDC link, which is how the
 * app installs firmware onto the board.
 *
 * ## The protocol
 *
 * MicroPython's serial console has two modes. The friendly REPL echoes input and prints a
 * `>>>` prompt — fine for a human, useless for a program, because the echo comes back
 * interleaved with output and continuation prompts appear inside indented blocks. Sending
 * `Ctrl-A` switches to the raw REPL, where the exchange is framed:
 *
 * ```
 *   host                     device
 *   \x01          ──►
 *                 ◄──        "raw REPL; CTRL-B to exit\r\n>"
 *   <code>\x04    ──►
 *                 ◄──        "OK" <stdout> \x04 <stderr> \x04 ">"
 * ```
 *
 * So a submission's result is unambiguous: everything before the first `\x04` is what the
 * code printed, everything between the two `\x04`s is the traceback if it raised, and the
 * trailing `>` means the device is ready for more. That framing is the entire reason this
 * class exists — screen-scraping the friendly REPL would work right up until a firmware
 * line happened to contain `>>>`.
 *
 * Globals persist between submissions, so a file can be opened in one and written across
 * many, which is how [FirmwareManager] streams a 13 KB firmware image through a link that
 * will not take it in one bite.
 *
 * ## Two gotchas that will cost an afternoon
 *
 *  - **Ctrl-C does not always interrupt.** The firmware calls
 *    `micropython.kbd_intr(-1)` while streaming, because `0x03` is a legal byte in binary
 *    sample data. A streaming board must be stopped with the `X` command *first*; only
 *    then does `Ctrl-C` get it out of its idle loop.
 *  - **The link is full of binary.** Frames already in flight arrive mixed into REPL
 *    output, so nothing here may assume the stream is text. Reads scan for byte patterns
 *    and the buffer is drained before every exchange.
 */
internal class MicroPythonRepl(private val conn: UsbCdcConnection) {

    /** Bytes received but not yet matched against a pattern. */
    private val pending = StringBuilder()
    private val scratch = ByteArray(READ_CHUNK)

    // --- low-level link ---

    /**
     * Discards whatever the device has queued, so a reply cannot be confused with
     * something it said before we were listening. Always worth doing: the board may have
     * been streaming samples for minutes.
     */
    fun drain(forMs: Long = DRAIN_MS) {
        val deadline = System.currentTimeMillis() + forMs
        while (System.currentTimeMillis() < deadline) {
            conn.read(scratch, timeoutMs = 20)
        }
        pending.setLength(0)
    }

    private fun send(bytes: ByteArray): Boolean = conn.write(bytes)

    private fun send(text: String): Boolean = send(text.toByteArray(Charsets.UTF_8))

    /**
     * Reads until [marker] appears or [timeoutMs] elapses. Returns everything received up
     * to and including the marker, or null on timeout.
     *
     * Bytes are decoded as ISO-8859-1 rather than UTF-8 on purpose: it is the only
     * encoding that round-trips arbitrary bytes, and this stream carries binary sample
     * frames as well as REPL text. Decoding as UTF-8 would turn a stray 0x80 into a
     * replacement character and could swallow part of the marker.
     */
    private fun readUntil(marker: String, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val at = pending.indexOf(marker)
            if (at >= 0) {
                val out = pending.substring(0, at + marker.length)
                pending.delete(0, at + marker.length)
                return out
            }
            if (System.currentTimeMillis() >= deadline) return null
            val n = conn.read(scratch, timeoutMs = 100)
            if (n > 0) pending.append(String(scratch, 0, n, Charsets.ISO_8859_1))
        }
    }

    // --- firmware-level operations ---

    /**
     * Asks the running firmware to identify itself and returns its banner line, or null
     * if nothing recognisable answered.
     *
     * Sends `X` before `?` so a board that is mid-stream stops and starts listening for
     * commands again; a stray `?` inside a sample frame is harmless either way.
     */
    fun identify(timeoutMs: Long = IDENTIFY_MS): String? {
        send(CMD_STOP)
        drain()
        if (!send(CMD_IDENTIFY)) return null
        // The banner ends in a newline; matching on that rather than a fixed length means
        // a future firmware can add fields without breaking the read.
        val reply = readUntil("\n", timeoutMs) ?: return null
        return reply.lineSequence().firstOrNull { it.contains("STETHOSCOPE") }
            ?: reply.takeIf { it.contains("STETHOSCOPE") }
    }

    /**
     * Interrupts whatever is running and enters the raw REPL.
     *
     * The `X` first, then two `Ctrl-C`s: one to break out of `time.sleep_ms` in the idle
     * loop, a second in case the first landed while interrupts were still disabled.
     */
    fun enterRaw(): Boolean {
        send(CMD_STOP)
        Thread.sleep(SETTLE_MS)
        send(byteArrayOf(CTRL_C, CTRL_C))
        Thread.sleep(SETTLE_MS)
        drain()
        if (!send(byteArrayOf(CTRL_A))) return false
        return readUntil(RAW_PROMPT, ENTER_RAW_MS) != null
    }

    /** Leaves the raw REPL for the friendly one. Best-effort; failure is not fatal. */
    fun exitRaw() {
        send(byteArrayOf(CTRL_B))
        drain(forMs = 100)
    }

    /**
     * Soft-reboots the board so `main.py` runs again.
     *
     * A soft reset rather than `machine.reset()`: it re-runs the boot scripts without
     * dropping off the USB bus, so the handle the app is holding stays valid and the user
     * does not see the device disappear and reappear.
     */
    fun softReset() {
        send(byteArrayOf(CTRL_D))
        drain(forMs = 400)
    }

    /**
     * Runs [code] on the device and returns whatever it printed.
     *
     * Failure modes are kept distinct because they need different responses: a timeout
     * usually means the link died mid-flash, whereas a traceback means the code was wrong
     * and retrying would fail the same way.
     */
    fun exec(code: String, timeoutMs: Long = EXEC_MS): Result<String> {
        if (!send(code)) return Result.failure(IllegalStateException("Link dropped while sending code."))
        if (!send(byteArrayOf(CTRL_D))) {
            return Result.failure(IllegalStateException("Link dropped before the code could be run."))
        }

        // The device acknowledges a well-formed submission with "OK" before running it.
        // Anything else means it was not actually in the raw REPL.
        if (readUntil("OK", ACK_MS) == null) {
            return Result.failure(IllegalStateException("The board did not acknowledge the raw REPL."))
        }

        val body = readUntil(EOT.toString(), timeoutMs)
            ?: return Result.failure(IllegalStateException("The board stopped responding while running code."))
        val stdout = body.dropLast(1)

        val errBlock = readUntil(EOT.toString(), ERROR_MS)
            ?: return Result.failure(IllegalStateException("The board did not finish its reply."))
        val stderr = errBlock.dropLast(1).trim()

        // Consume the trailing prompt so the next exec starts clean.
        readUntil(">", ACK_MS)

        return if (stderr.isEmpty()) {
            Result.success(stdout)
        } else {
            // Last line of a traceback is the actual exception; the frames above it are
            // this file's generated code and would only confuse the user.
            Result.failure(IllegalStateException(stderr.lines().last().trim().ifEmpty { stderr }))
        }
    }

    private companion object {
        const val CTRL_A: Byte = 0x01
        const val CTRL_B: Byte = 0x02
        const val CTRL_C: Byte = 0x03
        const val CTRL_D: Byte = 0x04
        /** End-of-transmission, the byte the raw REPL frames its reply with. */
        const val EOT = '\u0004'

        val CMD_STOP = "X"
        val CMD_IDENTIFY = "?"

        const val RAW_PROMPT = "raw REPL; CTRL-B to exit"

        const val READ_CHUNK = 4096
        const val DRAIN_MS = 250L
        const val SETTLE_MS = 120L
        const val IDENTIFY_MS = 1_500L
        const val ENTER_RAW_MS = 2_000L
        const val ACK_MS = 2_000L
        const val EXEC_MS = 5_000L
        const val ERROR_MS = 2_000L
    }
}
