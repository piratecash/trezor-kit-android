package cash.p.terminal.trezorkit.protocol

import cash.p.terminal.trezorkit.TrezorCancelledException
import cash.p.terminal.trezorkit.TrezorFailureException
import cash.p.terminal.trezorkit.TrezorNotInitializedException
import cash.p.terminal.trezorkit.TrezorTransportException
import com.satoshilabs.trezor.lib.protobuf.TrezorMessage.MessageType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.ButtonRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.Failure
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.PassphraseAck
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.PassphraseRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.Features
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.GetFeatures
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.Initialize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrezorSessionTest {

    private fun featuresMessage(major: Int) =
        Features.newBuilder().setMajorVersion(major).setMinorVersion(0).setPatchVersion(0).build()

    private suspend inline fun <reified T : Throwable> assertThrowsSuspend(crossinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("Expected ${T::class.simpleName} but got ${e::class.simpleName}: ${e.message}", e)
        }
        throw AssertionError("Expected ${T::class.simpleName} but nothing was thrown")
    }

    @Test
    fun exchange_happyPath_returnsFeatures() = runTest(UnconfinedTestDispatcher()) {
        val features = featuresMessage(major = 5)
        val responses = FramingCodec.encode(MessageType.MessageType_Features_VALUE, features.toByteArray())
        val transport = FakeTrezorTransport(responses)
        val session = TrezorSession(transport, MessageTypeRegistry, UnconfinedTestDispatcher(testScheduler))

        val result = session.exchange(Initialize.newBuilder().build())

        assertEquals(features, result)
        val sent = transport.writtenPackets.decodeMessages()
        assertEquals(1, sent.size)
        assertEquals(MessageType.MessageType_Initialize_VALUE, sent[0].msgType)
    }

    @Test
    fun exchange_buttonRequest_autoAcksAndEmitsEvent() = runTest(UnconfinedTestDispatcher()) {
        val features = featuresMessage(major = 6)
        val responses = FramingCodec.encode(MessageType.MessageType_ButtonRequest_VALUE, ButtonRequest.newBuilder().build().toByteArray()) +
            FramingCodec.encode(MessageType.MessageType_Features_VALUE, features.toByteArray())
        val transport = FakeTrezorTransport(responses)
        val session = TrezorSession(transport, MessageTypeRegistry, UnconfinedTestDispatcher(testScheduler))
        val events = mutableListOf<TrezorSessionEvent>()
        val collectJob = launch { session.events.collect { events.add(it) } }

        val result = session.exchange(Initialize.newBuilder().build())

        assertEquals(features, result)
        assertEquals(listOf(TrezorSessionEvent.ButtonRequested), events)
        val sentTypes = transport.writtenPackets.decodeMessages().map { it.msgType }
        assertEquals(listOf(MessageType.MessageType_Initialize_VALUE, MessageType.MessageType_ButtonAck_VALUE), sentTypes)
        collectJob.cancel()
    }

    @Test
    fun exchange_passphraseRequest_autoAcksOnDeviceAndEmitsEvent() = runTest(UnconfinedTestDispatcher()) {
        val features = featuresMessage(major = 7)
        val responses = FramingCodec.encode(MessageType.MessageType_PassphraseRequest_VALUE, PassphraseRequest.newBuilder().build().toByteArray()) +
            FramingCodec.encode(MessageType.MessageType_Features_VALUE, features.toByteArray())
        val transport = FakeTrezorTransport(responses)
        val session = TrezorSession(transport, MessageTypeRegistry, UnconfinedTestDispatcher(testScheduler))
        val events = mutableListOf<TrezorSessionEvent>()
        val collectJob = launch { session.events.collect { events.add(it) } }

        val result = session.exchange(Initialize.newBuilder().build())

        assertEquals(features, result)
        assertEquals(listOf(TrezorSessionEvent.PassphraseRequested), events)
        val sentAck = transport.writtenPackets.decodeMessages().last()
        assertEquals(MessageType.MessageType_PassphraseAck_VALUE, sentAck.msgType)
        assertTrue(PassphraseAck.parseFrom(sentAck.payload).onDevice)
        collectJob.cancel()
    }

    @Test
    fun exchange_failure_throwsTrezorFailureException() = runTest(UnconfinedTestDispatcher()) {
        val failure = Failure.newBuilder().setCode(Failure.FailureType.Failure_DataError).setMessage("bad data").build()
        val responses = FramingCodec.encode(MessageType.MessageType_Failure_VALUE, failure.toByteArray())
        val transport = FakeTrezorTransport(responses)
        val session = TrezorSession(transport, MessageTypeRegistry, UnconfinedTestDispatcher(testScheduler))

        val error = assertThrowsSuspend<TrezorFailureException> {
            session.exchange(Initialize.newBuilder().build())
        }

        assertEquals(Failure.FailureType.Failure_DataError.number, error.code)
        assertEquals("bad data", error.message)
    }

    @Test
    fun exchange_failureActionCancelled_throwsTrezorCancelledException() = runTest(UnconfinedTestDispatcher()) {
        val failure = Failure.newBuilder().setCode(Failure.FailureType.Failure_ActionCancelled).build()
        val responses = FramingCodec.encode(MessageType.MessageType_Failure_VALUE, failure.toByteArray())
        val transport = FakeTrezorTransport(responses)
        val session = TrezorSession(transport, MessageTypeRegistry, UnconfinedTestDispatcher(testScheduler))

        assertThrowsSuspend<TrezorCancelledException> {
            session.exchange(Initialize.newBuilder().build())
        }
    }

    @Test
    fun exchange_failureNotInitialized_throwsTrezorNotInitializedException() = runTest(UnconfinedTestDispatcher()) {
        val failure = Failure.newBuilder().setCode(Failure.FailureType.Failure_NotInitialized).setMessage("Device is not initialized").build()
        val responses = FramingCodec.encode(MessageType.MessageType_Failure_VALUE, failure.toByteArray())
        val transport = FakeTrezorTransport(responses)
        val session = TrezorSession(transport, MessageTypeRegistry, UnconfinedTestDispatcher(testScheduler))

        val error = assertThrowsSuspend<TrezorNotInitializedException> {
            session.exchange(Initialize.newBuilder().build())
        }

        assertEquals("Device is not initialized", error.message)
    }

    @Test
    fun exchange_concurrentCalls_serializedByMutex() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val features1 = featuresMessage(major = 10)
        val features2 = featuresMessage(major = 20)
        val responses = FramingCodec.encode(MessageType.MessageType_Features_VALUE, features1.toByteArray()) +
            FramingCodec.encode(MessageType.MessageType_Features_VALUE, features2.toByteArray())
        val transport = FakeTrezorTransport(responses)
        transport.beforeRead = { index -> if (index == 0) gate.await() }
        val session = TrezorSession(transport, MessageTypeRegistry, dispatcher)

        val job1 = async(dispatcher) { session.exchange(Initialize.newBuilder().build()) }
        val job2 = async(dispatcher) { session.exchange(Initialize.newBuilder().build()) }

        // job1 holds the mutex and is paused at the gate; job2 must still be waiting for the
        // mutex, so only job1's request should have reached the transport so far.
        assertEquals(1, transport.writtenPackets.decodeMessages().size)

        gate.complete(Unit)
        withTimeout(5_000) {
            assertEquals(features1, job1.await())
            assertEquals(features2, job2.await())
        }

        val sentTypes = transport.writtenPackets.decodeMessages().map { it.msgType }
        assertEquals(listOf(MessageType.MessageType_Initialize_VALUE, MessageType.MessageType_Initialize_VALUE), sentTypes)
    }

    @Test
    fun exchange_readHangsIndefinitely_operationTimeoutThrowsAndReleasesLock() = runTest(UnconfinedTestDispatcher()) {
        val features = featuresMessage(major = 30)
        val responses = FramingCodec.encode(MessageType.MessageType_Features_VALUE, features.toByteArray())
        val transport = FakeTrezorTransport(responses)
        var hang = true
        transport.beforeRead = { if (hang) awaitCancellation() }
        val session = TrezorSession(transport, MessageTypeRegistry, UnconfinedTestDispatcher(testScheduler))

        // There is no per-read deadline anymore, only the 5-minute operation timeout - a read
        // that hangs forever is still eventually cut off by it.
        assertThrowsSuspend<TrezorTransportException> {
            session.exchange(Initialize.newBuilder().build())
        }

        // The mutex must have been released despite the timeout - a subsequent exchange proceeds normally.
        hang = false
        val result = session.exchange(GetFeatures.newBuilder().build())
        assertEquals(features, result)
    }

    @Test
    fun exchange_slowUserConfirmation_succeedsWithoutPerReadTimeout() = runTest(UnconfinedTestDispatcher()) {
        val features = featuresMessage(major = 40)
        val responses = FramingCodec.encode(MessageType.MessageType_Features_VALUE, features.toByteArray())
        val transport = FakeTrezorTransport(responses)
        // Simulate the device waiting for a user action (button/passphrase) on-device for
        // longer than the old 30s per-read timeout, but well under the 5-minute operation timeout.
        transport.beforeRead = { delay(45_000) }
        val session = TrezorSession(transport, MessageTypeRegistry, UnconfinedTestDispatcher(testScheduler))

        val result = session.exchange(Initialize.newBuilder().build())

        assertEquals(features, result)
    }

    @Test
    fun withSession_concurrentCalls_doNotInterleave() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeTrezorTransport(emptyList())
        val session = TrezorSession(transport, MessageTypeRegistry, dispatcher)
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val job1 = async(dispatcher) {
            session.withSession {
                events.add("session1-start")
                gate.await()
                events.add("session1-end")
                "result1"
            }
        }
        val job2 = async(dispatcher) {
            session.withSession {
                events.add("session2-start")
                "result2"
            }
        }

        // job1 holds the session lock and is paused mid-block; job2 must still be waiting
        // for the lock, so it must not have entered its block yet.
        assertEquals(listOf("session1-start"), events)

        gate.complete(Unit)
        withTimeout(5_000) {
            assertEquals("result1", job1.await())
            assertEquals("result2", job2.await())
        }

        assertEquals(listOf("session1-start", "session1-end", "session2-start"), events)
    }
}
