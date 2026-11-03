package com.capstone.stethoscope.audio.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds the stethoscope on the USB bus and holds its runtime permission.
 *
 * Unlike audio devices, a raw USB device needs explicit per-device user consent before
 * it can be opened, and that consent arrives asynchronously via a broadcast. This class
 * owns that dance so the capture layer can simply ask whether it may proceed.
 *
 * Registered once for the app's lifetime from
 * [com.capstone.stethoscope.StethoscopeApp].
 */
class UsbStethoscope(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Describes what the app can currently do with the stethoscope. */
    sealed interface State {
        data object NotConnected : State
        data class NeedsPermission(val device: UsbDevice) : State
        data class Ready(val device: UsbDevice) : State
    }

    private val _state = MutableStateFlow<State>(State.NotConnected)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Single-claim lock over the CDC interface. See [connect]. */
    private val inUse = AtomicBoolean(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION,
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                -> refresh()
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Only the system broadcasts these to us, so the receiver stays unexported.
        ContextCompat.registerReceiver(
            appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    /** Re-examines the bus. Safe to call from the UI, e.g. on a diagnostics refresh. */
    fun refresh() {
        val device = findDevice()
        _state.value = when {
            device == null -> State.NotConnected
            manager.hasPermission(device) -> State.Ready(device)
            else -> State.NeedsPermission(device)
        }
    }

    /**
     * Asks the user to grant access. The result arrives as a broadcast and updates
     * [state]; nothing happens synchronously.
     */
    fun requestPermission() {
        val device = (_state.value as? State.NeedsPermission)?.device ?: findDevice() ?: return
        // FLAG_MUTABLE is required: the system fills in EXTRA_DEVICE and
        // EXTRA_PERMISSION_GRANTED on this intent, which an immutable one forbids.
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val intent = PendingIntent.getBroadcast(
            appContext, 0, Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName), flags,
        )
        manager.requestPermission(device, intent)
    }

    /** The device we would capture from, or null. */
    fun findDevice(): UsbDevice? {
        val devices = manager.deviceList.values
        // Prefer a Raspberry Pi board, but accept any CDC device so a different
        // microcontroller still works without a code change.
        return devices.firstOrNull { it.vendorId == VID_RASPBERRY_PI && hasCdcData(it) }
            ?: devices.firstOrNull { hasCdcData(it) }
    }

    /**
     * Opens a connection, or returns the reason it could not be opened.
     *
     * Only one connection may exist at a time and this enforces it. The board's CDC
     * interface can be claimed once, and three things now want it: live monitoring,
     * recording, and the firmware check that runs on plug-in. Without the lock, a
     * firmware check landing while the clinician taps Record produced an opaque
     * "another app is holding the interface" from the kernel; with it, the loser gets a
     * sentence that says what is actually going on.
     *
     * The lock is released by [UsbCdcConnection.close], so the caller's existing cleanup
     * is enough — there is nothing extra to remember.
     */
    fun connect(): Result<UsbCdcConnection> {
        val device = findDevice()
            ?: return Result.failure(IllegalStateException("No stethoscope is connected."))
        if (!manager.hasPermission(device)) {
            requestPermission()
            return Result.failure(
                IllegalStateException("Waiting for USB permission — tap Allow and try again.")
            )
        }
        if (!inUse.compareAndSet(false, true)) {
            return Result.failure(
                IllegalStateException(
                    "The stethoscope is busy — stop listening or recording, or wait for the " +
                        "firmware check to finish, then try again."
                )
            )
        }
        val conn = UsbCdcConnection(manager, device, onClosed = { inUse.set(false) })
        val failure = conn.open()
        if (failure != null) {
            // open() self-closes, which releases the lock; belt and braces in case a
            // future failure path in open() returns without closing.
            inUse.set(false)
            return Result.failure(IllegalStateException(failure))
        }
        return Result.success(conn)
    }

    /** True while something holds the device, so the UI can explain why an action waited. */
    val isBusy: Boolean get() = inUse.get()

    /** One-line description for the diagnostics screen. */
    fun describe(): String = when (val s = _state.value) {
        is State.NotConnected -> "No CDC stethoscope detected on USB."
        is State.NeedsPermission ->
            "Found %s (VID 0x%04X PID 0x%04X) — permission not yet granted.".format(
                s.device.productName ?: s.device.deviceName, s.device.vendorId, s.device.productId,
            )
        is State.Ready ->
            "Ready: %s (VID 0x%04X PID 0x%04X).".format(
                s.device.productName ?: s.device.deviceName, s.device.vendorId, s.device.productId,
            )
    }

    private fun hasCdcData(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) return true
        }
        return false
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.capstone.stethoscope.USB_PERMISSION"

        /** Raspberry Pi Trading Ltd — the RP2040's vendor ID. */
        const val VID_RASPBERRY_PI = 0x2E8A
    }
}
