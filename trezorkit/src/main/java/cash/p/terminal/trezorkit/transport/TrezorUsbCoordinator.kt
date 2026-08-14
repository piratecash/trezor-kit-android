package cash.p.terminal.trezorkit.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cash.p.terminal.trezorkit.TrezorNotInitializedException
import cash.p.terminal.trezorkit.TrezorUsbAcquireTimeoutException
import cash.p.terminal.trezorkit.TrezorUsbDeviceNotFoundException
import cash.p.terminal.trezorkit.TrezorUsbDisconnectedException
import cash.p.terminal.trezorkit.TrezorUsbPermissionDeniedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes access to the one Android USB connection shared by protobuf and native clients.
 */
class TrezorUsbCoordinator(
    private val usbManager: UsbManager,
    private val permissionRequester: UsbPermissionRequester,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val acquireTimeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS,
) {
    init {
        require(acquireTimeoutMs > 0) { "Acquire timeout must be positive" }
    }

    private val mutex = Mutex()

    suspend fun <T> withTransport(block: suspend (ITrezorTransport) -> T): T =
        withUsbTransport(block)

    suspend fun <T> withRawChannel(block: suspend (TrezorRawUsbChannel) -> T): T =
        withUsbTransport { transport ->
            transport.open()
            block(transport.rawChannel())
        }

    private suspend fun <T> withUsbTransport(
        block: suspend (UsbTrezorTransport) -> T,
    ): T =
        withDevice { device ->
            val transport = UsbTrezorTransport(usbManager, device, dispatcher)
            useTransport(transport) {
                block(transport)
            }
        }

    private suspend fun <T> useTransport(
        transport: UsbTrezorTransport,
        block: suspend () -> T,
    ): T {
        var primaryFailure: Throwable? = null
        try {
            return block()
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                transport.close()
            } catch (cleanupFailure: Throwable) {
                primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }

    private suspend fun <T> withDevice(block: suspend (UsbDevice) -> T): T {
        acquireTrezorUsbMutex(mutex, acquireTimeoutMs)
        try {
            val device = findDevice()
            ensurePermission(device)
            return block(device)
        } finally {
            mutex.unlock()
        }
    }

    private fun findDevice(): UsbDevice {
        val trezorDevices = usbManager.deviceList.values.filter {
            it.vendorId == UsbTrezorTransport.VENDOR_ID
        }
        return trezorDevices.firstOrNull {
            it.productId == UsbTrezorTransport.PRODUCT_ID_FIRMWARE
        } ?: if (trezorDevices.any {
                it.productId == UsbTrezorTransport.PRODUCT_ID_BOOTLOADER
            }) {
            throw TrezorNotInitializedException(
                "Trezor is in bootloader mode. Complete setup on the device.",
            )
        } else {
            throw TrezorUsbDeviceNotFoundException()
        }
    }

    private suspend fun ensurePermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) return
        val granted = permissionRequester.requestPermission(device)
        if (!isAttached(device)) throw TrezorUsbDisconnectedException()
        if (!granted || !usbManager.hasPermission(device)) {
            throw TrezorUsbPermissionDeniedException()
        }
    }

    private fun isAttached(device: UsbDevice): Boolean =
        usbManager.deviceList.values.any { it.deviceName == device.deviceName }

    companion object {
        const val DEFAULT_ACQUIRE_TIMEOUT_MS = 60_000L
    }
}

internal suspend fun acquireTrezorUsbMutex(
    mutex: Mutex,
    acquireTimeoutMs: Long,
    afterLockAcquired: suspend () -> Unit = {},
) {
    var lockAcquired = false
    var handoffComplete = false
    try {
        withTimeoutOrNull(acquireTimeoutMs) {
            mutex.lock()
            lockAcquired = true
            afterLockAcquired()
        } ?: throw TrezorUsbAcquireTimeoutException()
        handoffComplete = true
    } finally {
        if (lockAcquired && !handoffComplete) {
            mutex.unlock()
        }
    }
}
