package cash.p.terminal.trezorkit.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import cash.p.terminal.trezorkit.TrezorException
import cash.p.terminal.trezorkit.TrezorTransportException
import cash.p.terminal.trezorkit.TrezorUsbDisconnectedException
import cash.p.terminal.trezorkit.TrezorUsbInterfaceUnavailableException
import cash.p.terminal.trezorkit.TrezorUsbOpenFailedException
import cash.p.terminal.trezorkit.TrezorUsbOperationTimeoutException
import cash.p.terminal.trezorkit.TrezorUsbPermissionDeniedException
import cash.p.terminal.trezorkit.TrezorUsbShortPacketException
import cash.p.terminal.trezorkit.TrezorUsbStaleChannelException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicReference

open class TrezorUsbPermissionException :
    TrezorException("USB permission for Trezor device was not granted")

/**
 * Android USB transport that also exposes its active connection as a raw packet channel.
 */
class UsbTrezorTransport internal constructor(
    private val usbManager: UsbManager,
    private val usbDevice: UsbDevice,
    private val dispatcher: CoroutineDispatcher,
) : ITrezorTransport {
    constructor(usbManager: UsbManager, usbDevice: UsbDevice) :
        this(usbManager, usbDevice, Dispatchers.IO)

    private val state = AtomicReference<ConnectionState?>()

    override suspend fun open() = runInterruptible(dispatcher) {
        check(state.get() == null) { "Transport is already open" }
        if (
            usbDevice.vendorId != VENDOR_ID ||
            usbDevice.productId != PRODUCT_ID_FIRMWARE
        ) {
            throw TrezorTransportException(
                "Not a Trezor device in firmware mode " +
                    "(vendorId=${usbDevice.vendorId}, productId=${usbDevice.productId})",
            )
        }
        if (!usbManager.hasPermission(usbDevice)) {
            throw TrezorUsbPermissionDeniedException()
        }
        val selection = selectCommunicationInterface()
            ?: throw TrezorUsbInterfaceUnavailableException()
        val connection = usbManager.openDevice(usbDevice)
            ?: throw TrezorUsbOpenFailedException()
        try {
            if (!connection.claimInterface(selection.usbInterface, true)) {
                throw TrezorUsbInterfaceUnavailableException()
            }
            val opened = ConnectionState(
                connection,
                selection.usbInterface,
                selection.endpointIn,
                selection.endpointOut,
            )
            check(state.compareAndSet(null, opened)) { "Transport is already open" }
        } catch (error: Throwable) {
            try {
                connection.close()
            } catch (cleanupError: RuntimeException) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    override fun close() {
        val current = state.getAndSet(null) ?: return
        var failure: Throwable? = null
        try {
            current.connection.releaseInterface(current.usbInterface)
        } catch (error: RuntimeException) {
            failure = error
        }
        try {
            current.connection.close()
        } catch (error: RuntimeException) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        failure?.let {
            throw TrezorTransportException("Unable to close Trezor USB connection", it)
        }
    }

    override suspend fun writePacket(packet: ByteArray) = runInterruptible(dispatcher) {
        writePacketBlocking(packet)
    }

    override suspend fun readPacket(): ByteArray = runInterruptible(dispatcher) {
        readPacketBlocking(Long.MAX_VALUE)
    }

    internal fun rawChannel(): TrezorRawUsbChannel =
        object : TrezorRawUsbChannel {
            override fun writePacket(packet: ByteArray) {
                writePacketBlocking(packet)
            }

            override fun readPacket(): ByteArray = readPacketBlocking(Long.MAX_VALUE)

            override fun readPacket(deadlineNanos: Long): ByteArray =
                readPacketBlocking(deadlineNanos)

            override fun cancel() {
                close()
            }
        }

    private fun writePacketBlocking(packet: ByteArray) {
        require(packet.size == ITrezorTransport.PACKET_SIZE) {
            "Packet must be exactly ${ITrezorTransport.PACKET_SIZE} bytes, got ${packet.size}"
        }
        val current = requireOpen()
        val written = transfer(current, current.endpointOut, packet, WRITE_TIMEOUT_MS)
        when {
            state.get() !== current -> throw TrezorUsbDisconnectedException()
            written == ITrezorTransport.PACKET_SIZE -> Unit
            written in 1 until ITrezorTransport.PACKET_SIZE ->
                throw TrezorUsbShortPacketException("write", written)
            !isAttached() -> throw TrezorUsbDisconnectedException()
            written != ITrezorTransport.PACKET_SIZE ->
                throw TrezorTransportException("Trezor USB write failed")
        }
    }

    private fun readPacketBlocking(deadlineNanos: Long): ByteArray {
        val current = requireOpen()
        val packet = ByteArray(ITrezorTransport.PACKET_SIZE)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val read = transfer(current, current.endpointIn, packet, pollTimeout(deadlineNanos))
            when {
                state.get() !== current -> throw TrezorUsbDisconnectedException()
                read == ITrezorTransport.PACKET_SIZE -> return packet
                read > 0 -> throw TrezorUsbShortPacketException("read", read)
                read < 0 && !isAttached() -> throw TrezorUsbDisconnectedException()
            }
        }
    }

    private fun transfer(
        current: ConnectionState,
        endpoint: UsbEndpoint,
        packet: ByteArray,
        timeoutMs: Int,
    ): Int =
        try {
            current.connection.bulkTransfer(endpoint, packet, packet.size, timeoutMs)
        } catch (error: RuntimeException) {
            if (state.get() !== current || !isAttached()) {
                throw TrezorUsbDisconnectedException()
            }
            throw TrezorTransportException("Trezor USB transfer failed", error)
        }

    private fun requireOpen(): ConnectionState =
        state.get() ?: throw TrezorUsbStaleChannelException()

    private fun isAttached(): Boolean =
        usbManager.deviceList.values.any { it.deviceName == usbDevice.deviceName }

    private fun pollTimeout(deadlineNanos: Long): Int {
        if (deadlineNanos == Long.MAX_VALUE) return POLL_TIMEOUT_MS
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0) throw TrezorUsbOperationTimeoutException()
        val remainingMillis =
            (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
        return remainingMillis.coerceAtMost(POLL_TIMEOUT_MS.toLong()).toInt()
    }

    private fun selectCommunicationInterface(): InterfaceSelection? {
        var fallback: InterfaceSelection? = null
        for (index in 0 until usbDevice.interfaceCount) {
            val usbInterface = usbDevice.getInterface(index)
            val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
            val endpointIn = endpoints.pickEndpoint(UsbConstants.USB_DIR_IN) ?: continue
            val endpointOut = endpoints.pickEndpoint(UsbConstants.USB_DIR_OUT) ?: continue
            val selection = InterfaceSelection(usbInterface, endpointIn, endpointOut)
            if (usbInterface.interfaceClass == VENDOR_INTERFACE_CLASS) return selection
            if (fallback == null) fallback = selection
        }
        return fallback
    }

    private fun List<UsbEndpoint>.pickEndpoint(direction: Int): UsbEndpoint? {
        val matching = filter { it.direction == direction }
        return matching.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            ?: matching.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
    }

    private data class ConnectionState(
        val connection: UsbDeviceConnection,
        val usbInterface: UsbInterface,
        val endpointIn: UsbEndpoint,
        val endpointOut: UsbEndpoint,
    )

    private data class InterfaceSelection(
        val usbInterface: UsbInterface,
        val endpointIn: UsbEndpoint,
        val endpointOut: UsbEndpoint,
    )

    companion object {
        const val VENDOR_ID = 0x1209
        const val PRODUCT_ID_FIRMWARE = 0x53C1
        const val PRODUCT_ID_BOOTLOADER = 0x53C0
        private const val VENDOR_INTERFACE_CLASS = 0xFF
        private const val WRITE_TIMEOUT_MS = 5_000
        private const val POLL_TIMEOUT_MS = 1_000
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
