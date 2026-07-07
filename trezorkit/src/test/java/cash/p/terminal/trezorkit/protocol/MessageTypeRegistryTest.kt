package cash.p.terminal.trezorkit.protocol

import com.google.protobuf.ByteString
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.GetPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.PublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.Failure
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.HDNodeType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumGetPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.Features
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.GetFeatures
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.Initialize
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageSolana.SolanaGetPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarAddress
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarGetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTypeRegistryTest {

    @Test
    fun typeOf_initialize_returnsMessageTypeNumberZero() {
        val message = Initialize.newBuilder().build()

        assertEquals(0, MessageTypeRegistry.typeOf(message))
    }

    @Test
    fun typeOf_getFeatures_returnsMessageTypeNumberFiftyFive() {
        val message = GetFeatures.newBuilder().build()

        assertEquals(55, MessageTypeRegistry.typeOf(message))
    }

    @Test
    fun parse_featuresPayload_returnsEquivalentFeaturesMessage() {
        val original = Features.newBuilder()
            .setVendor("trezor.io")
            .setMajorVersion(2)
            .setMinorVersion(6)
            .setPatchVersion(3)
            .setDeviceId("abc123")
            .build()

        val parsed = MessageTypeRegistry.parse(17, original.toByteArray())

        assertEquals(original, parsed)
    }

    @Test
    fun parse_failurePayload_returnsEquivalentFailureMessage() {
        val original = Failure.newBuilder()
            .setCode(Failure.FailureType.Failure_ActionCancelled)
            .setMessage("cancelled by user")
            .build()

        val parsed = MessageTypeRegistry.parse(3, original.toByteArray())

        assertEquals(original, parsed)
    }

    @Test
    fun typeOf_bitcoinGetPublicKey_returnsMessageTypeNumberEleven() {
        val message = GetPublicKey.newBuilder().build()

        assertEquals(11, MessageTypeRegistry.typeOf(message))
    }

    @Test
    fun parse_bitcoinPublicKeyPayload_returnsEquivalentPublicKeyMessage() {
        val node = HDNodeType.newBuilder()
            .setDepth(0)
            .setFingerprint(0)
            .setChildNum(0)
            .setChainCode(ByteString.copyFrom(byteArrayOf(1)))
            .setPublicKey(ByteString.copyFrom(byteArrayOf(2)))
            .build()
        val original = PublicKey.newBuilder().setNode(node).setXpub("xpub123").build()

        val parsed = MessageTypeRegistry.parse(12, original.toByteArray())

        assertEquals(original, parsed)
    }

    @Test
    fun typeOf_ethereumGetPublicKey_returnsMessageTypeNumberFourHundredFifty() {
        val message = EthereumGetPublicKey.newBuilder().build()

        assertEquals(450, MessageTypeRegistry.typeOf(message))
    }

    @Test
    fun typeOf_solanaGetPublicKey_returnsMessageTypeNumberNineHundred() {
        val message = SolanaGetPublicKey.newBuilder().build()

        assertEquals(900, MessageTypeRegistry.typeOf(message))
    }

    @Test
    fun typeOf_stellarGetAddress_returnsMessageTypeNumberTwoHundredSeven() {
        val message = StellarGetAddress.newBuilder().build()

        assertEquals(207, MessageTypeRegistry.typeOf(message))
    }

    @Test
    fun parse_stellarAddressPayload_returnsEquivalentStellarAddressMessage() {
        val original = StellarAddress.newBuilder().setAddress("GABC123").build()

        val parsed = MessageTypeRegistry.parse(208, original.toByteArray())

        assertEquals(original, parsed)
    }
}
