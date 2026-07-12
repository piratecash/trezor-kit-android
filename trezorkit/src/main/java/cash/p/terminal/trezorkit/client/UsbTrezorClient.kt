package cash.p.terminal.trezorkit.client

import cash.p.terminal.trezorkit.TrezorException
import cash.p.terminal.trezorkit.TrezorNotInitializedException
import cash.p.terminal.trezorkit.protocol.TrezorSession
import cash.p.terminal.trezorkit.protocol.TrezorSessionScope
import com.google.protobuf.ByteString
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
}

private fun Features.toDto() = TrezorFeatures(
    deviceId = if (hasDeviceId()) deviceId else null,
    model = if (hasModel()) model else null,
    internalModel = if (hasInternalModel()) internalModel else null,
    firmwareVersion = "$majorVersion.$minorVersion.$patchVersion",
    passphraseProtection = passphraseProtection,
    // Old firmware without this field does not report it - do not block on its absence.
    initialized = if (hasInitialized()) initialized else true,
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
