package cash.p.terminal.trezorkit.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrezorFeaturesTest {

    @Test
    fun copy_moneroSupported_preservesCapability() {
        val features = features(supportsMonero = true)

        assertTrue(features.copy().supportsMonero)
    }

    @Test
    fun equality_differentMoneroCapability_notEqual() {
        assertNotEquals(
            features(supportsMonero = false),
            features(supportsMonero = true),
        )
    }

    @Test
    fun takeSessionId_concurrentCall_transfersOwnershipOnce() {
        val features = features()
        features.sessionId = TrezorSessionId(byteArrayOf(1))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val results = List(2) {
            executor.submit<TrezorSessionId?> {
                start.await()
                features.takeSessionId()
            }
        }

        start.countDown()
        val handles = results.map { it.get() }
        handles.filterNotNull().forEach(TrezorSessionId::close)
        executor.shutdown()

        assertEquals(1, handles.count { it != null })
    }

    private fun features(supportsMonero: Boolean = false) = TrezorFeatures(
        deviceId = "device-id",
        model = "Safe 5",
        internalModel = "T3T1",
        firmwareVersion = "2.8.10",
        passphraseProtection = false,
        initialized = true,
        supportsTron = true,
        supportsMonero = supportsMonero,
    )
}
