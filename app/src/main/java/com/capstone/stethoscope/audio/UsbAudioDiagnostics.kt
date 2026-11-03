package com.capstone.stethoscope.audio

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dumps everything the phone can tell us about what is plugged into the USB port.
 *
 * This exists because the capture path depends entirely on *how* the stethoscope
 * hardware presents itself, and that can't be determined from a datasheet alone:
 *
 *  - **USB Audio Class** (interface class 1) — [AudioRecord] can read it directly,
 *    which is what [StethoscopeRecorder] assumes today.
 *  - **CDC / serial** (interface class 2 or 10) — invisible to the audio framework
 *    entirely; would need the USB Host API and a framing layer.
 *  - **Vendor-specific** (interface class 255) — needs the vendor's protocol.
 *
 * Enumerating devices needs no permission (only *opening* one does), so this is
 * safe to run any time. Run it with the hardware attached and share the output.
 */
object UsbAudioDiagnostics {

    /** Rates worth probing: the app's options plus the rates USB audio usually offers. */
    private val PROBE_RATES = intArrayOf(4_000, 8_000, 16_000, 22_050, 32_000, 44_100, 48_000)

    fun report(context: Context): String = buildString {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        appendLine("StethoScope hardware diagnostics")
        appendLine(stamp)
        appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT})")
        appendLine()

        appendCdcSummary(context)
        appendLine()
        appendUsbDevices(context)
        appendLine()
        appendAudioInputs(context)
        appendLine()
        appendRateProbe()
        appendLine()
        appendVerdict(context)
    }

    // --- The capture path the app will actually use ---

    private fun StringBuilder.appendCdcSummary(context: Context) {
        appendLine("== Capture path ==")
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val cdc = manager?.deviceList?.values?.firstOrNull { device ->
            (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA
            }
        }
        if (cdc == null) {
            appendLine("  No CDC serial device found — the app will record from the")
            appendLine("  built-in microphone. Plug in the stethoscope to capture for real.")
            return
        }
        val permitted = manager.hasPermission(cdc)
        appendLine("  CDC device : %s (VID 0x%04X PID 0x%04X)".format(
            cdc.productName ?: cdc.deviceName, cdc.vendorId, cdc.productId,
        ))
        appendLine("  Permission : ${if (permitted) "granted" else "NOT granted — tap Allow USB access"}")
        appendLine("  Route      : ${if (permitted) "USB CDC (StethoscopeFrameParser)" else "built-in microphone until permission is granted"}")
        if (cdc.vendorId != VID_RASPBERRY_PI) {
            appendLine("  Note: vendor is not Raspberry Pi (0x2E8A); this may not be the stethoscope.")
        }
    }

    /** Raspberry Pi Trading Ltd — the RP2040's vendor ID. */
    private const val VID_RASPBERRY_PI = 0x2E8A

    // --- Raw USB layer: the definitive answer on what kind of device this is ---

    private fun StringBuilder.appendUsbDevices(context: Context) {
        appendLine("== USB devices (raw) ==")
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val devices = manager?.deviceList?.values.orEmpty()
        if (devices.isEmpty()) {
            appendLine("  (none)")
            appendLine("  Nothing on the USB bus. Either the adapter isn't passing the device")
            appendLine("  through, the phone isn't supplying power / OTG, or the hardware")
            appendLine("  presents only as analog audio through a headphone adapter — in which")
            appendLine("  case it should still show up under audio inputs below.")
            return
        }
        devices.forEach { device ->
            appendLine("  ${device.deviceName}")
            appendLine("    Vendor  : 0x%04X  %s".format(device.vendorId, device.manufacturerName ?: "?"))
            appendLine("    Product : 0x%04X  %s".format(device.productId, device.productName ?: "?"))
            appendLine("    Class   : ${classLabel(device.deviceClass)} (subclass ${device.deviceSubclass}, protocol ${device.deviceProtocol})")
            appendLine("    Interfaces: ${device.interfaceCount}")
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                appendLine(
                    "      [$i] ${classLabel(iface.interfaceClass)}" +
                        " subclass ${iface.interfaceSubclass} protocol ${iface.interfaceProtocol}" +
                        " endpoints ${iface.endpointCount}"
                )
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    appendLine(
                        "          ep${e}: ${endpointType(ep.type)} ${direction(ep.direction)}" +
                            " maxPacket ${ep.maxPacketSize} interval ${ep.interval}"
                    )
                }
            }
        }
    }

    // --- Audio framework layer: what AudioRecord can actually route to ---

    private fun StringBuilder.appendAudioInputs(context: Context) {
        appendLine("== Audio inputs seen by Android ==")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (inputs.isEmpty()) {
            appendLine("  (none)")
            return
        }
        inputs.forEach { d ->
            val usb = d.type in USB_INPUT_TYPES
            appendLine("  ${if (usb) "* " else "  "}id=${d.id} ${deviceTypeLabel(d.type)} \"${d.productName}\"")
            appendLine("      sample rates : ${d.sampleRates.joinToString().ifEmpty { "(unrestricted)" }}")
            appendLine("      channel counts: ${d.channelCounts.joinToString().ifEmpty { "(unrestricted)" }}")
            appendLine("      encodings    : ${d.encodings.joinToString { encodingLabel(it) }.ifEmpty { "(unrestricted)" }}")
        }
        appendLine("  (* = treated as the stethoscope by StethoscopeRecorder)")
    }

    // --- What AudioRecord will actually accept on this phone ---

    private fun StringBuilder.appendRateProbe() {
        appendLine("== AudioRecord buffer probe (16-bit PCM) ==")
        appendLine("  rate    mono      stereo")
        PROBE_RATES.forEach { rate ->
            val mono = minBuffer(rate, AudioFormat.CHANNEL_IN_MONO)
            val stereo = minBuffer(rate, AudioFormat.CHANNEL_IN_STEREO)
            appendLine("  %-7d %-9s %s".format(rate, mono, stereo))
        }
    }

    private fun minBuffer(rate: Int, channelMask: Int): String {
        val size = AudioRecord.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        return if (size > 0) "$size B" else "unsupported"
    }

    // --- Plain-language conclusion ---

    private fun StringBuilder.appendVerdict(context: Context) {
        appendLine("== Verdict ==")
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val devices = manager?.deviceList?.values.orEmpty()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val usbAudioInput = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type in USB_INPUT_TYPES }

        val classes = devices.flatMap { device ->
            (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass }
        }.toSet()

        when {
            usbAudioInput != null -> {
                appendLine("  USB AUDIO CLASS — works with the app as written.")
                appendLine("  Android exposes this as a recording input, so AudioRecord +")
                appendLine("  setPreferredDevice() captures from it. Check the channel counts")
                appendLine("  above: if 1 isn't listed, the app records stereo and folds it")
                appendLine("  down using the Input channel setting.")
            }
            classes.contains(UsbConstants.USB_CLASS_AUDIO) -> {
                appendLine("  A USB audio interface is present but Android has NOT exposed it as")
                appendLine("  a recording input. Usually means the descriptors advertise output")
                appendLine("  only, or the audio streaming interface has no alternate setting with")
                appendLine("  a usable format. Firmware side: check the input terminal descriptor.")
            }
            classes.contains(UsbConstants.USB_CLASS_CDC_DATA) ||
                classes.contains(UsbConstants.USB_CLASS_COMM) -> {
                appendLine("  CDC / SERIAL device — AudioRecord cannot see this at all.")
                appendLine("  Capturing from it needs the USB Host API, a CDC driver, and code to")
                appendLine("  unpack the 12-bit sample stream. That is a rewrite of")
                appendLine("  StethoscopeRecorder, not a settings change. Send this report on.")
            }
            classes.contains(UsbConstants.USB_CLASS_VENDOR_SPEC) -> {
                appendLine("  VENDOR-SPECIFIC device — no standard driver applies. We need the")
                appendLine("  firmware's endpoint/packet protocol from whoever wrote it.")
            }
            devices.isEmpty() -> {
                appendLine("  Nothing detected on USB. If the hardware feeds analog audio into a")
                appendLine("  USB-C headphone adapter, look for the adapter under audio inputs")
                appendLine("  above — the adapter, not the microcontroller, is the USB device.")
                appendLine("  If nothing is listed there either, the phone isn't seeing it at all:")
                appendLine("  check OTG support, cable orientation, and whether the mic line is")
                appendLine("  wired to the adapter's mic pin rather than a headphone pin.")
            }
            else -> appendLine("  Device present but not audio, CDC, or vendor-specific. See the raw dump above.")
        }
    }

    // --- Label helpers ---

    private val USB_INPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )

    private fun classLabel(code: Int): String = when (code) {
        UsbConstants.USB_CLASS_APP_SPEC -> "APP_SPECIFIC"
        UsbConstants.USB_CLASS_AUDIO -> "AUDIO"
        UsbConstants.USB_CLASS_CDC_DATA -> "CDC_DATA"
        UsbConstants.USB_CLASS_COMM -> "COMM (CDC)"
        UsbConstants.USB_CLASS_CONTENT_SEC -> "CONTENT_SECURITY"
        UsbConstants.USB_CLASS_CSCID -> "SMART_CARD"
        UsbConstants.USB_CLASS_HID -> "HID"
        UsbConstants.USB_CLASS_HUB -> "HUB"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "MASS_STORAGE"
        UsbConstants.USB_CLASS_MISC -> "MISC"
        UsbConstants.USB_CLASS_PHYSICA -> "PHYSICAL"
        UsbConstants.USB_CLASS_PRINTER -> "PRINTER"
        UsbConstants.USB_CLASS_STILL_IMAGE -> "STILL_IMAGE"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> "VENDOR_SPECIFIC"
        UsbConstants.USB_CLASS_VIDEO -> "VIDEO"
        UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "WIRELESS"
        0 -> "PER_INTERFACE"
        else -> "0x%02X".format(code)
    }

    private fun endpointType(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
        else -> "0x%02X".format(type)
    }

    private fun direction(dir: Int): String =
        if (dir == UsbConstants.USB_DIR_IN) "IN (device→phone)" else "OUT (phone→device)"

    private fun deviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        else -> "type_$type"
    }

    private fun encodingLabel(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> "PCM_8BIT"
        AudioFormat.ENCODING_PCM_16BIT -> "PCM_16BIT"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM_24BIT"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM_32BIT"
        else -> "enc_$encoding"
    }
}
