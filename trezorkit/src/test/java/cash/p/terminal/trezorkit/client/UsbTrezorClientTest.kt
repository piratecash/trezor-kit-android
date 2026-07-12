package cash.p.terminal.trezorkit.client

import cash.p.terminal.trezorkit.TrezorException
import cash.p.terminal.trezorkit.TrezorNotInitializedException
import cash.p.terminal.trezorkit.protocol.FakeTrezorTransport
import cash.p.terminal.trezorkit.protocol.FramingCodec
import cash.p.terminal.trezorkit.protocol.MessageTypeRegistry
import cash.p.terminal.trezorkit.protocol.TrezorSession
import cash.p.terminal.trezorkit.protocol.decodeMessages
import com.google.protobuf.ByteString
import com.satoshilabs.trezor.lib.protobuf.TrezorMessage.MessageType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.GetPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.InputScriptType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.OutputScriptType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.PublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.SignTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckInput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckOutput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckPrevInput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckPrevMeta
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckPrevOutput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxRequest.RequestType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.ButtonRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.HDNodeType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumMessageSignature
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumSignMessage
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumTxAck
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumTxRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.Features
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageSolana.SolanaPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageSolana.SolanaSignTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageSolana.SolanaTxSignature
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarAddress
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarAssetType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarChangeTrustOp
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarCreateAccountOp
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarPaymentOp
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarSignTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarSignedTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarTxOpRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsbTrezorClientTest {

    // The dispatcher must share the calling test's own scheduler (see [TrezorSessionTest]) -
    // an unrelated TestDispatcher would run on a disconnected virtual clock and never complete.
    // getFeatures test: no prepended Initialize response - its Features answer feeds the session's
    // opening Initialize directly, and getFeatures() then returns that cached value.
    private fun clientWithResponse(msgType: Int, payload: ByteArray, dispatcher: CoroutineDispatcher): UsbTrezorClient =
        clientWithRawResponses(dispatcher, msgType to payload).first

    /**
     * Scripts several messages in a row and exposes the transport. Prepends a Features response for
     * the mandatory session-opening Initialize, so callers only script their own exchanges.
     */
    private fun clientWithResponses(
        dispatcher: CoroutineDispatcher,
        vararg messages: Pair<Int, ByteArray>,
    ): Pair<UsbTrezorClient, FakeTrezorTransport> =
        clientWithRawResponses(dispatcher, sessionFeaturesResponse(), *messages)

    private fun clientWithRawResponses(
        dispatcher: CoroutineDispatcher,
        vararg messages: Pair<Int, ByteArray>,
    ): Pair<UsbTrezorClient, FakeTrezorTransport> {
        val responses = messages.flatMap { (msgType, payload) -> FramingCodec.encode(msgType, payload) }
        val transport = FakeTrezorTransport(responses)
        val session = TrezorSession(transport, MessageTypeRegistry, dispatcher)
        return UsbTrezorClient(session) to transport
    }

    private fun sessionFeaturesResponse(): Pair<Int, ByteArray> =
        MessageType.MessageType_Features_VALUE to
            Features.newBuilder().setMajorVersion(2).setMinorVersion(0).setPatchVersion(0).setInitialized(true).build().toByteArray()

    /** Messages the client actually sent, excluding the session-opening Initialize. */
    private fun FakeTrezorTransport.sentMessagesAfterInit() = writtenPackets.decodeMessages().drop(1)

    private fun legacyTx(data: ByteArray = byteArrayOf(1, 2, 3, 4)) = TrezorEvmTx(
        addressN = listOf(44, 60, 0, 0, 0),
        nonce = byteArrayOf(5),
        gasLimit = byteArrayOf(0x52, 0x08),
        to = "0x1111111111111111111111111111111111111111",
        value = byteArrayOf(0x0F, 0x42, 0x40),
        data = data,
        chainId = 1L,
        gasFee = TrezorEvmGasFee.Legacy(gasPrice = byteArrayOf(0x3B, 0x9A.toByte(), 0xCA.toByte(), 0x00)),
    )

    private fun eip1559Tx(data: ByteArray = byteArrayOf(1, 2, 3, 4)) = TrezorEvmTx(
        addressN = listOf(44, 60, 0, 0, 0),
        nonce = byteArrayOf(5),
        gasLimit = byteArrayOf(0x52, 0x08),
        to = "0x1111111111111111111111111111111111111111",
        value = byteArrayOf(0x0F, 0x42, 0x40),
        data = data,
        chainId = 1L,
        gasFee = TrezorEvmGasFee.Eip1559(
            maxFeePerGas = byteArrayOf(0x3B, 0x9A.toByte(), 0xCA.toByte(), 0x00),
            maxPriorityFeePerGas = byteArrayOf(0x3B, 0x9A.toByte(), 0xCA.toByte()),
        ),
    )

    private fun signatureResponse(v: Int, r: ByteArray, s: ByteArray): ByteArray = EthereumTxRequest.newBuilder()
        .setSignatureV(v)
        .setSignatureR(ByteString.copyFrom(r))
        .setSignatureS(ByteString.copyFrom(s))
        .build()
        .toByteArray()

    /**
     * Hand-derived RLP oracle for [legacyTx]'s fixed nonce/gasPrice/gasLimit/to/value fields -
     * `RLP([nonce, gasPrice, gasLimit, to, value, data, v, r, s])` per the Ethereum Yellow Paper,
     * Appendix B. Built independently of the production [Rlp] object so the test is not tautological.
     */
    private fun expectedLegacySerializedTx(data: ByteArray, v: Int, r: ByteArray, s: ByteArray): ByteArray {
        val dataRlp = when {
            data.size < 56 -> byteArrayOf((0x80 + data.size).toByte()) + data
            // Only exercised by the 2500-byte chunking test: 2500 = 0x09C4, 2 length bytes.
            else -> byteArrayOf(0xb9.toByte(), 0x09, 0xc4.toByte()) + data
        }
        val payload =
            byteArrayOf(0x05) + // nonce = 5
                byteArrayOf(0x84.toByte(), 0x3b, 0x9a.toByte(), 0xca.toByte(), 0x00) + // gasPrice = 0x3B9ACA00
                byteArrayOf(0x82.toByte(), 0x52, 0x08) + // gasLimit = 0x5208
                byteArrayOf(0x94.toByte()) + ByteArray(20) { 0x11 } + // to: 20-byte address 0x11...11
                byteArrayOf(0x83.toByte(), 0x0f, 0x42, 0x40) + // value = 0x0F4240
                dataRlp +
                byteArrayOf(v.toByte()) + // v < 0x80 for these fixtures -> single raw byte
                byteArrayOf(0xa0.toByte()) + r + // r: 32-byte string
                byteArrayOf(0xa0.toByte()) + s // s: 32-byte string
        val listPrefix = when {
            payload.size < 56 -> byteArrayOf((0xc0 + payload.size).toByte())
            payload.size <= 0xFF -> byteArrayOf(0xf8.toByte(), payload.size.toByte())
            else -> byteArrayOf(0xf9.toByte(), (payload.size ushr 8).toByte(), payload.size.toByte())
        }
        return listPrefix + payload
    }

    private val prevHash = ByteArray(32) { it.toByte() }

    private fun segwitInput() = TrezorBtcInput(
        addressN = listOf(84, 0, 0, 0, 0),
        prevHash = prevHash,
        prevIndex = 0,
        amount = 100_000L,
        scriptType = TrezorInputScriptType.SPENDWITNESS,
        sequence = 0xFFFFFFFFL,
    )

    private fun legacyInput() = TrezorBtcInput(
        addressN = listOf(44, 0, 0, 0, 0),
        prevHash = prevHash,
        prevIndex = 1,
        amount = 50_000L,
        scriptType = TrezorInputScriptType.SPENDADDRESS,
        sequence = 0xFFFFFFFFL,
    )

    private fun addressOutput(amount: Long = 60_000L) = TrezorBtcOutput.Address(
        address = "bc1qexampleaddress",
        amount = amount,
        scriptType = TrezorOutputScriptType.PAYTOWITNESS,
    )

    private fun changeOutput(amount: Long = 39_000L) = TrezorBtcOutput.Change(
        addressN = listOf(84, 0, 0, 1, 0),
        amount = amount,
        scriptType = TrezorOutputScriptType.PAYTOWITNESS,
    )

    private fun prevTx() = TrezorPrevTx(
        version = 1,
        lockTime = 0,
        inputs = listOf(
            TrezorPrevTxInput(
                prevHash = ByteArray(32) { 9 },
                prevIndex = 0,
                scriptSig = byteArrayOf(0x01, 0x02),
                sequence = 0xFFFFFFFFL,
            ),
        ),
        outputs = listOf(TrezorPrevTxOutput(amount = 150_000L, scriptPubkey = byteArrayOf(0x76, 0xA9.toByte()))),
    )

    /** Scripts one non-final [TxRequest]: asks for [requestType] data, optionally about a previous tx identified by [txHash]. */
    private fun txRequest(requestType: RequestType, requestIndex: Int? = null, txHash: ByteArray? = null): ByteArray {
        val details = TxRequest.TxRequestDetailsType.newBuilder()
        requestIndex?.let(details::setRequestIndex)
        txHash?.let { details.setTxHash(ByteString.copyFrom(it)) }
        return TxRequest.newBuilder().setRequestType(requestType).setDetails(details.build()).build().toByteArray()
    }

    /** Scripts the final [TxRequest], carrying the input's signature and a chunk of the serialized transaction. */
    private fun txRequestFinished(signatureIndex: Int, signature: ByteArray, serializedTx: ByteArray): ByteArray =
        TxRequest.newBuilder()
            .setRequestType(RequestType.TXFINISHED)
            .setSerialized(
                TxRequest.TxRequestSerializedType.newBuilder()
                    .setSignatureIndex(signatureIndex)
                    .setSignature(ByteString.copyFrom(signature))
                    .setSerializedTx(ByteString.copyFrom(serializedTx))
                    .build(),
            )
            .build()
            .toByteArray()

    @Test
    fun getFeatures_happyPath_mapsAllFields() = runTest(UnconfinedTestDispatcher()) {
        val features = Features.newBuilder()
            .setDeviceId("device-1")
            .setModel("T")
            .setInternalModel("T2T1")
            .setMajorVersion(2)
            .setMinorVersion(6)
            .setPatchVersion(3)
            .setPassphraseProtection(true)
            .setInitialized(true)
            .build()
        val client = clientWithResponse(
            MessageType.MessageType_Features_VALUE, features.toByteArray(), UnconfinedTestDispatcher(testScheduler),
        )

        val result = client.connect { getFeatures() }

        assertEquals(
            TrezorFeatures(
                deviceId = "device-1",
                model = "T",
                internalModel = "T2T1",
                firmwareVersion = "2.6.3",
                passphraseProtection = true,
                initialized = true,
            ),
            result,
        )
    }

    @Test
    fun connect_sessionFeaturesNotInitialized_throwsTrezorNotInitializedExceptionWithoutCallingBlock() = runTest(UnconfinedTestDispatcher()) {
        val features = Features.newBuilder().setMajorVersion(2).setMinorVersion(0).setPatchVersion(0).setInitialized(false).build()
        val (client, transport) = clientWithRawResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_Features_VALUE to features.toByteArray(),
        )

        var blockCalled = false
        var caught: TrezorException? = null
        try {
            client.connect {
                blockCalled = true
                getPublicKeys(emptyList())
            }
        } catch (e: TrezorException) {
            caught = e
        }

        assertTrue(caught is TrezorNotInitializedException)
        assertTrue(!blockCalled)
        assertEquals(1, transport.writtenPackets.decodeMessages().size)
    }

    @Test
    fun getPublicKeys_bitcoinEthereumSolanaStellar_mapsEachRequestInOrder() = runTest(UnconfinedTestDispatcher()) {
        val btcNode = HDNodeType.newBuilder()
            .setDepth(0)
            .setFingerprint(0)
            .setChildNum(0)
            .setChainCode(ByteString.copyFrom(byteArrayOf(1, 2)))
            .setPublicKey(ByteString.copyFrom(byteArrayOf(3, 4)))
            .build()
        val btcPublicKey = PublicKey.newBuilder().setNode(btcNode).setXpub("xpub-btc").build()

        val ethNode = HDNodeType.newBuilder()
            .setDepth(0)
            .setFingerprint(0)
            .setChildNum(0)
            .setChainCode(ByteString.copyFrom(byteArrayOf(5, 6)))
            .setPublicKey(ByteString.copyFrom(byteArrayOf(7, 8)))
            .build()
        val ethPublicKey = EthereumPublicKey.newBuilder().setNode(ethNode).setXpub("xpub-eth").build()

        val solanaPublicKey = SolanaPublicKey.newBuilder().setPublicKey(ByteString.copyFrom(byteArrayOf(9, 10))).build()
        val stellarAddress = StellarAddress.newBuilder().setAddress("GSTELLARADDRESS").build()

        val (client, _) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_PublicKey_VALUE to btcPublicKey.toByteArray(),
            MessageType.MessageType_EthereumPublicKey_VALUE to ethPublicKey.toByteArray(),
            MessageType.MessageType_SolanaPublicKey_VALUE to solanaPublicKey.toByteArray(),
            MessageType.MessageType_StellarAddress_VALUE to stellarAddress.toByteArray(),
        )

        val result = client.connect {
            getPublicKeys(
                listOf(
                    TrezorPublicKeyRequest.Bitcoin(coinName = "Bitcoin", path = listOf(44, 0, 0), scriptType = TrezorInputScriptType.SPENDADDRESS),
                    TrezorPublicKeyRequest.Ethereum(path = listOf(44, 60, 0)),
                    TrezorPublicKeyRequest.Solana(path = listOf(44, 501, 0)),
                    TrezorPublicKeyRequest.Stellar(path = listOf(44, 148, 0)),
                ),
            )
        }

        assertEquals(4, result.size)

        assertEquals("xpub-btc", result[0].key)
        assertArrayEquals(byteArrayOf(3, 4), result[0].publicKey)
        assertArrayEquals(byteArrayOf(1, 2), result[0].chainCode)

        assertEquals("xpub-eth", result[1].key)
        assertArrayEquals(byteArrayOf(7, 8), result[1].publicKey)
        assertArrayEquals(byteArrayOf(5, 6), result[1].chainCode)

        assertEquals(byteArrayOf(9, 10).toHex(), result[2].key)
        assertArrayEquals(byteArrayOf(9, 10), result[2].publicKey)
        assertArrayEquals(ByteArray(0), result[2].chainCode)

        assertEquals("GSTELLARADDRESS", result[3].key)
        assertArrayEquals(ByteArray(0), result[3].publicKey)
        assertArrayEquals(ByteArray(0), result[3].chainCode)
    }

    private fun bitcoinPublicKeyResponse(xpub: String): ByteArray {
        val node = HDNodeType.newBuilder()
            .setDepth(0)
            .setFingerprint(0)
            .setChildNum(0)
            .setChainCode(ByteString.copyFrom(byteArrayOf(1, 2)))
            .setPublicKey(ByteString.copyFrom(byteArrayOf(3, 4)))
            .build()
        return PublicKey.newBuilder().setNode(node).setXpub(xpub).build().toByteArray()
    }

    @Test
    fun getPublicKeys_bitcoinEachScriptType_sendsRequestedScriptTypeInGetPublicKey() = runTest(UnconfinedTestDispatcher()) {
        val scriptTypes = TrezorInputScriptType.entries
        val (client, transport) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            *scriptTypes.map { MessageType.MessageType_PublicKey_VALUE to bitcoinPublicKeyResponse("xpub-${it.name}") }
                .toTypedArray(),
        )

        val requests = scriptTypes.map { TrezorPublicKeyRequest.Bitcoin(coinName = "Bitcoin", path = listOf(44, 0, 0), scriptType = it) }
        val result = client.connect { getPublicKeys(requests) }

        assertEquals(scriptTypes.map { "xpub-${it.name}" }, result.map { it.key })

        val sentScriptTypes = transport.sentMessagesAfterInit().map { GetPublicKey.parseFrom(it.payload).scriptType }
        assertEquals(
            listOf(
                InputScriptType.SPENDADDRESS,
                InputScriptType.SPENDWITNESS,
                InputScriptType.SPENDP2SHWITNESS,
                InputScriptType.SPENDTAPROOT,
            ),
            sentScriptTypes,
        )
    }

    @Test
    fun signEthereum_legacyNoChunking_returnsSignature() = runTest(UnconfinedTestDispatcher()) {
        val r = ByteArray(32) { 1 }
        val s = ByteArray(32) { 2 }
        val (client, transport) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_EthereumTxRequest_VALUE to signatureResponse(27, r, s),
        )

        val result = client.connect { signEthereum(legacyTx()) }

        val expectedSerializedTx = expectedLegacySerializedTx(data = byteArrayOf(1, 2, 3, 4), v = 27, r = r, s = s)
        assertEquals(TrezorSignature(v = 27, r = r, s = s, serializedTx = expectedSerializedTx), result)
        val sentTypes = transport.sentMessagesAfterInit().map { it.msgType }
        assertEquals(listOf(MessageType.MessageType_EthereumSignTx_VALUE), sentTypes)
    }

    @Test
    fun signEthereum_eip1559NoChunking_returnsSignature() = runTest(UnconfinedTestDispatcher()) {
        val r = ByteArray(32) { 3 }
        val s = ByteArray(32) { 4 }
        val (client, transport) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_EthereumTxRequest_VALUE to signatureResponse(28, r, s),
        )

        val result = client.connect { signEthereum(eip1559Tx()) }

        // 0x02 || RLP([chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit, to, value,
        // data, accessList=[], yParity, r, s]); payload = 1+1+4+5+3+21+4+5+1+1+33+33 = 112 (0x70).
        val payload =
            byteArrayOf(0x01) + // chainId = 1
                byteArrayOf(0x05) + // nonce = 5
                byteArrayOf(0x83.toByte(), 0x3b, 0x9a.toByte(), 0xca.toByte()) + // maxPriorityFeePerGas = 0x3B9ACA
                byteArrayOf(0x84.toByte(), 0x3b, 0x9a.toByte(), 0xca.toByte(), 0x00) + // maxFeePerGas = 0x3B9ACA00
                byteArrayOf(0x82.toByte(), 0x52, 0x08) + // gasLimit = 0x5208
                byteArrayOf(0x94.toByte()) + ByteArray(20) { 0x11 } + // to
                byteArrayOf(0x83.toByte(), 0x0f, 0x42, 0x40) + // value = 0x0F4240
                byteArrayOf(0x84.toByte(), 0x01, 0x02, 0x03, 0x04) + // data = [1,2,3,4]
                byteArrayOf(0xc0.toByte()) + // accessList = [] -> empty RLP list
                byteArrayOf(0x1c) + // yParity = v = 28
                byteArrayOf(0xa0.toByte()) + r +
                byteArrayOf(0xa0.toByte()) + s
        val expectedSerializedTx = byteArrayOf(0x02) + byteArrayOf(0xf8.toByte(), 0x70) + payload

        assertEquals(TrezorSignature(v = 28, r = r, s = s, serializedTx = expectedSerializedTx), result)
        val sentTypes = transport.sentMessagesAfterInit().map { it.msgType }
        assertEquals(listOf(MessageType.MessageType_EthereumSignTxEIP1559_VALUE), sentTypes)
    }

    @Test
    fun signEthereum_chunkedData_sendsAcksInOrderThenReturnsSignature() = runTest(UnconfinedTestDispatcher()) {
        val data = ByteArray(2500) { (it % 256).toByte() }
        val r = ByteArray(32) { 5 }
        val s = ByteArray(32) { 6 }
        val (client, transport) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_EthereumTxRequest_VALUE to
                EthereumTxRequest.newBuilder().setDataLength(1024).build().toByteArray(),
            MessageType.MessageType_EthereumTxRequest_VALUE to
                EthereumTxRequest.newBuilder().setDataLength(452).build().toByteArray(),
            MessageType.MessageType_EthereumTxRequest_VALUE to signatureResponse(28, r, s),
        )

        val result = client.connect { signEthereum(legacyTx(data = data)) }

        val expectedSerializedTx = expectedLegacySerializedTx(data = data, v = 28, r = r, s = s)
        assertEquals(TrezorSignature(v = 28, r = r, s = s, serializedTx = expectedSerializedTx), result)
        val sentMessages = transport.sentMessagesAfterInit()
        assertEquals(
            listOf(
                MessageType.MessageType_EthereumSignTx_VALUE,
                MessageType.MessageType_EthereumTxAck_VALUE,
                MessageType.MessageType_EthereumTxAck_VALUE,
            ),
            sentMessages.map { it.msgType },
        )
        assertArrayEquals(
            data.copyOfRange(1024, 2048),
            EthereumTxAck.parseFrom(sentMessages[1].payload).dataChunk.toByteArray(),
        )
        assertArrayEquals(
            data.copyOfRange(2048, 2500),
            EthereumTxAck.parseFrom(sentMessages[2].payload).dataChunk.toByteArray(),
        )
    }

    @Test
    fun signEthereum_buttonRequestMidFlow_autoAcksThenReturnsSignature() = runTest(UnconfinedTestDispatcher()) {
        val r = ByteArray(32) { 7 }
        val s = ByteArray(32) { 8 }
        val (client, transport) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_ButtonRequest_VALUE to ButtonRequest.newBuilder().build().toByteArray(),
            MessageType.MessageType_EthereumTxRequest_VALUE to signatureResponse(27, r, s),
        )

        val result = client.connect { signEthereum(legacyTx()) }

        val expectedSerializedTx = expectedLegacySerializedTx(data = byteArrayOf(1, 2, 3, 4), v = 27, r = r, s = s)
        assertEquals(TrezorSignature(v = 27, r = r, s = s, serializedTx = expectedSerializedTx), result)
        val sentTypes = transport.sentMessagesAfterInit().map { it.msgType }
        assertEquals(
            listOf(MessageType.MessageType_EthereumSignTx_VALUE, MessageType.MessageType_ButtonAck_VALUE),
            sentTypes,
        )
    }

    @Test
    fun signEthereum_legacyKnownVector_producesHandComputedRlpBytes() = runTest(UnconfinedTestDispatcher()) {
        val tx = legacyTx(data = byteArrayOf(1, 2, 3, 4)).copy(nonce = byteArrayOf(0x09), gasFee = TrezorEvmGasFee.Legacy(gasPrice = byteArrayOf(0x14)))
        val v = 37 // EIP-155 v for chainId=1, recovery id 0: 35 + chainId * 2 + 0 = 37 (0x25)
        val r = ByteArray(32) { (it + 1).toByte() } // bytes 0x01..0x20
        val s = ByteArray(32) { (it + 33).toByte() } // bytes 0x21..0x40
        val (client, _) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_EthereumTxRequest_VALUE to signatureResponse(v, r, s),
        )

        val result = client.connect { signEthereum(tx) }

        // RLP([nonce, gasPrice, gasLimit, to, value, data, v, r, s]), hand-derived per the
        // Ethereum Yellow Paper, Appendix B:
        //   payload = 09 | 14 | 82 5208 | 94 <20x11> | 83 0f4240 | 84 01020304 | 25 | a0 <r> | a0 <s>
        //   payload length = 1+1+3+21+4+5+1+33+33 = 102 (>= 56 -> long-form list, 1 length byte)
        //   list prefix = f8 66 (0x66 = 102)
        val expected = byteArrayOf(0xf8.toByte(), 0x66) +
            byteArrayOf(0x09) + // nonce = 9
            byteArrayOf(0x14) + // gasPrice = 20
            byteArrayOf(0x82.toByte(), 0x52, 0x08) + // gasLimit = 0x5208
            byteArrayOf(0x94.toByte()) + ByteArray(20) { 0x11 } + // to: 20-byte address 0x11...11
            byteArrayOf(0x83.toByte(), 0x0f, 0x42, 0x40) + // value = 0x0F4240
            byteArrayOf(0x84.toByte(), 0x01, 0x02, 0x03, 0x04) + // data = [1,2,3,4]
            byteArrayOf(0x25) + // v = 37 = 0x25
            byteArrayOf(0xa0.toByte()) + r + // r, 32 bytes
            byteArrayOf(0xa0.toByte()) + s // s, 32 bytes

        assertArrayEquals(expected, result.serializedTx)
    }

    @Test
    fun signEthereum_eip1559KnownVector_producesHandComputedRlpBytes() = runTest(UnconfinedTestDispatcher()) {
        val tx = TrezorEvmTx(
            addressN = listOf(44, 60, 0, 0, 0),
            nonce = byteArrayOf(0x07),
            gasLimit = byteArrayOf(0x52, 0x08),
            to = "0x2222222222222222222222222222222222222222",
            value = byteArrayOf(0x0F, 0x42, 0x40),
            data = byteArrayOf(),
            chainId = 1L,
            gasFee = TrezorEvmGasFee.Eip1559(
                maxFeePerGas = byteArrayOf(0x77, 0x35, 0x94.toByte(), 0x00),
                maxPriorityFeePerGas = byteArrayOf(0x3B, 0x9A.toByte(), 0xCA.toByte()),
            ),
        )
        val v = 1 // yParity
        val r = ByteArray(32) { (it + 1).toByte() } // bytes 0x01..0x20
        val s = ByteArray(32) { (it + 33).toByte() } // bytes 0x21..0x40
        val (client, _) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_EthereumTxRequest_VALUE to signatureResponse(v, r, s),
        )

        val result = client.connect { signEthereum(tx) }

        // 0x02 || RLP([chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit, to, value,
        // data, accessList=[], yParity, r, s]), hand-derived. Empty data/accessList exercise
        // RLP's empty-string (0x80) and empty-list (0xc0) edge cases:
        //   payload = 01 | 07 | 83 3b9aca | 84 77359400 | 82 5208 | 94 <20x22> | 83 0f4240
        //             | 80 | c0 | 01 | a0 <r> | a0 <s>
        //   payload length = 1+1+4+5+3+21+4+1+1+1+33+33 = 108 (>= 56 -> long-form list, 1 length byte)
        //   list prefix = f8 6c (0x6c = 108)
        val expected = byteArrayOf(0x02) + byteArrayOf(0xf8.toByte(), 0x6c) +
            byteArrayOf(0x01) + // chainId = 1
            byteArrayOf(0x07) + // nonce = 7
            byteArrayOf(0x83.toByte(), 0x3b, 0x9a.toByte(), 0xca.toByte()) + // maxPriorityFeePerGas = 0x3B9ACA
            byteArrayOf(0x84.toByte(), 0x77, 0x35, 0x94.toByte(), 0x00) + // maxFeePerGas = 0x77359400
            byteArrayOf(0x82.toByte(), 0x52, 0x08) + // gasLimit = 0x5208
            byteArrayOf(0x94.toByte()) + ByteArray(20) { 0x22 } + // to: 20-byte address 0x22...22
            byteArrayOf(0x83.toByte(), 0x0f, 0x42, 0x40) + // value = 0x0F4240
            byteArrayOf(0x80.toByte()) + // data = empty -> RLP empty string
            byteArrayOf(0xc0.toByte()) + // accessList = [] -> RLP empty list
            byteArrayOf(0x01) + // yParity = 1
            byteArrayOf(0xa0.toByte()) + r + // r, 32 bytes
            byteArrayOf(0xa0.toByte()) + s // s, 32 bytes

        assertArrayEquals(expected, result.serializedTx)
    }

    @Test
    fun signEthereum_deviceRequestsMoreDataThanRemains_throwsException() = runTest(UnconfinedTestDispatcher()) {
        val (client, _) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_EthereumTxRequest_VALUE to
                EthereumTxRequest.newBuilder().setDataLength(999).build().toByteArray(),
        )

        var caught: TrezorException? = null
        try {
            client.connect { signEthereum(legacyTx()) }
        } catch (e: TrezorException) {
            caught = e
        }

        assertTrue(caught != null)
    }

    @Test
    fun signEthereumMessage_happyPath_sendsRequestAndReturnsSignature() = runTest(UnconfinedTestDispatcher()) {
        val signature = ByteArray(65) { it.toByte() }
        val address = "0x1111111111111111111111111111111111111111"
        val response = EthereumMessageSignature.newBuilder()
            .setSignature(ByteString.copyFrom(signature))
            .setAddress(address)
            .build()
        val (client, transport) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_EthereumMessageSignature_VALUE to response.toByteArray(),
        )
        val message = "Hello Trezor".toByteArray()

        val result = client.connect { signEthereumMessage(listOf(44, 60, 0, 0, 0), message) }

        assertEquals(TrezorMessageSignature(signature = signature, address = address), result)
        val sentMessages = transport.sentMessagesAfterInit()
        assertEquals(listOf(MessageType.MessageType_EthereumSignMessage_VALUE), sentMessages.map { it.msgType })
        val sentRequest = EthereumSignMessage.parseFrom(sentMessages[0].payload)
        assertEquals(listOf(44, 60, 0, 0, 0), sentRequest.addressNList)
        assertArrayEquals(message, sentRequest.message.toByteArray())
    }

    @Test
    fun signBitcoin_segwitInputWithPrevTxAndTwoOutputs_sendsAcksInOrderAndAssemblesResult() = runTest(UnconfinedTestDispatcher()) {
        val input = segwitInput()
        val prev = prevTx()
        val addrOutput = addressOutput()
        val changeOut = changeOutput()
        val tx = TrezorBtcSignTx(version = 1, lockTime = 0, inputs = listOf(input), outputs = listOf(addrOutput, changeOut))
        val signature = ByteArray(64) { 1 }
        val serializedChunk = ByteArray(10) { 2 }

        val (client, transport) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_TxRequest_VALUE to txRequest(RequestType.TXINPUT, requestIndex = 0),
            MessageType.MessageType_TxRequest_VALUE to txRequest(RequestType.TXMETA, txHash = input.prevHash),
            MessageType.MessageType_TxRequest_VALUE to txRequest(RequestType.TXINPUT, requestIndex = 0, txHash = input.prevHash),
            MessageType.MessageType_TxRequest_VALUE to txRequest(RequestType.TXOUTPUT, requestIndex = 0, txHash = input.prevHash),
            MessageType.MessageType_TxRequest_VALUE to txRequest(RequestType.TXOUTPUT, requestIndex = 0),
            MessageType.MessageType_TxRequest_VALUE to txRequest(RequestType.TXOUTPUT, requestIndex = 1),
            MessageType.MessageType_TxRequest_VALUE to
                txRequestFinished(signatureIndex = 0, signature = signature, serializedTx = serializedChunk),
        )

        val result = client.connect { signBitcoin("Bitcoin", tx, mapOf(input.prevHash.toHex() to prev)) }

        assertEquals(TrezorBtcSignResult(serializedTx = serializedChunk, signatures = listOf(signature)), result)

        val sentMessages = transport.sentMessagesAfterInit()
        assertEquals(
            listOf(
                MessageType.MessageType_SignTx_VALUE,
                MessageType.MessageType_TxAck_VALUE, // TxAckInput: current input 0
                MessageType.MessageType_TxAck_VALUE, // TxAckPrevMeta
                MessageType.MessageType_TxAck_VALUE, // TxAckPrevInput: prev input 0
                MessageType.MessageType_TxAck_VALUE, // TxAckPrevOutput: prev output 0
                MessageType.MessageType_TxAck_VALUE, // TxAckOutput: current output 0
                MessageType.MessageType_TxAck_VALUE, // TxAckOutput: current output 1
            ),
            sentMessages.map { it.msgType },
        )

        val signTx = SignTx.parseFrom(sentMessages[0].payload)
        assertEquals(1, signTx.inputsCount)
        assertEquals(2, signTx.outputsCount)
        assertEquals("Bitcoin", signTx.coinName)

        val ackInput = TxAckInput.parseFrom(sentMessages[1].payload).tx.input
        assertEquals(input.addressN, ackInput.addressNList)
        assertArrayEquals(input.prevHash, ackInput.prevHash.toByteArray())
        assertEquals(input.prevIndex, ackInput.prevIndex)
        assertEquals(input.amount, ackInput.amount)
        assertEquals(InputScriptType.SPENDWITNESS, ackInput.scriptType)
        assertEquals(input.sequence.toInt(), ackInput.sequence)

        val ackPrevMeta = TxAckPrevMeta.parseFrom(sentMessages[2].payload).tx
        assertEquals(prev.version, ackPrevMeta.version)
        assertEquals(prev.lockTime.toInt(), ackPrevMeta.lockTime)
        assertEquals(prev.inputs.size, ackPrevMeta.inputsCount)
        assertEquals(prev.outputs.size, ackPrevMeta.outputsCount)

        val ackPrevInput = TxAckPrevInput.parseFrom(sentMessages[3].payload).tx.input
        assertArrayEquals(prev.inputs[0].prevHash, ackPrevInput.prevHash.toByteArray())
        assertEquals(prev.inputs[0].prevIndex, ackPrevInput.prevIndex)
        assertArrayEquals(prev.inputs[0].scriptSig, ackPrevInput.scriptSig.toByteArray())
        assertEquals(prev.inputs[0].sequence.toInt(), ackPrevInput.sequence)

        val ackPrevOutput = TxAckPrevOutput.parseFrom(sentMessages[4].payload).tx.output
        assertEquals(prev.outputs[0].amount, ackPrevOutput.amount)
        assertArrayEquals(prev.outputs[0].scriptPubkey, ackPrevOutput.scriptPubkey.toByteArray())

        val ackOutput0 = TxAckOutput.parseFrom(sentMessages[5].payload).tx.output
        assertEquals(addrOutput.address, ackOutput0.address)
        assertEquals(addrOutput.amount, ackOutput0.amount)
        assertEquals(OutputScriptType.PAYTOWITNESS, ackOutput0.scriptType)

        val ackOutput1 = TxAckOutput.parseFrom(sentMessages[6].payload).tx.output
        assertEquals(changeOut.addressN, ackOutput1.addressNList)
        assertEquals(changeOut.amount, ackOutput1.amount)
        assertEquals(OutputScriptType.PAYTOWITNESS, ackOutput1.scriptType)
    }

    @Test
    fun signBitcoin_legacyInputWithPrevTx_requestsPrevTxDataBeforeCurrentOutput() = runTest(UnconfinedTestDispatcher()) {
        val input = legacyInput()
        val prev = prevTx()
        val output = addressOutput()
        val tx = TrezorBtcSignTx(version = 1, lockTime = 0, inputs = listOf(input), outputs = listOf(output))
        val signature = ByteArray(64) { 5 }
        val serializedChunk = ByteArray(8) { 6 }

        val (client, transport) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_TxRequest_VALUE to txRequest(RequestType.TXINPUT, requestIndex = 0),
            MessageType.MessageType_TxRequest_VALUE to txRequest(RequestType.TXMETA, txHash = input.prevHash),
            MessageType.MessageType_TxRequest_VALUE to txRequest(RequestType.TXINPUT, requestIndex = 0, txHash = input.prevHash),
            MessageType.MessageType_TxRequest_VALUE to txRequest(RequestType.TXOUTPUT, requestIndex = 0, txHash = input.prevHash),
            MessageType.MessageType_TxRequest_VALUE to txRequest(RequestType.TXOUTPUT, requestIndex = 0),
            MessageType.MessageType_TxRequest_VALUE to
                txRequestFinished(signatureIndex = 0, signature = signature, serializedTx = serializedChunk),
        )

        val result = client.connect { signBitcoin("Bitcoin", tx, mapOf(input.prevHash.toHex() to prev)) }

        assertEquals(TrezorBtcSignResult(serializedTx = serializedChunk, signatures = listOf(signature)), result)

        val sentMessages = transport.sentMessagesAfterInit()
        assertEquals(
            listOf(
                MessageType.MessageType_SignTx_VALUE,
                MessageType.MessageType_TxAck_VALUE, // TxAckInput: current input 0
                MessageType.MessageType_TxAck_VALUE, // TxAckPrevMeta
                MessageType.MessageType_TxAck_VALUE, // TxAckPrevInput: prev input 0
                MessageType.MessageType_TxAck_VALUE, // TxAckPrevOutput: prev output 0
                MessageType.MessageType_TxAck_VALUE, // TxAckOutput: current output 0
            ),
            sentMessages.map { it.msgType },
        )

        val ackInput = TxAckInput.parseFrom(sentMessages[1].payload).tx.input
        assertEquals(InputScriptType.SPENDADDRESS, ackInput.scriptType)
        assertEquals(input.prevIndex, ackInput.prevIndex)

        val ackPrevInput = TxAckPrevInput.parseFrom(sentMessages[3].payload).tx.input
        assertArrayEquals(prev.inputs[0].scriptSig, ackPrevInput.scriptSig.toByteArray())

        val ackPrevOutput = TxAckPrevOutput.parseFrom(sentMessages[4].payload).tx.output
        assertEquals(prev.outputs[0].amount, ackPrevOutput.amount)
    }

    @Test
    fun signBitcoin_opReturnOutput_sendsZeroAmountAndOpReturnData() = runTest(UnconfinedTestDispatcher()) {
        val input = legacyInput()
        val output = TrezorBtcOutput.OpReturn(opReturnData = byteArrayOf(0x68, 0x65, 0x6C, 0x6C, 0x6F))
        val tx = TrezorBtcSignTx(version = 1, lockTime = 0, inputs = listOf(input), outputs = listOf(output))
        val signature = ByteArray(64) { 3 }
        val serializedChunk = ByteArray(5) { 4 }

        val (client, transport) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_TxRequest_VALUE to txRequest(RequestType.TXINPUT, requestIndex = 0),
            MessageType.MessageType_TxRequest_VALUE to txRequest(RequestType.TXOUTPUT, requestIndex = 0),
            MessageType.MessageType_TxRequest_VALUE to
                txRequestFinished(signatureIndex = 0, signature = signature, serializedTx = serializedChunk),
        )

        val result = client.connect { signBitcoin("Bitcoin", tx, emptyMap()) }

        assertEquals(TrezorBtcSignResult(serializedTx = serializedChunk, signatures = listOf(signature)), result)

        val sentMessages = transport.sentMessagesAfterInit()
        val ackOutput = TxAckOutput.parseFrom(sentMessages[2].payload).tx.output
        assertEquals(0L, ackOutput.amount)
        assertEquals(OutputScriptType.PAYTOOPRETURN, ackOutput.scriptType)
        assertArrayEquals(output.opReturnData, ackOutput.opReturnData.toByteArray())
    }

    @Test
    fun signSolana_happyPath_sendsSignTxAndReturnsSignature() = runTest(UnconfinedTestDispatcher()) {
        val serializedTx = ByteArray(16) { it.toByte() }
        val signature = ByteArray(64) { 9 }
        val (client, transport) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_SolanaTxSignature_VALUE to
                SolanaTxSignature.newBuilder().setSignature(ByteString.copyFrom(signature)).build().toByteArray(),
        )

        val result = client.connect { signSolana(listOf(44, 501, 0), serializedTx) }

        assertArrayEquals(signature, result)
        val sentMessages = transport.sentMessagesAfterInit()
        assertEquals(listOf(MessageType.MessageType_SolanaSignTx_VALUE), sentMessages.map { it.msgType })
        val signTx = SolanaSignTx.parseFrom(sentMessages[0].payload)
        assertEquals(listOf(44, 501, 0), signTx.addressNList)
        assertArrayEquals(serializedTx, signTx.serializedTx.toByteArray())
    }

    private fun stellarSignTx(operations: List<TrezorStellarOperation>, memo: TrezorStellarMemo = TrezorStellarMemo.None) =
        TrezorStellarSignTx(
            addressN = listOf(44, 148, 0),
            networkPassphrase = "Test SDF Network ; September 2015",
            source = "GSOURCEACCOUNT",
            fee = 100,
            sequenceNumber = 123456789L,
            timeboundsStart = 1000L,
            timeboundsEnd = 2000L,
            memo = memo,
            operations = operations,
        )

    private fun stellarOpRequest(): ByteArray = StellarTxOpRequest.newBuilder().build().toByteArray()

    private fun stellarSignedTx(signature: ByteArray, publicKey: ByteArray = ByteArray(32) { 1 }): ByteArray =
        StellarSignedTx.newBuilder()
            .setPublicKey(ByteString.copyFrom(publicKey))
            .setSignature(ByteString.copyFrom(signature))
            .build()
            .toByteArray()

    @Test
    fun signStellar_paymentThenChangeTrust_sendsOperationsInOrderAndReturnsSignature() = runTest(UnconfinedTestDispatcher()) {
        val payment = TrezorStellarOperation.Payment(
            destination = "GDESTACCOUNT",
            asset = TrezorStellarAsset.Native,
            amount = 500_000_000L,
            sourceAccount = null,
        )
        val changeTrust = TrezorStellarOperation.ChangeTrust(
            asset = TrezorStellarAsset.AlphaNum4(code = "USD", issuer = "GISSUERACCOUNT"),
            limit = 1_000_000_000L,
            sourceAccount = "GOTHERSOURCE",
        )
        val tx = stellarSignTx(operations = listOf(payment, changeTrust))
        val signature = ByteArray(64) { 8 }

        val (client, transport) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_StellarTxOpRequest_VALUE to stellarOpRequest(),
            MessageType.MessageType_StellarTxOpRequest_VALUE to stellarOpRequest(),
            MessageType.MessageType_StellarSignedTx_VALUE to stellarSignedTx(signature),
        )

        val result = client.connect { signStellar(tx) }

        assertArrayEquals(signature, result)
        val sentMessages = transport.sentMessagesAfterInit()
        assertEquals(
            listOf(
                MessageType.MessageType_StellarSignTx_VALUE,
                MessageType.MessageType_StellarPaymentOp_VALUE,
                MessageType.MessageType_StellarChangeTrustOp_VALUE,
            ),
            sentMessages.map { it.msgType },
        )

        val signTx = StellarSignTx.parseFrom(sentMessages[0].payload)
        assertEquals(tx.addressN, signTx.addressNList)
        assertEquals(tx.networkPassphrase, signTx.networkPassphrase)
        assertEquals(tx.source, signTx.sourceAccount)
        assertEquals(tx.fee, signTx.fee)
        assertEquals(tx.sequenceNumber, signTx.sequenceNumber)
        assertEquals(tx.timeboundsStart.toInt(), signTx.timeboundsStart)
        assertEquals(tx.timeboundsEnd.toInt(), signTx.timeboundsEnd)
        assertEquals(2, signTx.numOperations)
        assertEquals(StellarSignTx.StellarMemoType.NONE, signTx.memoType)

        val paymentOp = StellarPaymentOp.parseFrom(sentMessages[1].payload)
        assertEquals(payment.destination, paymentOp.destinationAccount)
        assertEquals(payment.amount, paymentOp.amount)
        assertEquals(StellarAssetType.NATIVE, paymentOp.asset.type)
        assertEquals(false, paymentOp.hasSourceAccount())

        val changeTrustOp = StellarChangeTrustOp.parseFrom(sentMessages[2].payload)
        assertEquals(changeTrust.limit, changeTrustOp.limit)
        assertEquals(StellarAssetType.ALPHANUM4, changeTrustOp.asset.type)
        assertEquals("USD", changeTrustOp.asset.code)
        assertEquals("GISSUERACCOUNT", changeTrustOp.asset.issuer)
        assertEquals("GOTHERSOURCE", changeTrustOp.sourceAccount)
    }

    @Test
    fun signStellar_createAccountOperation_sendsNewAccountAndStartingBalance() = runTest(UnconfinedTestDispatcher()) {
        val createAccount = TrezorStellarOperation.CreateAccount(
            destination = "GNEWACCOUNT",
            startingBalance = 250_000_000L,
            sourceAccount = null,
        )
        val tx = stellarSignTx(operations = listOf(createAccount), memo = TrezorStellarMemo.Text("hello"))
        val signature = ByteArray(64) { 2 }

        val (client, transport) = clientWithResponses(
            UnconfinedTestDispatcher(testScheduler),
            MessageType.MessageType_StellarTxOpRequest_VALUE to stellarOpRequest(),
            MessageType.MessageType_StellarSignedTx_VALUE to stellarSignedTx(signature),
        )

        val result = client.connect { signStellar(tx) }

        assertArrayEquals(signature, result)
        val sentMessages = transport.sentMessagesAfterInit()
        assertEquals(
            listOf(MessageType.MessageType_StellarSignTx_VALUE, MessageType.MessageType_StellarCreateAccountOp_VALUE),
            sentMessages.map { it.msgType },
        )

        val signTx = StellarSignTx.parseFrom(sentMessages[0].payload)
        assertEquals(StellarSignTx.StellarMemoType.TEXT, signTx.memoType)
        assertEquals("hello", signTx.memoText)

        val createAccountOp = StellarCreateAccountOp.parseFrom(sentMessages[1].payload)
        assertEquals(createAccount.destination, createAccountOp.newAccount)
        assertEquals(createAccount.startingBalance, createAccountOp.startingBalance)
        assertEquals(false, createAccountOp.hasSourceAccount())
    }
}
