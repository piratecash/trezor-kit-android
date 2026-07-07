package cash.p.terminal.trezorkit

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
class TrezorTransportException(message: String, cause: Throwable? = null) : TrezorException(message, cause)

/** The device is connected but has no seed (or is in bootloader mode) - it has not completed setup. */
class TrezorNotInitializedException(message: String) : TrezorException(message)
