package com.capstone.stethoscope.export

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands exported files to WhatsApp — the way results actually get to a doctor in
 * the field.
 *
 * Three deliberate choices, each of which is easy to get wrong:
 *
 *  1. **Files go as documents, never as an audio message.** WhatsApp re-encodes
 *     anything sent as a voice/audio message to lossy Opus, which would damage the
 *     exact signal the analysis algorithm reads. Documents are delivered
 *     byte-exact, so the WAV that arrives is still analyzable. This is why the
 *     intent type stays generic instead of an audio wildcard.
 *  2. **The intent is aimed at the WhatsApp package** so the clinician lands
 *     directly on the contact picker instead of a share sheet. Requires the
 *     `<queries>` entry in AndroidManifest.xml — without it `getPackageInfo` is
 *     invisible on Android 11+ and we would always think WhatsApp is missing.
 *  3. **URI grants ride on ClipData.** Setting a package skips the chooser, and
 *     the chooser is what normally propagates `FLAG_GRANT_READ_URI_PERMISSION` to
 *     multiple `EXTRA_STREAM` items. Without ClipData the attachments arrive as
 *     permission-denied.
 */
object WhatsAppSharer {

    /** Consumer WhatsApp first, then WhatsApp Business (common for clinics). */
    private val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    /** The installed WhatsApp flavour, or null if neither is present. */
    fun installedPackage(context: Context): String? = PACKAGES.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    /**
     * Opens WhatsApp with [uris] attached, falling back to the system share sheet when
     * WhatsApp isn't installed or refuses the intent. [title] labels that fallback
     * chooser only. Returns false only if nothing could be launched at all.
     *
     * ## No message text, deliberately
     *
     * WhatsApp will not accept `EXTRA_TEXT` and `EXTRA_STREAM` in the same intent. Given
     * both, it takes the text path and **silently drops every attachment** — the message
     * arrives and the files do not, with no error anywhere. An earlier version of this
     * class set a summary caption and shipped exactly that bug.
     *
     * The clinician types their own note in WhatsApp instead. Nothing is lost: the PDF
     * already carries the patient details and the analysis result.
     */
    fun share(context: Context, uris: List<Uri>, title: String): Boolean {
        if (uris.isEmpty()) return false
        val target = installedPackage(context)

        if (target != null) {
            // Belt and braces alongside ClipData: an explicit grant survives even if
            // the receiving app re-reads the URI from a background service later.
            uris.forEach {
                context.grantUriPermission(target, it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(buildIntent(context, uris).setPackage(target))
                return true
            } catch (_: ActivityNotFoundException) {
                // WhatsApp is installed but wouldn't take this intent shape — fall through.
            }
        }

        return runCatching {
            context.startActivity(Intent.createChooser(buildIntent(context, uris), title))
        }.isSuccess
    }

    private fun buildIntent(context: Context, uris: List<Uri>): Intent {
        val single = uris.size == 1
        return Intent(if (single) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            // Generic type keeps WhatsApp on the document path (see note 1 above).
            type = "*/*"
            if (single) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            // Deliberately NO EXTRA_TEXT here — see the note on share().
            clipData = buildClip(context, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Builds ClipData whose description declares **every** attachment's MIME type.
     *
     * `ClipData.newUri()` derives the ClipDescription from the first URI alone, and
     * `addItem()` does not revise it. Attaching a PDF and then a WAV therefore produced a
     * clip advertising only `application/pdf` — and WhatsApp, filtering by the declared
     * type, delivered the PDF and silently dropped the WAV. The files were staged
     * correctly on disk; only the description lied about them.
     */
    private fun buildClip(context: Context, uris: List<Uri>): ClipData {
        val resolver = context.contentResolver
        val mimeTypes = uris
            .map { resolver.getType(it) ?: "application/octet-stream" }
            .distinct()
            .toTypedArray()
        val clip = ClipData(
            ClipDescription("StethoScope", mimeTypes),
            ClipData.Item(uris.first()),
        )
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return clip
    }
}
