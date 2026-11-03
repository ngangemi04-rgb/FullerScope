package com.capstone.stethoscope.audio.usb

import android.content.Context

/**
 * The firmware the app ships for the stethoscope board, and the rules for deciding
 * whether what is on the board matches it.
 *
 * `firmware/main.py` is copied into the APK's assets by the Gradle build
 * (`syncFirmwareAsset`), so there is exactly one source of truth: the file a developer
 * flashes by hand with `mpremote` is byte-identical to the one the app flashes over USB.
 * Keeping a second hand-maintained copy here would eventually mean the app confidently
 * installing an older firmware than the repo's.
 */
object StethoscopeFirmware {

    /** Path inside the APK's assets. Generated — do not check a copy in at this path. */
    private const val ASSET_PATH = "firmware/main.py"

    /** Where it lands on the board. MicroPython runs `main.py` at boot. */
    const val TARGET_FILE = "main.py"

    /** The shipped firmware: its declared version and its exact bytes. */
    class Payload(
        val version: String,
        val source: ByteArray,
    ) {
        val sizeBytes: Int get() = source.size

        /**
         * Sum of every byte, which the board recomputes after a flash.
         *
         * A plain sum rather than a CRC because MicroPython builds do not all ship
         * `binascii.crc32`, and this only has to catch truncation and corruption of a
         * file we already know the expected length of — not resist a forged one.
         */
        val byteSum: Long get() = source.fold(0L) { acc, b -> acc + (b.toInt() and 0xFF) }
    }

    /**
     * Reads the bundled firmware. Throws if the asset is missing or has no parseable
     * version, because both mean the build is broken in a way that silently flashing
     * something would hide.
     */
    fun load(context: Context): Payload {
        val bytes = context.assets.open(ASSET_PATH).use { it.readBytes() }
        val version = parseSourceVersion(String(bytes, Charsets.UTF_8))
            ?: error("Bundled $ASSET_PATH declares no FW_VERSION")
        return Payload(version, bytes)
    }

    /**
     * Pulls `FW_VERSION = "..."` out of the firmware source.
     *
     * Deliberately reads the source text rather than duplicating the constant in Kotlin:
     * the version and the code it describes then cannot disagree.
     */
    fun parseSourceVersion(pythonSource: String): String? =
        SOURCE_VERSION.find(pythonSource)?.groupValues?.get(1)

    /**
     * Pulls the version out of the firmware's `?` banner, e.g.
     * `STETHOSCOPE ready fw=2.0.0 proto=STH1 rate=16000 ...`.
     *
     * Returns null when the line is not a stethoscope banner at all — which is the normal
     * answer from a board sitting in a bare MicroPython REPL with no firmware installed.
     */
    fun parseBannerVersion(banner: String): String? {
        if (!banner.contains(BANNER_MARKER)) return null
        return BANNER_VERSION.find(banner)?.groupValues?.get(1)
    }

    /** Sample rate the running firmware declares in its banner, or null. */
    fun parseBannerRate(banner: String): Int? =
        BANNER_RATE.find(banner)?.groupValues?.get(1)?.toIntOrNull()

    private const val BANNER_MARKER = "STETHOSCOPE ready"
    private val SOURCE_VERSION = Regex("""^FW_VERSION\s*=\s*["']([^"']+)["']""", RegexOption.MULTILINE)
    private val BANNER_VERSION = Regex("""fw=(\S+)""")
    private val BANNER_RATE = Regex("""rate=(\d+)""")
}
