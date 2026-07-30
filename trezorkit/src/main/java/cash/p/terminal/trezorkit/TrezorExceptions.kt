package cash.p.terminal.trezorkit

import cash.p.terminal.trezorkit.transport.ITrezorTransport
import cash.p.terminal.trezorkit.transport.TrezorUsbPermissionException

/**
 * Base type for all errors surfaced by trezorkit. Neutral - no Android or feature-module
 * dependency; callers map it to their own UI-facing error types.
 */
open class TrezorException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The device returned a `Failure` response. [code] is the wire `FailureType` number, if present. */
class TrezorFailureException(val code: Int?, message: String) : TrezorException(message)

/**
 * The operation was cancelled - by the user on the device, by a `Failure(ActionCancelled)`
 * response, or by a disconnect mid-operation.
 */
class TrezorCancelledException(message: String) : TrezorException(message)

/** A transport-level error occurred: an I/O failure or an operation timing out. */
open class TrezorTransportException(message: String, cause: Throwable? = null) : TrezorException(message, cause)

/** No Trezor in firmware mode is currently attached over USB. */
class TrezorUsbDeviceNotFoundException : TrezorTransportException("No Trezor found. Connect it with a cable.")

/** The user denied Android USB access for the attached Trezor. */
class TrezorUsbPermissionDeniedException : TrezorUsbPermissionException()

/** Android could not open an attached, permitted USB device. */
class TrezorUsbOpenFailedException(cause: Throwable? = null) :
    TrezorTransportException("Unable to open USB connection to Trezor device.", cause)

/** The attached device has no usable packet interface, or Android could not claim it. */
class TrezorUsbInterfaceUnavailableException :
    TrezorTransportException("Unable to claim a compatible Trezor USB interface.")

/** Acquiring the shared Trezor USB connection exceeded its deadline. */
class TrezorUsbAcquireTimeoutException :
    TrezorTransportException("Timed out acquiring the shared Trezor USB connection.")

/** The active raw USB operation did not return a complete packet before its absolute deadline. */
class TrezorUsbOperationTimeoutException :
    TrezorTransportException("Trezor USB operation timed out.")

/** The active Trezor was detached, or its connection was cancelled while an operation was running. */
class TrezorUsbDisconnectedException : TrezorTransportException("Trezor was disconnected.")

/** A raw channel was used after the coordinator had released its lease. */
class TrezorUsbStaleChannelException : TrezorTransportException("Trezor USB channel is no longer active.")

/** USB returned a packet fragment even though Trezor wire packets are always exactly 64 bytes. */
class TrezorUsbShortPacketException(
    operation: String,
    actualSize: Int,
) : TrezorTransportException(
    "USB $operation returned $actualSize of ${ITrezorTransport.PACKET_SIZE} bytes.",
)

/** The device is connected but has no seed (or is in bootloader mode) - it has not completed setup. */
class TrezorNotInitializedException(message: String) : TrezorException(message)
