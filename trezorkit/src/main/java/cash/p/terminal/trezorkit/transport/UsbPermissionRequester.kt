package cash.p.terminal.trezorkit.transport

import android.hardware.usb.UsbDevice

/**
 * App-owned Android permission prompt.
 *
 * The kit deliberately does not own a Context or UI lifecycle. The coordinator calls this only
 * when [android.hardware.usb.UsbManager.hasPermission] is false.
 */
fun interface UsbPermissionRequester {
    suspend fun requestPermission(device: UsbDevice): Boolean
}
