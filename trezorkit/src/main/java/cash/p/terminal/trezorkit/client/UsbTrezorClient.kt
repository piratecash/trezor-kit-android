package cash.p.terminal.trezorkit.client

import cash.p.terminal.trezorkit.TrezorException
import cash.p.terminal.trezorkit.TrezorNotInitializedException
import cash.p.terminal.trezorkit.protocol.TrezorSession
import cash.p.terminal.trezorkit.protocol.TrezorSessionScope
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.GetPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.InputScriptType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.OutputScriptType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.PrevInput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.PrevOutput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.PrevTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.PublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.SignTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckInput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckOutput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckPrevInput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckPrevMeta
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckPrevOutput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxInput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxOutput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxRequest.RequestType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.HDNodeType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumGetPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumMessageSignature
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumSignMessage
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumSignTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumSignTxEIP1559
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumTxAck
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumTxRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.Features
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.Initialize
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageSolana.SolanaGetPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageSolana.SolanaPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageSolana.SolanaSignTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageSolana.SolanaTxSignature
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarAddress
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarAsset
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarAssetType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarChangeTrustOp
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarCreateAccountOp
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarGetAddress
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarPaymentOp
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarSignTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarSignedTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarTxOpRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronAddress
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronContractRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronGetAddress
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronRawTransaction
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronRawTransaction.TronRawContract
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronSignTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronSignature
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronTransferContract
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronTriggerSmartContract
import java.io.ByteArrayOutputStream

/** [ITrezorClient] backed by a [TrezorSession] talking to a USB-connected Trezor. */
class UsbTrezorClient(private val session: TrezorSession) : ITrezorClient {

    override suspend fun <T> connect(block: suspend TrezorClientSession.() -> T): T =
        session.withSession {
            val clientSession = SessionImpl(this)
            // Initialize must be the first message of every session: it resets device state and
            // establishes the session. Without it the device rejects follow-ups with "Invalid
            // session" - e.g. scan-to-add fetches keys without first calling getFeatures().
            clientSession.initialize()
            clientSession.block()
        }
}

/** Asks [message] on this session scope and casts the response to [T], the only expected reply type. */
private suspend inline fun <reified T : MessageLite> TrezorSessionScope.exchangeTyped(message: MessageLite): T {
    val response = exchange(message)
    return response as? T
        ?: throw TrezorException("Expected ${T::class.simpleName} but got ${response::class.simpleName}")
}

/** Trezor requests transaction payload data in chunks of at most this many bytes. */
private const val ETHEREUM_DATA_CHUNK_SIZE = 1024

private class SessionImpl(private val scope: TrezorSessionScope) : TrezorClientSession {

    private var cachedFeatures: TrezorFeatures? = null

    /** Sends the mandatory session-opening Initialize and caches the resulting Features. */
    suspend fun initialize() {
        val features = requestFeatures()
        if (!features.initialized) {
            throw TrezorNotInitializedException("Trezor is not set up. Complete setup on the device or in Trezor Suite.")
        }
        cachedFeatures = features
    }

    override suspend fun getFeatures(): TrezorFeatures = cachedFeatures ?: requestFeatures()

    private suspend fun requestFeatures(): TrezorFeatures =
        scope.exchangeTyped<Features>(Initialize.newBuilder().build()).toDto()

    override suspend fun getPublicKeys(requests: List<TrezorPublicKeyRequest>): List<TrezorKeyResult> =
        requests.map { request ->
            when (request) {
                is TrezorPublicKeyRequest.Bitcoin -> getBitcoinPublicKey(request.coinName, request.path, request.scriptType)
                is TrezorPublicKeyRequest.Ethereum -> getEthereumPublicKey(request.path)
                is TrezorPublicKeyRequest.Solana -> getSolanaPublicKey(request.path)
                is TrezorPublicKeyRequest.Stellar -> getStellarAddress(request.path)
                is TrezorPublicKeyRequest.Tron -> getTronAddress(request.path)
            }
        }

