package cash.p.terminal.trezorkit.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import cash.p.terminal.trezorkit.TrezorException
import cash.p.terminal.trezorkit.TrezorTransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Thrown by [UsbTrezorTransport.open] when the caller has not been granted USB permission yet. */
class TrezorUsbPermissionException : TrezorException("USB permission for Trezor device was not granted")

/**
 * [ITrezorTransport] implementation over the Android USB Host API for a Trezor device
 * in firmware mode (vendor id [VENDOR_ID], product id [PRODUCT_ID_FIRMWARE]).
 *
 * The caller is responsible for obtaining USB permission for [usbDevice] beforehand
 * (via [UsbManager.requestPermission]) - this class only checks that permission was
 * already granted and fails with [TrezorUsbPermissionException] otherwise.
 */
class UsbTrezorTransport(
    private val usbManager: UsbManager,
    private val usbDevice: UsbDevice,
) : ITrezorTransport {

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        if (usbDevice.vendorId != VENDOR_ID || usbDevice.productId != PRODUCT_ID_FIRMWARE) {
            throw TrezorTransportException(
                "Not a Trezor device in firmware mode (vendorId=${usbDevice.vendorId}, productId=${usbDevice.productId})"
            )
        }
        if (!usbManager.hasPermission(usbDevice)) {
            throw TrezorUsbPermissionException()
        }

        val selection = selectCommunicationInterface(usbDevice)
            ?: throw TrezorTransportException("No interface with an IN/OUT endpoint pair found on Trezor device")
        val conn = usbManager.openDevice(usbDevice)
            ?: throw TrezorTransportException("Unable to open USB connection to Trezor device")
        if (!conn.claimInterface(selection.iface, true)) {
            conn.close()
            throw TrezorTransportException("Unable to claim USB interface on Trezor device")
        }

        usbInterface = selection.iface
        endpointIn = selection.endpointIn
        endpointOut = selection.endpointOut
        connection = conn
        Unit
    }

    override fun close() {
        connection?.let { conn ->
            usbInterface?.let { conn.releaseInterface(it) }
            conn.close()
        }
        connection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null
    }

    override suspend fun writePacket(packet: ByteArray) = withContext(Dispatchers.IO) {
        require(packet.size == ITrezorTransport.PACKET_SIZE) {
            "Packet must be exactly ${ITrezorTransport.PACKET_SIZE} bytes, got ${packet.size}"
        }
        val conn = connection ?: throw TrezorTransportException("Transport is not open")
        val endpoint = endpointOut ?: throw TrezorTransportException("Transport is not open")
        val written = conn.bulkTransfer(endpoint, packet, packet.size, WRITE_TIMEOUT_MS)
        if (written != ITrezorTransport.PACKET_SIZE) {
            throw TrezorTransportException(
                "USB write failed: wrote $written of ${ITrezorTransport.PACKET_SIZE} bytes"
            )
        }
    }

    /**
     * Polls for one 64-byte packet. A device that is waiting for a user action (button press,
     * on-device passphrase entry) may not send anything for a long time - that is not a
     * transport error, so a timed-out poll simply repeats. [POLL_TIMEOUT_MS] is only the polling
     * interval; the caller (see `TrezorSession.OPERATION_TIMEOUT_MS`) is responsible for the
     * user-facing deadline via coroutine cancellation, which [ensureActive] surfaces here.
     */
    override suspend fun readPacket(): ByteArray = withContext(Dispatchers.IO) {
        val conn = connection ?: throw TrezorTransportException("Transport is not open")
        val endpoint = endpointIn ?: throw TrezorTransportException("Transport is not open")
        val buffer = ByteArray(ITrezorTransport.PACKET_SIZE)
        var read = 0
        while (read != ITrezorTransport.PACKET_SIZE) {
            read = conn.bulkTransfer(endpoint, buffer, buffer.size, POLL_TIMEOUT_MS)
            if (read in 1 until ITrezorTransport.PACKET_SIZE) {
                throw TrezorTransportException(
                    "USB read failed: read $read of ${ITrezorTransport.PACKET_SIZE} bytes"
                )
            }
            if (read <= 0) {
                coroutineContext.ensureActive()
            }
        }
        buffer
    }

    /**
     * Picks the interface Trezor talks its wire protocol over. Trezor Safe devices expose several
     * interfaces (data channel plus WebUSB/CDC/HID), and not every vendor-class interface carries a
     * usable endpoint pair, so we scan them all and take the first with both an IN and an OUT
     * endpoint - preferring the vendor-class interface (the actual data channel) over any fallback.
     */
    private fun selectCommunicationInterface(device: UsbDevice): InterfaceSelection? {
        var fallback: InterfaceSelection? = null
        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            val endpoints = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
            logInterface(index, iface, endpoints)

            val epIn = pickEndpoint(endpoints, UsbConstants.USB_DIR_IN) ?: continue
            val epOut = pickEndpoint(endpoints, UsbConstants.USB_DIR_OUT) ?: continue
            val selection = InterfaceSelection(iface, epIn, epOut)

            if (iface.interfaceClass == VENDOR_INTERFACE_CLASS) return selection
            if (fallback == null) fallback = selection
        }
        return fallback
    }

    /** Prefers a bulk endpoint but accepts interrupt - Android's [UsbDeviceConnection.bulkTransfer] drives both. */
    private fun pickEndpoint(endpoints: List<UsbEndpoint>, direction: Int): UsbEndpoint? {
        val matching = endpoints.filter { it.direction == direction }
        return matching.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            ?: matching.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
    }

    private fun logInterface(index: Int, iface: UsbInterface, endpoints: List<UsbEndpoint>) {
        Timber.d(
            "iface[$index] class=0x${iface.interfaceClass.toString(16)} " +
                "sub=0x${iface.interfaceSubclass.toString(16)} " +
                "proto=0x${iface.interfaceProtocol.toString(16)} endpoints=${iface.endpointCount}"
        )
        endpoints.forEach { ep ->
            val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
            Timber.d("  ep addr=0x${ep.address.toString(16)} type=${ep.type} dir=$dir")
        }
    }

    private data class InterfaceSelection(
        val iface: UsbInterface,
        val endpointIn: UsbEndpoint,
        val endpointOut: UsbEndpoint,
    )

    companion object {
        const val VENDOR_ID = 0x1209
        const val PRODUCT_ID_FIRMWARE = 0x53C1
        const val PRODUCT_ID_BOOTLOADER = 0x53C0
        private const val VENDOR_INTERFACE_CLASS = 0xFF
        private const val WRITE_TIMEOUT_MS = 5_000
        private const val POLL_TIMEOUT_MS = 5_000
    }
}
