package cash.p.terminal.trezorkit.protocol

import cash.p.terminal.trezorkit.TrezorCancelledException
import cash.p.terminal.trezorkit.TrezorException
import cash.p.terminal.trezorkit.TrezorFailureException
import cash.p.terminal.trezorkit.TrezorInteractionRequiredException
import cash.p.terminal.trezorkit.TrezorNotInitializedException
import cash.p.terminal.trezorkit.TrezorTransportException
import cash.p.terminal.trezorkit.transport.ITrezorTransport
import com.google.protobuf.MessageLite
import com.satoshilabs.trezor.lib.protobuf.TrezorMessage.MessageType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.ButtonAck
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.ButtonRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.Failure
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.PassphraseAck
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.PassphraseRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.Cancel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Events emitted by [TrezorSession] so a UI layer can prompt the user for an on-device action. */
sealed class TrezorSessionEvent {
    data object ButtonRequested : TrezorSessionEvent()
    data object PassphraseRequested : TrezorSessionEvent()
}

/** How [TrezorSession] answers device prompts: [Interactive] acks them, [Unattended] cancels them. */
enum class TrezorPromptPolicy {
    Interactive,
    Unattended,
}

/** Receiver for the [TrezorSession.withSession] block. The session lock is already held. */
interface TrezorSessionScope {
    suspend fun exchange(message: MessageLite): MessageLite

    /** Receives the next final response without sending another request. */
    suspend fun receive(): MessageLite
}

/**
 * Runs a Trezor wire-protocol v1 session over [transport], transparently handling the
 * interactive device prompts (button confirmation, on-device passphrase entry) that can
 * appear between a request and its final response.
 *
 * Every [exchange] call and every [withSession] scope is serialized through a single
 * [Mutex] so the transport's packet stream is never interleaved between concurrent callers,
 * and a scoped session (`open -> block -> close`) runs as one atomic critical section.
 */
class TrezorSession @JvmOverloads constructor(
    private val transport: ITrezorTransport,
    private val registry: MessageTypeRegistry,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val promptPolicy: TrezorPromptPolicy = TrezorPromptPolicy.Interactive,
) {
    private val mutex = Mutex()
    private val mutableEvents = MutableSharedFlow<TrezorSessionEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<TrezorSessionEvent> = mutableEvents

    private val sessionScope = object : TrezorSessionScope {
        override suspend fun exchange(message: MessageLite): MessageLite = exchangeInternal(message)

        override suspend fun receive(): MessageLite = receiveInternal()
    }

    /**
     * Opens the transport, runs [block] with an [exchange] bound to the already-held lock,
     * and closes the transport afterwards - all as one atomic critical section, so no other
     * [exchange] or [withSession] call can interleave with it.
     */
    suspend fun <T> withSession(block: suspend TrezorSessionScope.() -> T): T = mutex.withLock {
        transport.open()
        try {
            sessionScope.block()
        } finally {
            transport.close()
        }
    }

    /**
     * Sends [message] and returns the device's final response, auto-acknowledging any
     * ButtonRequest/PassphraseRequest prompts along the way. For a one-off exchange outside
     * an explicit [withSession] scope.
     */
    suspend fun exchange(message: MessageLite): MessageLite = mutex.withLock { exchangeInternal(message) }

    /** Body of [exchange], without the lock - callers must already hold [mutex]. */
    private suspend fun exchangeInternal(message: MessageLite): MessageLite =
        mapTimeoutToTransportException {
            withTimeout(OPERATION_TIMEOUT_MS) {
                withContext(dispatcher) {
                    send(message)
                    readResponse()
                }
            }
        }

    /** Receives one final response while retaining the normal prompt and timeout handling. */
    private suspend fun receiveInternal(): MessageLite =
        mapTimeoutToTransportException {
            withTimeout(OPERATION_TIMEOUT_MS) {
                withContext(dispatcher) { readResponse() }
            }
        }

    private suspend fun readResponse(): MessageLite {
        while (true) {
            val decoded = readMessage()
            if (decoded.msgType == MessageType.MessageType_PinMatrixRequest_VALUE) {
                throw TrezorCancelledException("Host PIN entry is not supported")
            }

            when (val response = registry.parse(decoded.msgType, decoded.payload)) {
                is ButtonRequest -> {
                    rejectPromptIfUnattended()
                    mutableEvents.emit(TrezorSessionEvent.ButtonRequested)
                    send(ButtonAck.newBuilder().build())
                }
                is PassphraseRequest -> {
                    rejectPromptIfUnattended()
                    mutableEvents.emit(TrezorSessionEvent.PassphraseRequested)
                    send(PassphraseAck.newBuilder().setOnDevice(true).build())
                }
                is Failure -> throw response.toException()
                else -> return response
            }
        }
    }

    private suspend fun rejectPromptIfUnattended() {
        if (promptPolicy == TrezorPromptPolicy.Interactive) return
        send(MessageType.MessageType_Cancel_VALUE, Cancel.getDefaultInstance())
        readMessage() // the device answers Cancel with a Failure
        throw TrezorInteractionRequiredException()
    }

    private suspend fun send(message: MessageLite) = send(registry.typeOf(message), message)

    private suspend fun send(msgType: Int, message: MessageLite) {
        val packets = FramingCodec.encode(msgType, message.toByteArray())
        packets.forEach { packet -> withTimeout(WRITE_TIMEOUT_MS) { transport.writePacket(packet) } }
    }

    private suspend fun readMessage(): DecodedMessage {
        val decoder = FramingDecoder()
        var decoded: DecodedMessage? = null
        while (decoded == null) {
            // No per-read deadline here: the device may legitimately wait for a user action
            // (button press, on-device passphrase entry) for longer than a single poll
            // interval. The only user-facing deadline is OPERATION_TIMEOUT_MS in exchangeInternal.
            val packet = transport.readPacket()
            decoded = decoder.offer(packet)
        }
        return decoded
    }

    private fun Failure.toException(): TrezorException {
        val failureMessage = message.ifEmpty { "Device returned a failure" }
        return when {
            hasCode() && code == Failure.FailureType.Failure_ActionCancelled -> TrezorCancelledException(failureMessage)
            // Defense-in-depth: the device may return this even when Features.initialized was true.
            hasCode() && code == Failure.FailureType.Failure_NotInitialized -> TrezorNotInitializedException(failureMessage)
            else -> TrezorFailureException(if (hasCode()) code.number else null, failureMessage)
        }
    }

    private suspend fun <T> mapTimeoutToTransportException(block: suspend () -> T): T =
        try {
            block()
        } catch (e: TimeoutCancellationException) {
            throw TrezorTransportException("Trezor operation timed out", e)
        }

    companion object {
        private const val WRITE_TIMEOUT_MS = 30_000L
        private const val OPERATION_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