    private suspend fun getBitcoinPublicKey(coinName: String, path: List<Int>, scriptType: TrezorInputScriptType): TrezorKeyResult {
        val request = GetPublicKey.newBuilder()
            .addAllAddressN(path)
            .setCoinName(coinName)
            .setScriptType(scriptType.toProto())
            .build()
        val response = scope.exchangeTyped<PublicKey>(request)
        return response.node.toKeyResult(response.xpub)
    }

    private suspend fun getEthereumPublicKey(path: List<Int>): TrezorKeyResult {
        val request = EthereumGetPublicKey.newBuilder().addAllAddressN(path).build()
        val response = scope.exchangeTyped<EthereumPublicKey>(request)
        return response.node.toKeyResult(response.xpub)
    }

    private suspend fun getSolanaPublicKey(path: List<Int>): TrezorKeyResult {
        val request = SolanaGetPublicKey.newBuilder().addAllAddressN(path).build()
        val publicKey = scope.exchangeTyped<SolanaPublicKey>(request).publicKey.toByteArray()
        return TrezorKeyResult(key = publicKey.toHex(), publicKey = publicKey, chainCode = ByteArray(0))
    }

    private suspend fun getStellarAddress(path: List<Int>): TrezorKeyResult {
        val request = StellarGetAddress.newBuilder().addAllAddressN(path).build()
        val address = scope.exchangeTyped<StellarAddress>(request).address
        return TrezorKeyResult(key = address, publicKey = ByteArray(0), chainCode = ByteArray(0))
    }

    private suspend fun getTronAddress(path: List<Int>): TrezorKeyResult {
        val request = TronGetAddress.newBuilder().addAllAddressN(path).build()
        val address = scope.exchangeTyped<TronAddress>(request).address
        return TrezorKeyResult(key = address, publicKey = ByteArray(0), chainCode = ByteArray(0))
    }

    override suspend fun signEthereum(tx: TrezorEvmTx): TrezorSignature {
        val initialChunk = tx.data.copyOf(minOf(tx.data.size, ETHEREUM_DATA_CHUNK_SIZE))
        var response = scope.exchangeTyped<EthereumTxRequest>(tx.toSignTxMessage(initialChunk))
        var sentBytes = initialChunk.size

        while (response.hasDataLength()) {
            val remaining = tx.data.size - sentBytes
            if (response.dataLength <= 0 || response.dataLength > remaining) {
                throw TrezorException(
                    "Device requested ${response.dataLength} bytes of Ethereum tx data but only $remaining remain",
                )
            }
            val chunk = tx.data.copyOfRange(sentBytes, sentBytes + response.dataLength)
            sentBytes += chunk.size
            val ack = EthereumTxAck.newBuilder().setDataChunk(ByteString.copyFrom(chunk)).build()
            response = scope.exchangeTyped<EthereumTxRequest>(ack)
        }
        if (sentBytes != tx.data.size) {
            throw TrezorException("Device ended the Ethereum sign flow after $sentBytes of ${tx.data.size} data bytes")
        }

        val v = response.signatureV
        val r = response.signatureR.toByteArray()
        val s = response.signatureS.toByteArray()
        return TrezorSignature(v = v, r = r, s = s, serializedTx = tx.toSerializedTx(v, r, s))
    }

    override suspend fun signEthereumMessage(addressN: List<Int>, message: ByteArray): TrezorMessageSignature {
        val request = EthereumSignMessage.newBuilder()
            .addAllAddressN(addressN)
            .setMessage(ByteString.copyFrom(message))
            .build()
        val response = scope.exchangeTyped<EthereumMessageSignature>(request)
        return TrezorMessageSignature(
            signature = response.signature.toByteArray(),
            address = response.address,
        )
    }

