package com.capstone.stethoscope.audio.usb

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Checks and uploads the board's firmware — **only when the user asks**, from the
 * Diagnostics screen.
 *
 * ## Why this is manual now (2026-09-30)
 *
 * It used to check on every attach and reflash on any mismatch. That caused problems in
 * practice: every plug-in claimed the single-claim CDC interface for a check (racing
 * capture and the live monitor), and a flash could start when nobody wanted one. So
 * [start] now only clears the status on detach; nothing touches the board until
 * [recheck] (read-only) or [reinstall] (upload) is called. To restore the old behaviour,
 * call `checkAndUpdate()` for a non-null device in [start].
 *
 * The rest of this comment describes why the app can flash the hardware at all.
 *
 * ## Why the app flashes the hardware
 *
 * The stethoscope's behaviour and the app's signal chain are one design, not two: the
 * firmware's 16 kHz sample rate, its 12-bit right-aligned wire format and its `STH1`
 * framing are all assumptions baked into [StethoscopeFrameParser] and
 * [com.capstone.stethoscope.analysis.pcg.PcgPreprocessor]. A board carrying an older
 * `main.py` does not fail loudly; it streams 8 kHz samples that the app dutifully
 * resamples, producing a recording that looks entirely plausible and is quietly built on
 * half the intended resolution. Worse, a board with *no* firmware looks identical to
 * broken hardware.
 *
 * Checking on every attach and reflashing on a mismatch removes that whole class of
 * problem — and removes the need for anyone in the field to own `mpremote`.
 *
 * ## What it does, in order
 *
 *  1. Wait for the device to be attached **and** permitted (Android requires per-device
 *     consent before the app may open it at all).
 *  2. Ask the firmware to identify itself (`?`) and compare `fw=` against the bundled
 *     [StethoscopeFirmware] version.
 *  3. If they match, stop — the common case costs about a second.
 *  4. Otherwise enter the MicroPython raw REPL, stream the bundled `main.py` across in
 *     base64 chunks, verify size and checksum on the device, then soft-reboot and confirm
 *     the new banner.
 *
 * The write goes to a temporary name and is renamed into place only after it verifies,
 * so a flash interrupted by an unplugged cable leaves the *old* working firmware behind
 * rather than a truncated `main.py` that fails to boot.
 */
