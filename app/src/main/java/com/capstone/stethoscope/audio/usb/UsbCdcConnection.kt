package com.capstone.stethoscope.audio.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * Minimal USB CDC-ACM host transport — enough to talk to the Pico's serial link, and
 * nothing more.
 *
 * A CDC device splits itself across two interfaces: a *control* interface (class 2)
 * carrying line-state requests, and a *data* interface (class 10) carrying the bulk
 * endpoints. Both matter here.
 */
class UsbCdcConnection(
    private val manager: UsbManager,
    private val device: UsbDevice,
    /**
     * Called once when this connection is released, whether cleanly or after a failed
     * [open]. [UsbStethoscope] uses it to drop the single-claim lock, so a caller that
     * forgets to release cannot deadlock the device for the rest of the session.
     */
    private val onClosed: () -> Unit = {},
) {
    private var connection: UsbDeviceConnection? = null
    private var dataInterface: UsbInterface? = null
    private var controlInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    val isOpen: Boolean get() = connection != null

    /** Largest single bulk transfer this endpoint will accept. */
    val readPacketSize: Int get() = endpointIn?.maxPacketSize ?: 64

    /**
     * Claims the interfaces and brings the line up. Returns null on success, or a
     * user-presentable reason for failure.
     */
    fun open(): String? {
        val conn = manager.openDevice(device)
            ?: return "Could not open the USB device. Permission may have been revoked."
        connection = conn

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            when (iface.interfaceClass) {
                UsbConstants.USB_CLASS_CDC_DATA -> if (dataInterface == null) dataInterface = iface
                UsbConstants.USB_CLASS_COMM -> if (controlInterface == null) controlInterface = iface
            }
        }
        val data = dataInterface ?: run {
            close()
            return "This USB device has no CDC data interface, so it is not the stethoscope."
        }

        for (e in 0 until data.endpointCount) {
            val ep = data.getEndpoint(e)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) endpointIn = ep else endpointOut = ep
        }
        if (endpointIn == null) {
            close()
            return "The stethoscope's CDC interface has no bulk IN endpoint to read from."
        }

        if (!conn.claimInterface(data, true)) {
            close()
            return "Another app is holding the stethoscope's USB interface."
        }
        controlInterface?.let { conn.claimInterface(it, true) }

        val controlId = controlInterface?.id ?: 0
        setLineCoding(conn, controlId)
        // DTR is not optional: MicroPython's USB CDC only considers a host "connected"
        // once DTR is asserted, and silently discards stdout writes until then. Without
        // this the device looks alive but never delivers a single frame.
        setControlLineState(conn, controlId, dtr = true, rts = true)
        return null
    }

    /**
     * Sends [bytes] to the firmware, looping until all of them are gone. Returns true
     * only if every byte was written.
     *
     * The loop is not decoration. `bulkTransfer` returns the number of bytes it actually
     * moved, and a short write is reported as a non-negative count — so the obvious
     * `bulkTransfer(...) >= 0` treats a truncated transfer as a success. That is
     * invisible for the single-byte commands this started out carrying, and corrupting
     * for the ~540-byte chunks the firmware flasher sends: the board would silently
     * receive a partial line of base64, and the only symptom would be an occasional
     * failed checksum at the end of an update.
     */
    fun write(bytes: ByteArray, timeoutMs: Int = WRITE_TIMEOUT_MS): Boolean {
        val conn = connection ?: return false
        val ep = endpointOut ?: return false
        var sent = 0
        while (sent < bytes.size) {
            val n = conn.bulkTransfer(ep, bytes, sent, bytes.size - sent, timeoutMs)
            // Negative is an error; zero means no progress, and retrying forever on a
            // dead endpoint would hang the flash rather than fail it.
            if (n <= 0) return false
            sent += n
        }
        return true
    }

    /**
     * Reads whatever is available into [dest]. Returns the byte count, 0 on timeout
     * (normal — it just means no data yet), or a negative value on error.
     */
    fun read(dest: ByteArray, timeoutMs: Int = READ_TIMEOUT_MS): Int {
        val conn = connection ?: return -1
        val ep = endpointIn ?: return -1
        val n = conn.bulkTransfer(ep, dest, dest.size, timeoutMs)
        // A timeout and a genuine failure are indistinguishable in the return value, so
        // treat negatives as "nothing this time" and let the caller's own timeout decide
        // when to give up.
        return if (n < 0) 0 else n
    }

    fun close() {
        val conn = connection
        if (conn != null) {
            runCatching { setControlLineState(conn, controlInterface?.id ?: 0, dtr = false, rts = false) }
            dataInterface?.let { runCatching { conn.releaseInterface(it) } }
            controlInterface?.let { runCatching { conn.releaseInterface(it) } }
            runCatching { conn.close() }
        }
        connection = null
        dataInterface = null
        controlInterface = null
        endpointIn = null
        endpointOut = null
        // Guarded so a double close — which happens routinely, since PcmSource.close()
        // follows stop() and open() self-closes on failure — releases the lock once.
        if (!released) {
            released = true
            runCatching { onClosed() }
        }
    }

    private var released = false

    // --- CDC class requests ---

    private fun setLineCoding(conn: UsbDeviceConnection, interfaceId: Int) {
        // 115200 8N1. The Pico ignores these values (its CDC is virtual), but some
        // hosts and devices sulk if the request never arrives.
        val baud = 115_200
        val payload = byteArrayOf(
            (baud and 0xFF).toByte(),
            ((baud shr 8) and 0xFF).toByte(),
            ((baud shr 16) and 0xFF).toByte(),
            ((baud shr 24) and 0xFF).toByte(),
            0, // 1 stop bit
            0, // no parity
            8, // data bits
        )
        conn.controlTransfer(
            REQTYPE_CLASS_INTERFACE_OUT, REQ_SET_LINE_CODING, 0, interfaceId,
            payload, payload.size, CONTROL_TIMEOUT_MS,
        )
    }

    private fun setControlLineState(
        conn: UsbDeviceConnection,
        interfaceId: Int,
        dtr: Boolean,
        rts: Boolean,
    ) {
        var value = 0
        if (dtr) value = value or 0x01
        if (rts) value = value or 0x02
        conn.controlTransfer(
            REQTYPE_CLASS_INTERFACE_OUT, REQ_SET_CONTROL_LINE_STATE, value, interfaceId,
            null, 0, CONTROL_TIMEOUT_MS,
        )
    }

    private companion object {
        /** Host-to-device | class request | interface recipient. */
        const val REQTYPE_CLASS_INTERFACE_OUT = 0x21
        const val REQ_SET_LINE_CODING = 0x20
        const val REQ_SET_CONTROL_LINE_STATE = 0x22

        const val CONTROL_TIMEOUT_MS = 500
        const val WRITE_TIMEOUT_MS = 500

        /**
         * Short enough that a stopped stream is noticed promptly, long enough that a
         * normal inter-frame gap (64 ms at 8 kHz) does not read as a stall.
         */
        const val READ_TIMEOUT_MS = 200
    }
}