    override suspend fun signBitcoin(
        coinName: String,
        tx: TrezorBtcSignTx,
        prevTxByHash: Map<String, TrezorPrevTx>,
    ): TrezorBtcSignResult {
        val signTx = SignTx.newBuilder()
            .setCoinName(coinName)
            .setInputsCount(tx.inputs.size)
            .setOutputsCount(tx.outputs.size)
            .setVersion(tx.version)
            .setLockTime(tx.lockTime.toInt())
            .build()

        var response = scope.exchangeTyped<TxRequest>(signTx)
        val signatures = arrayOfNulls<ByteArray>(tx.inputs.size)
        val buffer = ByteArrayOutputStream()

        while (true) {
            if (response.hasSerialized()) response.serialized.collectInto(signatures, buffer)
            if (response.requestType == RequestType.TXFINISHED) break
            response = scope.exchangeTyped<TxRequest>(response.buildAck(tx, prevTxByHash))
        }

        return TrezorBtcSignResult(
            serializedTx = buffer.toByteArray(),
            signatures = signatures.map { it ?: error("Device did not return a signature for one or more inputs") },
        )
    }

    override suspend fun signSolana(addressN: List<Int>, serializedTx: ByteArray): ByteArray {
        val request = SolanaSignTx.newBuilder()
            .addAllAddressN(addressN)
            .setSerializedTx(ByteString.copyFrom(serializedTx))
            .build()
        return scope.exchangeTyped<SolanaTxSignature>(request).signature.toByteArray()
    }

    override suspend fun signStellar(tx: TrezorStellarSignTx): ByteArray {
        scope.exchangeTyped<StellarTxOpRequest>(tx.toSignTxProto())

        var signature: ByteString? = null
        for (operation in tx.operations) {
            when (val ack = scope.exchange(operation.toProto())) {
                is StellarSignedTx -> signature = ack.signature
                is StellarTxOpRequest -> Unit
                else -> throw TrezorException("Unexpected response ${ack::class.simpleName} for Stellar operation")
            }
        }
        return checkNotNull(signature) { "Device did not return a signature after the last Stellar operation" }.toByteArray()
    }

    override suspend fun signTron(addressN: List<Int>, rawData: ByteArray): ByteArray {
        // Everything that can reject the transaction happens here, before any Tron message
        // is sent - only the session-opening Initialize has reached the device by this point.
        val parsed = parseTronRawTransaction(rawData)
        val contractMsg = parsed.toCleanContractMessage()
        requireRepresentable(parsed, contractMsg, rawData)

        scope.exchangeTyped<TronContractRequest>(parsed.toSignTxMessage(addressN))
        return scope.exchangeTyped<TronSignature>(contractMsg).signature.toByteArray()
    }
}

private fun Features.toDto() = TrezorFeatures(
    deviceId = if (hasDeviceId()) deviceId else null,
    model = if (hasModel()) model else null,
    internalModel = if (hasInternalModel()) internalModel else null,
    firmwareVersion = "$majorVersion.$minorVersion.$patchVersion",
    passphraseProtection = passphraseProtection,
    // Old firmware without this field does not report it - do not block on its absence.
    initialized = if (hasInitialized()) initialized else true,
    supportsTron = capabilitiesList.contains(Features.Capability.Capability_Tron),
)

private fun HDNodeType.toKeyResult(xpub: String) = TrezorKeyResult(
    key = xpub,
    publicKey = publicKey.toByteArray(),
    chainCode = chainCode.toByteArray(),
)