class FirmwareManager(
    context: Context,
    private val stethoscope: UsbStethoscope,
) {
    private val appContext = context.applicationContext

    sealed interface State {
        /** Nothing plugged in, or not permitted yet — nothing to say. */
        data object Idle : State
        data object Checking : State
        data class UpToDate(val version: String, val sampleRate: Int?) : State

        /** The board runs [found] (null = no recognisable firmware), not [bundled]. */
        data class Outdated(val found: String?, val bundled: String) : State
        data class Installing(val version: String, val percent: Int) : State
        data class Installed(val version: String) : State

        /**
         * The check or flash failed. [recoverable] distinguishes "the device was busy,
         * this will be retried" from "something is actually wrong", because the first
         * does not deserve an alarming message.
         */
        data class Failed(val reason: String, val recoverable: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** True while a flash is in progress, when nothing else may claim the device. */
    val isWorking: Boolean
        get() = _state.value.let { it is State.Checking || it is State.Installing }

    /**
     * This manager's own scope, deliberately not one belonging to a screen.
     *
     * A flash takes several seconds of round trips and must not be cancelled because the
     * user navigated away from whichever screen happened to trigger it — an interrupted
     * write is the one failure mode that could leave the board unable to boot. The
     * rename-after-verify dance in [install] protects against it, but not creating the
     * hazard is better than surviving it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var job: Job? = null

    /**
     * Watches for the stethoscope so the status resets when it is unplugged. Does **not**
     * check or flash on attach — see the class comment.
     *
     * `collectLatest` matters: unplugging mid-flash cancels the flash rather than leaving
     * it writing to a handle that is already gone.
     */
    fun start() {
        job?.cancel()
        job = scope.launch {
            stethoscope.state
                // Key on the device's bus path, not on the state object.
                //
                // `UsbDevice` does not override `equals`, so `State.Ready(device)` is a
                // distinct value every time `UsbStethoscope.refresh()` runs — and the UI
                // refreshes whenever the Listen screen appears. Collecting the state flow
                // directly therefore re-ran the whole check, claiming the device and
                // greying out Record for a second, every time the user navigated home.
                // The bus path is stable while the board stays plugged in, and a detach
                // passes through null so a genuine re-plug still triggers a check.
                .map { s -> (s as? UsbStethoscope.State.Ready)?.device?.deviceName }
                .distinctUntilChanged()
                .collectLatest { deviceName ->
                    // A new or removed board invalidates whatever was reported for the
                    // last one. A running upload is cancelled with its Job below.
                    if (deviceName == null) manualJob?.cancel()
                    _state.value = State.Idle
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private var manualJob: Job? = null

    /**
     * Reads the board's firmware version without changing anything, e.g. from the
     * Diagnostics screen's Refresh button. A mismatch is reported as [State.Outdated].
     */
    fun recheck() {
        if (isWorking) return
        manualJob = scope.launch { checkAndUpdate(install = false) }
    }

    /**
     * Uploads the bundled firmware unconditionally, even when the version already matches.
     */
    fun reinstall() {
        if (isWorking) return
        manualJob = scope.launch { checkAndUpdate(install = true, force = true) }
    }

    private suspend fun checkAndUpdate(
        install: Boolean,
        force: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        val payload = runCatching { StethoscopeFirmware.load(appContext) }.getOrElse { t ->
            _state.value = State.Failed(
                "This build of the app has no firmware bundled (${t.message}).",
                recoverable = false,
            )
            return@withContext
        }

        // Capture may legitimately hold the device. Retry a few times rather than
        // reporting a failure the user can do nothing about.
        var connection: UsbCdcConnection? = null
        for (attempt in 1..CONNECT_ATTEMPTS) {
            val result = stethoscope.connect()
            if (result.isSuccess) {
                connection = result.getOrNull()
                break
            }
            if (attempt == CONNECT_ATTEMPTS) {
                _state.value = State.Failed(
                    result.exceptionOrNull()?.message ?: "Could not open the stethoscope.",
                    recoverable = true,
                )
                return@withContext
            }
            delay(CONNECT_RETRY_MS)
        }
        val conn = connection ?: return@withContext

        try {
            val repl = MicroPythonRepl(conn)
            val banner = repl.identify()
            val found = banner?.let { StethoscopeFirmware.parseBannerVersion(it) }

            if (!force && found == payload.version) {
                _state.value = State.UpToDate(
                    version = found,
                    sampleRate = banner?.let { StethoscopeFirmware.parseBannerRate(it) },
                )
                return@withContext
            }

            if (!install) {
                _state.value = State.Outdated(found = found, bundled = payload.version)
                return@withContext
            }

            install(repl, payload)
        } catch (t: Throwable) {
            _state.value = State.Failed(t.message ?: "Firmware check failed.", recoverable = true)
        } finally {
            runCatching { conn.close() }
        }
    }

    /**
     * Streams [payload] onto the board and confirms it landed.
     *
     * Base64 rather than raw bytes in the generated source: the firmware contains quotes,
     * backslashes and newlines, and escaping all of them correctly through a Python string
     * literal inside a Kotlin string literal is a bug waiting to happen. Base64 has no
     * characters Python's tokenizer cares about, so the generated line is always valid.
     */
    private suspend fun install(repl: MicroPythonRepl, payload: StethoscopeFirmware.Payload) {
        _state.value = State.Installing(payload.version, 0)

        if (!repl.enterRaw()) {
            _state.value = State.Failed(
                "The board did not respond to the MicroPython REPL, so its firmware " +
                    "cannot be updated. Unplug it, plug it back in, and try again.",
                recoverable = true,
            )
            return
        }

        try {
            // b64decode is spelled differently across MicroPython builds and ports, so
            // resolve it once here rather than guessing.
            repl.exec(
                """
                try:
                    import binascii as _b64
                except ImportError:
                    import ubinascii as _b64
                import os as _os
                _f = open('$TEMP_FILE', 'wb')
                """.trimIndent()
            ).getOrThrow()

            var sent = 0
            while (sent < payload.sizeBytes) {
                val end = minOf(sent + CHUNK_BYTES, payload.sizeBytes)
                val encoded = Base64.encodeToString(
                    payload.source.copyOfRange(sent, end), Base64.NO_WRAP,
                )
                repl.exec("_f.write(_b64.a2b_base64('$encoded'))").getOrThrow()
                sent = end
                _state.value = State.Installing(
                    payload.version,
                    percent = sent * 100 / payload.sizeBytes,
                )
            }

            repl.exec("_f.close()").getOrThrow()

            // Verify before renaming into place. A truncated main.py would leave the board
            // unable to boot its firmware at all, which is a much worse state than the
            // stale firmware we are replacing.
            val stat = repl.exec(
                "print(_os.stat('$TEMP_FILE')[6], sum(open('$TEMP_FILE','rb').read()))"
            ).getOrThrow().trim().split(Regex("\\s+"))
            val size = stat.getOrNull(0)?.toIntOrNull()
            val sum = stat.getOrNull(1)?.toLongOrNull()
            if (size != payload.sizeBytes || sum != payload.byteSum) {
                repl.exec("_os.remove('$TEMP_FILE')")
                _state.value = State.Failed(
                    "The firmware did not transfer correctly (got $size bytes, expected " +
                        "${payload.sizeBytes}). The old firmware is untouched; try again.",
                    recoverable = true,
                )
                return
            }

            // Only now is it safe to replace the working copy. os.rename cannot overwrite
            // on MicroPython's filesystem, hence the explicit remove.
            repl.exec(
                """
                try:
                    _os.remove('${StethoscopeFirmware.TARGET_FILE}')
                except OSError:
                    pass
                _os.rename('$TEMP_FILE', '${StethoscopeFirmware.TARGET_FILE}')
                """.trimIndent()
            ).getOrThrow()

            repl.exitRaw()
            repl.softReset()

            // Confirm rather than assume: a board that boots into a traceback would
            // otherwise be reported as successfully updated.
            delay(RESET_SETTLE_MS)
            val banner = repl.identify(timeoutMs = POST_RESET_IDENTIFY_MS)
            val running = banner?.let { StethoscopeFirmware.parseBannerVersion(it) }
            _state.value = if (running == payload.version) {
                State.Installed(payload.version)
            } else {
                State.Failed(
                    "Firmware ${payload.version} was written but the board reported " +
                        "'${running ?: banner ?: "nothing"}' after restarting. Unplug it and " +
                        "plug it back in.",
                    recoverable = true,
                )
            }
        } catch (t: Throwable) {
            runCatching { repl.exec("_f.close()") }
            runCatching { repl.exitRaw() }
            _state.value = State.Failed(
                "Firmware update failed: ${t.message ?: "unknown error"}. The board's " +
                    "previous firmware is untouched.",
                recoverable = true,
            )
        }
    }

    private companion object {
        /** Written first, renamed over main.py only once verified. */
        const val TEMP_FILE = "_stho_new.py"

        /**
         * Raw bytes per submission. 384 encodes to a 512-character literal, which keeps
         * each generated line comfortably inside MicroPython's input handling while still
         * moving a 13 KB image in about 35 round trips.
         */
        const val CHUNK_BYTES = 384

        const val CONNECT_ATTEMPTS = 3
        const val CONNECT_RETRY_MS = 1_500L
        const val RESET_SETTLE_MS = 1_200L
        const val POST_RESET_IDENTIFY_MS = 3_000L
    }
}
