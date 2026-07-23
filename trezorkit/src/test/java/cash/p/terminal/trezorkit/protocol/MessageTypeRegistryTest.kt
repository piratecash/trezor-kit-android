package cash.p.terminal.trezorkit.protocol

import com.google.protobuf.ByteString
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.GetPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.PublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.Failure
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.HDNodeType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumGetPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumMessageSignature
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumSignMessage
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.Features
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.GetFeatures
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.Initialize
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageSolana.SolanaGetPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarAddress
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarGetAddress
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronAddress
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronContractRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronGetAddress
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronSignTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronSignature
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronTransferContract
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronTriggerSmartContract
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

    @Test
    fun typeOf_tronGetAddress_returnsMessageTypeNumberTwentyTwoHundred() {
        val message = TronGetAddress.newBuilder().build()

        assertEquals(2200, MessageTypeRegistry.typeOf(message))
    }

    @Test
    fun parse_tronAddressPayload_returnsEquivalentTronAddressMessage() {
        val original = TronAddress.newBuilder().setAddress("TTronAddressBase58").build()

        val parsed = MessageTypeRegistry.parse(2201, original.toByteArray())

        assertEquals(original, parsed)
    }

    @Test
    fun typeOf_tronSignTx_returnsMessageTypeNumberTwentyTwoHundredTwo() {
        val message = TronSignTx.newBuilder()
            .setRefBlockBytes(ByteString.copyFrom(byteArrayOf(1, 2)))
            .setRefBlockHash(ByteString.copyFrom(byteArrayOf(3)))
            .setExpiration(1L)
            .setTimestamp(2L)
            .build()

        assertEquals(2202, MessageTypeRegistry.typeOf(message))
    }

    @Test
    fun parse_tronSignaturePayload_returnsEquivalentTronSignatureMessage() {
        val original = TronSignature.newBuilder().setSignature(ByteString.copyFrom(ByteArray(65) { 1 })).build()

        val parsed = MessageTypeRegistry.parse(2203, original.toByteArray())

        assertEquals(original, parsed)
    }

    @Test
    fun parse_tronContractRequestPayload_returnsTronContractRequestMessage() {
        val parsed = MessageTypeRegistry.parse(2204, ByteArray(0))

        assertEquals(TronContractRequest.newBuilder().build(), parsed)
    }

    @Test
    fun typeOf_tronTransferContract_returnsMessageTypeNumberTwentyTwoHundredFive() {
        val message = TronTransferContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(byteArrayOf(1)))
            .setToAddress(ByteString.copyFrom(byteArrayOf(2)))
            .setAmount(1L)
            .build()

        assertEquals(2205, MessageTypeRegistry.typeOf(message))
    }

    @Test
    fun typeOf_tronTriggerSmartContract_returnsMessageTypeNumberTwentyTwoHundredSix() {
        val message = TronTriggerSmartContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(byteArrayOf(1)))
            .setContractAddress(ByteString.copyFrom(byteArrayOf(2)))
            .setData(ByteString.copyFrom(byteArrayOf(3)))
            .build()

        assertEquals(2206, MessageTypeRegistry.typeOf(message))
    }

    @Test
    fun typeOf_ethereumSignMessage_returnsMessageTypeNumberSixtyFour() {
        val message = EthereumSignMessage.newBuilder().setMessage(ByteString.copyFrom(byteArrayOf(1))).build()

        assertEquals(64, MessageTypeRegistry.typeOf(message))
    }

    @Test
    fun parse_ethereumMessageSignaturePayload_returnsEquivalentMessage() {
        val original = EthereumMessageSignature.newBuilder()
            .setSignature(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .setAddress("0xabc123")
            .build()

        val parsed = MessageTypeRegistry.parse(66, original.toByteArray())

        assertEquals(original, parsed)
    }
}