/** Builds the initial EVM sign request - legacy or EIP-1559 - carrying [initialChunk] of [TrezorEvmTx.data]. */
private fun TrezorEvmTx.toSignTxMessage(initialChunk: ByteArray): MessageLite = when (val fee = gasFee) {
    is TrezorEvmGasFee.Legacy -> EthereumSignTx.newBuilder()
        .addAllAddressN(addressN)
        .setNonce(ByteString.copyFrom(nonce))
        .setGasPrice(ByteString.copyFrom(fee.gasPrice))
        .setGasLimit(ByteString.copyFrom(gasLimit))
        .setTo(to)
        .setValue(ByteString.copyFrom(value))
        .setDataInitialChunk(ByteString.copyFrom(initialChunk))
        .setDataLength(data.size)
        .setChainId(chainId)
        .build()

    is TrezorEvmGasFee.Eip1559 -> EthereumSignTxEIP1559.newBuilder()
        .addAllAddressN(addressN)
        .setNonce(ByteString.copyFrom(nonce))
        .setMaxGasFee(ByteString.copyFrom(fee.maxFeePerGas))
        .setMaxPriorityFee(ByteString.copyFrom(fee.maxPriorityFeePerGas))
        .setGasLimit(ByteString.copyFrom(gasLimit))
        .setTo(to)
        .setValue(ByteString.copyFrom(value))
        .setDataInitialChunk(ByteString.copyFrom(initialChunk))
        .setDataLength(data.size)
        .setChainId(chainId)
        .build()
}

/**
 * Deterministically reassembles the serialized, signed transaction from this request's fields
 * and the device's v/r/s - the device only returns the signature, never the serialized bytes.
 */
private fun TrezorEvmTx.toSerializedTx(v: Int, r: ByteArray, s: ByteArray): ByteArray {
    val signatureFields = listOf(
        Rlp.encodeBytes(v.toRlpBytes()),
        Rlp.encodeBytes(r.trimLeadingZeros()),
        Rlp.encodeBytes(s.trimLeadingZeros()),
    )
    return when (val fee = gasFee) {
        is TrezorEvmGasFee.Legacy -> Rlp.encodeList(
            listOf(
                Rlp.encodeBytes(nonce),
                Rlp.encodeBytes(fee.gasPrice),
                Rlp.encodeBytes(gasLimit),
                Rlp.encodeBytes(to.hexToByteArray()),
                Rlp.encodeBytes(value),
                Rlp.encodeBytes(data),
            ) + signatureFields,
        )

        is TrezorEvmGasFee.Eip1559 -> byteArrayOf(0x02) + Rlp.encodeList(
            listOf(
                Rlp.encodeBytes(chainId.toRlpBytes()),
                Rlp.encodeBytes(nonce),
                Rlp.encodeBytes(fee.maxPriorityFeePerGas),
                Rlp.encodeBytes(fee.maxFeePerGas),
                Rlp.encodeBytes(gasLimit),
                Rlp.encodeBytes(to.hexToByteArray()),
                Rlp.encodeBytes(value),
                Rlp.encodeBytes(data),
                Rlp.encodeList(emptyList()), // empty access list
            ) + signatureFields,
        )
    }
}

