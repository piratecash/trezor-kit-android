package cash.p.terminal.trezorkit.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import cash.p.terminal.trezorkit.TrezorUsbDeviceNotFoundException
import cash.p.terminal.trezorkit.TrezorTransportException
import cash.p.terminal.trezorkit.TrezorUsbAcquireTimeoutException
import cash.p.terminal.trezorkit.TrezorUsbDisconnectedException
import cash.p.terminal.trezorkit.TrezorUsbOperationTimeoutException
import cash.p.terminal.trezorkit.TrezorUsbPermissionDeniedException
import cash.p.terminal.trezorkit.TrezorUsbShortPacketException
import cash.p.terminal.trezorkit.TrezorUsbStaleChannelException
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TrezorUsbCoordinatorTest {
    private val usbManager = mockk<UsbManager>()
    private val device = mockk<UsbDevice>()
    private val usbInterface = mockk<UsbInterface>()
    private val endpointIn = mockk<UsbEndpoint>()
    private val endpointOut = mockk<UsbEndpoint>()
    private val connection = mockk<UsbDeviceConnection>()

    @Before
    fun setUp() {
        every { usbManager.deviceList } returns hashMapOf(DEVICE_NAME to device)
        every { usbManager.hasPermission(device) } returns true
        every { usbManager.openDevice(device) } returns connection
        every { device.vendorId } returns UsbTrezorTransport.VENDOR_ID
        every { device.productId } returns UsbTrezorTransport.PRODUCT_ID_FIRMWARE
        every { device.deviceName } returns DEVICE_NAME
        every { device.interfaceCount } returns 1
        every { device.getInterface(0) } returns usbInterface
        every { usbInterface.interfaceClass } returns 0xFF
        every { usbInterface.endpointCount } returns 2
        every { usbInterface.getEndpoint(0) } returns endpointIn
        every { usbInterface.getEndpoint(1) } returns endpointOut
        every { endpointIn.direction } returns UsbConstants.USB_DIR_IN
        every { endpointIn.type } returns UsbConstants.USB_ENDPOINT_XFER_BULK
        every { endpointOut.direction } returns UsbConstants.USB_DIR_OUT
        every { endpointOut.type } returns UsbConstants.USB_ENDPOINT_XFER_BULK
        every { connection.claimInterface(usbInterface, true) } returns true
        every { connection.releaseInterface(usbInterface) } returns true
        every { connection.close() } just Runs
        every {
            connection.bulkTransfer(endpointOut, any(), ITrezorTransport.PACKET_SIZE, any())
        } returns ITrezorTransport.PACKET_SIZE
        every {
            connection.bulkTransfer(endpointIn, any(), ITrezorTransport.PACKET_SIZE, any())
        } answers {
            secondArg<ByteArray>().fill(READ_BYTE)
            ITrezorTransport.PACKET_SIZE
        }
    }

    @Test
    fun withRawChannel_validDevice_ownsConnectionLifecycle() = runTest {
        coordinator().withRawChannel { channel ->
            channel.writePacket(ByteArray(ITrezorTransport.PACKET_SIZE))
            assertArrayEquals(
                ByteArray(ITrezorTransport.PACKET_SIZE) { READ_BYTE },
                channel.readPacket(),
            )
        }

        verify(exactly = 1) { connection.claimInterface(usbInterface, true) }
        verify(exactly = 1) { connection.releaseInterface(usbInterface) }
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun withRawChannel_successfulPackets_doNotQueryAttachmentPerPacket() = runTest {
        coordinator().withRawChannel { channel ->
            channel.writePacket(ByteArray(ITrezorTransport.PACKET_SIZE))
            channel.readPacket()
        }

        verify(exactly = 1) { usbManager.deviceList }
    }

    @Test
    fun withRawChannel_permissionMissing_requestsBeforeOpening() = runTest {
        val permissionRequester = mockk<UsbPermissionRequester>()
        every { usbManager.hasPermission(device) } returnsMany listOf(false, true)
        coEvery { permissionRequester.requestPermission(device) } returns true

        coordinator(permissionRequester).withRawChannel {}

        coVerify(exactly = 1) { permissionRequester.requestPermission(device) }
        verify(exactly = 1) { usbManager.openDevice(device) }
    }

    @Test
    fun withRawChannel_permissionDenied_doesNotOpenDevice() = runTest {
        val permissionRequester = mockk<UsbPermissionRequester>()
        every { usbManager.hasPermission(device) } returns false
        coEvery { permissionRequester.requestPermission(device) } returns false

        assertSuspendThrows<TrezorUsbPermissionDeniedException> {
            coordinator(permissionRequester).withRawChannel {}
        }

        verify(exactly = 0) { usbManager.openDevice(any()) }
    }

    @Test
    fun withRawChannel_slowPermission_doesNotConsumeAcquireTimeout() = runTest {
        val permissionRequester = mockk<UsbPermissionRequester>()
        every { usbManager.hasPermission(device) } returnsMany listOf(false, true)
        coEvery { permissionRequester.requestPermission(device) } coAnswers {
            delay(ACQUIRE_TIMEOUT_MS * 2)
            true
        }

        coordinator(permissionRequester, ACQUIRE_TIMEOUT_MS).withRawChannel {}

        verify(exactly = 1) { usbManager.openDevice(device) }
    }

    @Test
    fun withRawChannel_outerTimeoutDuringPermission_preservesCancellation() = runTest {
        val permissionRequester = mockk<UsbPermissionRequester>()
        every { usbManager.hasPermission(device) } returns false
        coEvery { permissionRequester.requestPermission(device) } coAnswers {
            CompletableDeferred<Boolean>().await()
        }

        assertSuspendThrows<TimeoutCancellationException> {
            withTimeout(ACQUIRE_TIMEOUT_MS) {
                coordinator(permissionRequester, ACQUIRE_TIMEOUT_MS * 10).withRawChannel {}
            }
        }
    }

    @Test
    fun withRawChannel_noDevice_throwsTypedError() = runTest {
        every { usbManager.deviceList } returns hashMapOf()

        assertSuspendThrows<TrezorUsbDeviceNotFoundException> {
            coordinator().withRawChannel {}
        }
    }

    @Test
    fun withRawChannel_concurrentCalls_areSerialized() = runTest {
        val coordinator = coordinator()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = async {
            coordinator.withRawChannel {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            coordinator.withRawChannel {
                secondEntered.complete(Unit)
            }
        }

        yield()
        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertTrue(secondEntered.isCompleted)
        verify(exactly = 2) { usbManager.openDevice(device) }
    }

    @Test
    fun withRawChannel_waiterExceedsAcquireTimeout_throwsTypedError() = runTest {
        val coordinator = coordinator(acquireTimeoutMs = ACQUIRE_TIMEOUT_MS)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            coordinator.withRawChannel {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        assertSuspendThrows<TrezorUsbAcquireTimeoutException> {
            coordinator.withRawChannel {}
        }

        releaseFirst.complete(Unit)
        first.await()
    }

    @Test
    fun readPacket_expiredDeadline_doesNotPollUsb() = runTest {
        assertSuspendThrows<TrezorUsbOperationTimeoutException> {
            coordinator().withRawChannel {
                it.readPacket(System.nanoTime() - 1)
            }
        }

        verify(exactly = 0) {
            connection.bulkTransfer(endpointIn, any(), ITrezorTransport.PACKET_SIZE, any())
        }
    }

    @Test
    fun readPacket_shortPacket_throwsTypedError() = runTest {
        every {
            connection.bulkTransfer(endpointIn, any(), ITrezorTransport.PACKET_SIZE, any())
        } returns SHORT_PACKET_SIZE

        assertSuspendThrows<TrezorUsbShortPacketException> {
            coordinator().withRawChannel { it.readPacket() }
        }
    }

    @Test
    fun withRawChannel_blockAndCloseFail_preservesBlockFailure() = runTest {
        val blockFailure = IllegalStateException("block")
        every { connection.releaseInterface(usbInterface) } throws IllegalStateException("close")

        val actual = assertSuspendThrows<IllegalStateException> {
            coordinator().withRawChannel { throw blockFailure }
        }

        assertSame(blockFailure, actual)
        assertTrue(actual.suppressed.isNotEmpty())
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun rawChannel_cancelDuringRead_unblocksAndIsIdempotent() = runTest {
        val transferStarted = CountDownLatch(1)
        val releaseTransfer = CountDownLatch(1)
        every {
            connection.bulkTransfer(endpointIn, any(), ITrezorTransport.PACKET_SIZE, any())
        } answers {
            transferStarted.countDown()
            releaseTransfer.await(5, TimeUnit.SECONDS)
            -1
        }
        every { connection.close() } answers {
            releaseTransfer.countDown()
        }

        coordinator().withRawChannel { channel ->
            val failure = async(Dispatchers.IO) {
                try {
                    channel.readPacket()
                    null
                } catch (error: Throwable) {
                    error
                }
            }
            assertTrue(transferStarted.await(5, TimeUnit.SECONDS))

            channel.cancel()
            channel.cancel()

            assertTrue(failure.await() is TrezorUsbDisconnectedException)
        }

        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun rawChannel_afterLeaseClosed_rejectsStaleUse() = runTest {
        lateinit var channel: TrezorRawUsbChannel
        coordinator().withRawChannel {
            channel = it
        }

        assertSuspendThrows<TrezorUsbStaleChannelException> {
            channel.readPacket()
        }
    }

    @Test
    fun suspendRead_cancelled_releasesCoordinatorLease() = runTest {
        val transferStarted = CountDownLatch(1)
        every {
            connection.bulkTransfer(endpointIn, any(), ITrezorTransport.PACKET_SIZE, any())
        } answers {
            transferStarted.countDown()
            CountDownLatch(1).await()
            -1
        }
        val coordinator = coordinator()
        val read = async(Dispatchers.IO) {
            coordinator.withTransport { transport ->
                transport.open()
                transport.readPacket()
            }
        }
        assertTrue(transferStarted.await(5, TimeUnit.SECONDS))

        read.cancelAndJoin()
        every {
            connection.bulkTransfer(endpointIn, any(), ITrezorTransport.PACKET_SIZE, any())
        } returns ITrezorTransport.PACKET_SIZE

        coordinator.withRawChannel {}

        verify(exactly = 2) { usbManager.openDevice(device) }
    }

    @Test
    fun transport_wrongVendor_rejectsDeviceBeforeOpen() = runTest {
        every { device.vendorId } returns 0x0001

        assertSuspendThrows<TrezorTransportException> {
            UsbTrezorTransport(usbManager, device, Dispatchers.Unconfined).open()
        }

        verify(exactly = 0) { usbManager.openDevice(any()) }
    }

    @Test
    fun transport_bootloaderProduct_rejectsDeviceBeforeOpen() = runTest {
        every { device.productId } returns UsbTrezorTransport.PRODUCT_ID_BOOTLOADER

        assertSuspendThrows<TrezorTransportException> {
            UsbTrezorTransport(usbManager, device, Dispatchers.Unconfined).open()
        }

        verify(exactly = 0) { usbManager.openDevice(any()) }
    }

    private fun coordinator(
        permissionRequester: UsbPermissionRequester = UsbPermissionRequester { true },
        acquireTimeoutMs: Long = TrezorUsbCoordinator.DEFAULT_ACQUIRE_TIMEOUT_MS,
    ) = TrezorUsbCoordinator(
        usbManager = usbManager,
        permissionRequester = permissionRequester,
        dispatcher = Dispatchers.Unconfined,
        acquireTimeoutMs = acquireTimeoutMs,
    )

    private companion object {
        const val DEVICE_NAME = "test-trezor"
        const val READ_BYTE: Byte = 0x2A
        const val SHORT_PACKET_SIZE = 12
        const val ACQUIRE_TIMEOUT_MS = 10L
    }
}