/** Parses a "0x"-prefixed (or bare) hex string into raw bytes - the inverse of [toHex]. */
private fun String.hexToByteArray(): ByteArray {
    val hex = removePrefix("0x").removePrefix("0X")
    return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/** Accumulates a [TxRequest]'s serialized-data fragment: a signature keyed by input index, and/or a chunk of the final signed transaction. */
private fun TxRequest.TxRequestSerializedType.collectInto(signatures: Array<ByteArray?>, buffer: ByteArrayOutputStream) {
    if (hasSignatureIndex() && hasSignature()) signatures[signatureIndex] = signature.toByteArray()
    if (hasSerializedTx()) buffer.write(serializedTx.toByteArray())
}

/** Dispatches a [TxRequest] to the TxAck-family response it expects, for either the current tx or a referenced previous tx. */
private fun TxRequest.buildAck(tx: TrezorBtcSignTx, prevTxByHash: Map<String, TrezorPrevTx>): MessageLite {
    val requestIndex = if (details.hasRequestIndex()) details.requestIndex else 0
    if (!details.hasTxHash()) return tx.buildAck(requestType, requestIndex)

    val hash = details.txHash.toByteArray().toHex()
    val prevTx = prevTxByHash[hash] ?: error("Missing previous transaction for hash $hash")
    return prevTx.buildAck(requestType, requestIndex)
}

/** Builds the TxAck-family response for a request about the transaction currently being signed. */
private fun TrezorBtcSignTx.buildAck(requestType: RequestType, requestIndex: Int): MessageLite = when (requestType) {
    RequestType.TXINPUT -> TxAckInput.newBuilder()
        .setTx(TxAckInput.TxAckInputWrapper.newBuilder().setInput(inputs[requestIndex].toProto()).build())
        .build()

    RequestType.TXOUTPUT -> TxAckOutput.newBuilder()
        .setTx(TxAckOutput.TxAckOutputWrapper.newBuilder().setOutput(outputs[requestIndex].toProto()).build())
        .build()

    else -> error("Unexpected request type $requestType for the transaction being signed")
}

/** Builds the TxAck-family response for a request about a previous transaction (verifying a spent UTXO). */
private fun TrezorPrevTx.buildAck(requestType: RequestType, requestIndex: Int): MessageLite = when (requestType) {
    RequestType.TXMETA -> TxAckPrevMeta.newBuilder().setTx(toMetaProto()).build()

    RequestType.TXINPUT -> TxAckPrevInput.newBuilder()
        .setTx(TxAckPrevInput.TxAckPrevInputWrapper.newBuilder().setInput(inputs[requestIndex].toProto()).build())
        .build()

    RequestType.TXOUTPUT -> TxAckPrevOutput.newBuilder()
        .setTx(TxAckPrevOutput.TxAckPrevOutputWrapper.newBuilder().setOutput(outputs[requestIndex].toProto()).build())
        .build()

    else -> error("Unexpected request type $requestType for a previous transaction")
}

private fun TrezorPrevTx.toMetaProto(): PrevTx = PrevTx.newBuilder()
    .setVersion(version)
    .setLockTime(lockTime.toInt())
    .setInputsCount(inputs.size)
    .setOutputsCount(outputs.size)
    .build()

private fun TrezorBtcInput.toProto(): TxInput = TxInput.newBuilder()
    .addAllAddressN(addressN)
    .setPrevHash(ByteString.copyFrom(prevHash))
    .setPrevIndex(prevIndex)
    .setSequence(sequence.toInt())
    .setScriptType(scriptType.toProto())
    .setAmount(amount)
    .build()

private fun TrezorBtcOutput.toProto(): TxOutput {
    val builder = TxOutput.newBuilder().setAmount(amount)
    return when (this) {
        is TrezorBtcOutput.Address -> builder.setAddress(address).setScriptType(scriptType.toProto()).build()
        is TrezorBtcOutput.Change -> builder.addAllAddressN(addressN).setScriptType(scriptType.toProto()).build()
        is TrezorBtcOutput.OpReturn -> builder
            .setScriptType(OutputScriptType.PAYTOOPRETURN)
            .setOpReturnData(ByteString.copyFrom(opReturnData))
            .build()
    }
}

private fun TrezorPrevTxInput.toProto(): PrevInput = PrevInput.newBuilder()
    .setPrevHash(ByteString.copyFrom(prevHash))
    .setPrevIndex(prevIndex)
    .setScriptSig(ByteString.copyFrom(scriptSig))
    .setSequence(sequence.toInt())
    .build()

private fun TrezorPrevTxOutput.toProto(): PrevOutput = PrevOutput.newBuilder()
    .setAmount(amount)
    .setScriptPubkey(ByteString.copyFrom(scriptPubkey))
    .build()

private fun TrezorInputScriptType.toProto(): InputScriptType = when (this) {
    TrezorInputScriptType.SPENDADDRESS -> InputScriptType.SPENDADDRESS
    TrezorInputScriptType.SPENDWITNESS -> InputScriptType.SPENDWITNESS
    TrezorInputScriptType.SPENDP2SHWITNESS -> InputScriptType.SPENDP2SHWITNESS
    TrezorInputScriptType.SPENDTAPROOT -> InputScriptType.SPENDTAPROOT
}

private fun TrezorOutputScriptType.toProto(): OutputScriptType = when (this) {
    TrezorOutputScriptType.PAYTOADDRESS -> OutputScriptType.PAYTOADDRESS
    TrezorOutputScriptType.PAYTOWITNESS -> OutputScriptType.PAYTOWITNESS
    TrezorOutputScriptType.PAYTOP2SHWITNESS -> OutputScriptType.PAYTOP2SHWITNESS
    TrezorOutputScriptType.PAYTOTAPROOT -> OutputScriptType.PAYTOTAPROOT
}

/** Lowercase hex encoding shared by production prev-tx-hash lookups and test fixtures. */
internal fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

private fun TrezorStellarSignTx.toSignTxProto(): StellarSignTx {
    val builder = StellarSignTx.newBuilder()
        .addAllAddressN(addressN)
        .setNetworkPassphrase(networkPassphrase)
        .setSourceAccount(source)
        .setFee(fee)
        .setSequenceNumber(sequenceNumber)
        .setTimeboundsStart(timeboundsStart.toInt())
        .setTimeboundsEnd(timeboundsEnd.toInt())
        .setNumOperations(operations.size)
    builder.applyMemo(memo)
    return builder.build()
}

private fun StellarSignTx.Builder.applyMemo(memo: TrezorStellarMemo) {
    when (memo) {
        is TrezorStellarMemo.None -> setMemoType(StellarSignTx.StellarMemoType.NONE)
        is TrezorStellarMemo.Text -> setMemoType(StellarSignTx.StellarMemoType.TEXT).setMemoText(memo.text)
        is TrezorStellarMemo.Id -> setMemoType(StellarSignTx.StellarMemoType.ID).setMemoId(memo.id)
        is TrezorStellarMemo.Hash ->
            setMemoType(StellarSignTx.StellarMemoType.HASH).setMemoHash(ByteString.copyFrom(memo.hash))
        is TrezorStellarMemo.ReturnHash ->
            setMemoType(StellarSignTx.StellarMemoType.RETURN).setMemoHash(ByteString.copyFrom(memo.hash))
    }
}

private fun TrezorStellarOperation.toProto(): MessageLite = when (this) {
    is TrezorStellarOperation.Payment -> {
        val builder = StellarPaymentOp.newBuilder()
            .setDestinationAccount(destination)
            .setAsset(asset.toProto())
            .setAmount(amount)
        sourceAccount?.let { builder.setSourceAccount(it) }
        builder.build()
    }

    is TrezorStellarOperation.CreateAccount -> {
        val builder = StellarCreateAccountOp.newBuilder()
            .setNewAccount(destination)
            .setStartingBalance(startingBalance)
        sourceAccount?.let { builder.setSourceAccount(it) }
        builder.build()
    }

    is TrezorStellarOperation.ChangeTrust -> {
        val builder = StellarChangeTrustOp.newBuilder()
            .setAsset(asset.toProto())
            .setLimit(limit)
        sourceAccount?.let { builder.setSourceAccount(it) }
        builder.build()
    }
}

/** Prefix of `google.protobuf.Any.type_url` in Tron transactions, completed with the contract type name. */
private const val TRON_TYPE_URL_PREFIX = "type.googleapis.com/protocol."

/** Runs a protobuf parse of a Tron [what], converting parse failures into [TrezorException]. */
private inline fun <T> parsingTron(what: String, block: () -> T): T = try {
    block()
} catch (e: InvalidProtocolBufferException) {
    throw TrezorException("Invalid Tron $what: ${e.message}")
}

private fun parseTronRawTransaction(rawData: ByteArray): TronRawTransaction =
    parsingTron("raw transaction") { TronRawTransaction.parseFrom(rawData) }

/**
 * Extracts the single contract of a Tron transaction as the typed Trezor wire message,
 * rebuilt from scratch so that unknown fields retained by the protobuf parser are dropped.
 */
private fun TronRawTransaction.toCleanContractMessage(): MessageLite {
    if (contractCount != 1) {
        throw TrezorException("Tron transaction must contain exactly one contract, got $contractCount")
    }
    val parameterValue = getContract(0).parameter.value
    return when (val type = getContract(0).type) {
        TronRawContract.TronRawContractType.TransferContract -> {
            val transfer = parsingTron("TransferContract") { TronTransferContract.parseFrom(parameterValue) }
            TronTransferContract.newBuilder()
                .setOwnerAddress(transfer.ownerAddress)
                .setToAddress(transfer.toAddress)
                .setAmount(transfer.amount)
                .build()
        }

        TronRawContract.TronRawContractType.TriggerSmartContract -> {
            val trigger = parsingTron("TriggerSmartContract") { TronTriggerSmartContract.parseFrom(parameterValue) }
            TronTriggerSmartContract.newBuilder()
                .setOwnerAddress(trigger.ownerAddress)
                .setContractAddress(trigger.contractAddress)
                .setData(trigger.data)
                .build()
        }

        else -> throw TrezorException("Unsupported Tron contract type $type")
    }
}

/**
 * Fails unless [rawData] is exactly representable by the Trezor Tron protocol.
 *
 * The device does not sign the provided bytes - it re-serializes a TronRawTransaction from the
 * forwarded fields and signs sha256 of its own serialization (firmware apps/tron/sign_tx.py).
 * Any field the Trezor proto does not know (call_value, ref_block_num, ...) would be silently
 * dropped, making the device display and sign a DIFFERENT transaction. Rebuilding from only
 * the forwarded fields and requiring a byte-exact match closes that hole.
 */
private fun requireRepresentable(parsed: TronRawTransaction, contractMsg: MessageLite, rawData: ByteArray) {
    val rebuiltContract = TronRawContract.newBuilder()
        .setType(parsed.getContract(0).type)
        .setParameter(
            TronRawContract.TronRawParameter.newBuilder()
                .setTypeUrl(TRON_TYPE_URL_PREFIX + parsed.getContract(0).type.name)
                .setValue(contractMsg.toByteString()),
        )
        .build()
    val rebuilt = TronRawTransaction.newBuilder()
        .setRefBlockBytes(parsed.refBlockBytes)
        .setRefBlockHash(parsed.refBlockHash)
        .setExpiration(parsed.expiration)
        .addContract(rebuiltContract)
        .setTimestamp(parsed.timestamp)
        .apply {
            if (parsed.hasData()) setData(parsed.data)
            if (parsed.hasFeeLimit()) setFeeLimit(parsed.feeLimit)
        }
        .build()
    if (!rebuilt.toByteArray().contentEquals(rawData)) {
        throw TrezorException("Tron transaction contains fields not supported by Trezor")
    }
}

/** Builds the [TronSignTx] opener carrying the contract-agnostic fields of the transaction. */
private fun TronRawTransaction.toSignTxMessage(addressN: List<Int>): TronSignTx =
    TronSignTx.newBuilder()
        .addAllAddressN(addressN)
        .setRefBlockBytes(refBlockBytes)
        .setRefBlockHash(refBlockHash)
        .setExpiration(expiration)
        .setTimestamp(timestamp)
        .apply {
            if (this@toSignTxMessage.hasData()) setData(this@toSignTxMessage.data)
            if (this@toSignTxMessage.hasFeeLimit()) setFeeLimit(this@toSignTxMessage.feeLimit)
        }
        .build()

private fun TrezorStellarAsset.toProto(): StellarAsset = when (this) {
    TrezorStellarAsset.Native -> StellarAsset.newBuilder().setType(StellarAssetType.NATIVE).build()
    is TrezorStellarAsset.AlphaNum4 -> StellarAsset.newBuilder()
        .setType(StellarAssetType.ALPHANUM4)
        .setCode(code)
        .setIssuer(issuer)
        .build()
    is TrezorStellarAsset.AlphaNum12 -> StellarAsset.newBuilder()
        .setType(StellarAssetType.ALPHANUM12)
        .setCode(code)
        .setIssuer(issuer)
        .build()
}
