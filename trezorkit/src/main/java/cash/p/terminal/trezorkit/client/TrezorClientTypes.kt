package cash.p.terminal.trezorkit.client

/**
 * Neutral, protobuf-free snapshot of a Trezor device's `Features` response.
 *
 * [firmwareVersion] is assembled as `"major.minor.patch"`.
 * [supportsTron] reflects the device's `Capability_Tron` (Tron signing landed in core firmware 2.11.0);
 * older firmware rejects Tron messages, so callers must not request Tron derivation when it is false.
 */
data class TrezorFeatures(
    val deviceId: String?,
    val model: String?,
    val internalModel: String?,
    val firmwareVersion: String,
    val passphraseProtection: Boolean,
    val initialized: Boolean = true,
    val supportsTron: Boolean = false,
)

/** One key/address derivation request, batched together in a single [TrezorClientSession.getPublicKeys] call. */
sealed interface TrezorPublicKeyRequest {
    data class Bitcoin(val coinName: String, val path: List<Int>, val scriptType: TrezorInputScriptType) : TrezorPublicKeyRequest
    data class Ethereum(val path: List<Int>) : TrezorPublicKeyRequest
    data class Solana(val path: List<Int>) : TrezorPublicKeyRequest
    data class Stellar(val path: List<Int>) : TrezorPublicKeyRequest
    data class Tron(val path: List<Int>) : TrezorPublicKeyRequest
}

/**
 * Uniform result of one [TrezorPublicKeyRequest]. [key]'s meaning depends on the request type:
 * xpub for Bitcoin/Ethereum, hex-encoded public key for Solana, address for Stellar/Tron.
 * [chainCode] is empty for Solana/Stellar/Tron - none of them has a BIP-32 chain code.
 */
data class TrezorKeyResult(
    val key: String,
    val publicKey: ByteArray,
    val chainCode: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrezorKeyResult) return false
        return key == other.key &&
            publicKey.contentEquals(other.publicKey) &&
            chainCode.contentEquals(other.chainCode)
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + chainCode.contentHashCode()
        return result
    }
}

/** Gas pricing model for an EVM transaction - a Trezor sign request carries exactly one of these. */
sealed class TrezorEvmGasFee {

    /** Pre-EIP-1559 fixed gas price, in wei, trimmed big-endian. */
    data class Legacy(val gasPrice: ByteArray) : TrezorEvmGasFee() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Legacy) return false
            return gasPrice.contentEquals(other.gasPrice)
        }

        override fun hashCode(): Int = gasPrice.contentHashCode()
    }

    /** EIP-1559 fee market, in wei, trimmed big-endian. */
    data class Eip1559(val maxFeePerGas: ByteArray, val maxPriorityFeePerGas: ByteArray) : TrezorEvmGasFee() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Eip1559) return false
            return maxFeePerGas.contentEquals(other.maxFeePerGas) &&
                maxPriorityFeePerGas.contentEquals(other.maxPriorityFeePerGas)
        }

        override fun hashCode(): Int =
            31 * maxFeePerGas.contentHashCode() + maxPriorityFeePerGas.contentHashCode()
    }
}

/**
 * An unsigned EVM transaction to sign on-device. Numeric fields are trimmed big-endian byte
 * arrays, as required by the Trezor wire protocol.
 */
data class TrezorEvmTx(
    val addressN: List<Int>,
    val nonce: ByteArray,
    val gasLimit: ByteArray,
    val to: String,
    val value: ByteArray,
    val data: ByteArray,
    val chainId: Long,
    val gasFee: TrezorEvmGasFee,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrezorEvmTx) return false
        return addressN == other.addressN &&
            nonce.contentEquals(other.nonce) &&
            gasLimit.contentEquals(other.gasLimit) &&
            to == other.to &&
            value.contentEquals(other.value) &&
            data.contentEquals(other.data) &&
            chainId == other.chainId &&
            gasFee == other.gasFee
    }

    override fun hashCode(): Int {
        var result = addressN.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + gasLimit.contentHashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + value.contentHashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + chainId.hashCode()
        result = 31 * result + gasFee.hashCode()
        return result
    }
}

/** Result of signing an EIP-191 personal message on-device: the raw signature and the signing address. */
data class TrezorMessageSignature(val signature: ByteArray, val address: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrezorMessageSignature) return false
        return signature.contentEquals(other.signature) && address == other.address
    }

    override fun hashCode(): Int = 31 * signature.contentHashCode() + address.hashCode()
}

/**
 * A device-computed signature. The USB client populates [serializedTx] by deterministically
 * re-assembling the signed transaction from the request fields and the device's v/r/s (the device
 * itself returns only the signature). [serializedTx] is null only for clients that cannot provide it.
 */
data class TrezorSignature(
    val v: Int,
    val r: ByteArray,
    val s: ByteArray,
    val serializedTx: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrezorSignature) return false
        return v == other.v &&
            r.contentEquals(other.r) &&
            s.contentEquals(other.s) &&
            serializedTx.contentEquals(other.serializedTx)
    }

    override fun hashCode(): Int {
        var result = v
        result = 31 * result + r.contentHashCode()
        result = 31 * result + s.contentHashCode()
        result = 31 * result + serializedTx.contentHashCode()
        return result
    }
}

/** Template of the scriptPubKey/witness a Bitcoin input spends from. Maps to Trezor's `InputScriptType`. */
enum class TrezorInputScriptType {
    SPENDADDRESS,
    SPENDWITNESS,
    SPENDP2SHWITNESS,
    SPENDTAPROOT,
}

/** Template of the scriptPubKey a Bitcoin output pays to. Maps to Trezor's `OutputScriptType`. */
enum class TrezorOutputScriptType {
    PAYTOADDRESS,
    PAYTOWITNESS,
    PAYTOP2SHWITNESS,
    PAYTOTAPROOT,
}

/** A Bitcoin transaction input to sign. Numeric fields wider than 32 bits use [Long] to hold the full uint32 range. */
data class TrezorBtcInput(
    val addressN: List<Int>,
    val prevHash: ByteArray,
    val prevIndex: Int,
    val amount: Long,
    val scriptType: TrezorInputScriptType,
    val sequence: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrezorBtcInput) return false
        return addressN == other.addressN &&
            prevHash.contentEquals(other.prevHash) &&
            prevIndex == other.prevIndex &&
            amount == other.amount &&
            scriptType == other.scriptType &&
            sequence == other.sequence
    }

    override fun hashCode(): Int {
        var result = addressN.hashCode()
        result = 31 * result + prevHash.contentHashCode()
        result = 31 * result + prevIndex
        result = 31 * result + amount.hashCode()
        result = 31 * result + scriptType.hashCode()
        result = 31 * result + sequence.hashCode()
        return result
    }
}

/** A Bitcoin transaction output - payout to an external address, change back to this device, or an OP_RETURN payload. */
sealed class TrezorBtcOutput {
    abstract val amount: Long

    /** Payout to an external address. */
    data class Address(
        val address: String,
        override val amount: Long,
        val scriptType: TrezorOutputScriptType,
    ) : TrezorBtcOutput()

    /** Change output, whose address is derived on-device from [addressN]. */
    data class Change(
        val addressN: List<Int>,
        override val amount: Long,
        val scriptType: TrezorOutputScriptType,
    ) : TrezorBtcOutput()

    /** OP_RETURN data output - carries no value. */
    data class OpReturn(val opReturnData: ByteArray) : TrezorBtcOutput() {
        override val amount: Long = 0

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is OpReturn) return false
            return opReturnData.contentEquals(other.opReturnData)
        }

        override fun hashCode(): Int = opReturnData.contentHashCode()
    }
}

/** An unsigned Bitcoin transaction to sign on-device. */
data class TrezorBtcSignTx(
    val version: Int,
    val lockTime: Long,
    val inputs: List<TrezorBtcInput>,
    val outputs: List<TrezorBtcOutput>,
)

/** One input of a previous transaction, supplied by the caller so the device can verify the UTXO being spent. */
data class TrezorPrevTxInput(
    val prevHash: ByteArray,
    val prevIndex: Int,
    val scriptSig: ByteArray,
    val sequence: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrezorPrevTxInput) return false
        return prevHash.contentEquals(other.prevHash) &&
            prevIndex == other.prevIndex &&
            scriptSig.contentEquals(other.scriptSig) &&
            sequence == other.sequence
    }

    override fun hashCode(): Int {
        var result = prevHash.contentHashCode()
        result = 31 * result + prevIndex
        result = 31 * result + scriptSig.contentHashCode()
        result = 31 * result + sequence.hashCode()
        return result
    }
}

/** One output of a previous transaction, supplied by the caller so the device can verify the UTXO amount. */
data class TrezorPrevTxOutput(
    val amount: Long,
    val scriptPubkey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrezorPrevTxOutput) return false
        return amount == other.amount && scriptPubkey.contentEquals(other.scriptPubkey)
    }

    override fun hashCode(): Int = 31 * amount.hashCode() + scriptPubkey.contentHashCode()
}

/**
 * A previous transaction that supplies a UTXO spent by [TrezorBtcSignTx.inputs]. The caller
 * provides this data (the kit never fetches it); the device uses it to verify input amounts.
 */
data class TrezorPrevTx(
    val version: Int,
    val lockTime: Long,
    val inputs: List<TrezorPrevTxInput>,
    val outputs: List<TrezorPrevTxOutput>,
)

/** Result of signing a Bitcoin transaction: the fully serialized signed transaction and each input's signature. */
data class TrezorBtcSignResult(
    val serializedTx: ByteArray,
    val signatures: List<ByteArray>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrezorBtcSignResult) return false
        return serializedTx.contentEquals(other.serializedTx) &&
            signatures.size == other.signatures.size &&
            signatures.indices.all { signatures[it].contentEquals(other.signatures[it]) }
    }

    override fun hashCode(): Int {
        var result = serializedTx.contentHashCode()
        for (signature in signatures) result = 31 * result + signature.contentHashCode()
        return result
    }
}

/** A Stellar asset referenced by an operation. Maps to Trezor's `StellarAsset`/`StellarAssetType`. */
sealed class TrezorStellarAsset {
    data object Native : TrezorStellarAsset()
    data class AlphaNum4(val code: String, val issuer: String) : TrezorStellarAsset()
    data class AlphaNum12(val code: String, val issuer: String) : TrezorStellarAsset()
}

/** A Stellar transaction memo. Maps to Trezor's `StellarSignTx.StellarMemoType` and its `memo_*` fields. */
sealed class TrezorStellarMemo {
    data object None : TrezorStellarMemo()
    data class Text(val text: String) : TrezorStellarMemo()
    data class Id(val id: Long) : TrezorStellarMemo()

    data class Hash(val hash: ByteArray) : TrezorStellarMemo() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Hash) return false
            return hash.contentEquals(other.hash)
        }

        override fun hashCode(): Int = hash.contentHashCode()
    }

    data class ReturnHash(val hash: ByteArray) : TrezorStellarMemo() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ReturnHash) return false
            return hash.contentEquals(other.hash)
        }

        override fun hashCode(): Int = hash.contentHashCode()
    }
}

/**
 * One operation of a Stellar transaction. Only the operation kinds the kit currently signs -
 * payment, account creation, and change-trust - are modeled; the device supports more.
 */
sealed class TrezorStellarOperation {
    data class Payment(
        val destination: String,
        val asset: TrezorStellarAsset,
        val amount: Long,
        val sourceAccount: String?,
    ) : TrezorStellarOperation()

    data class CreateAccount(
        val destination: String,
        val startingBalance: Long,
        val sourceAccount: String?,
    ) : TrezorStellarOperation()

    data class ChangeTrust(
        val asset: TrezorStellarAsset,
        val limit: Long,
        val sourceAccount: String?,
    ) : TrezorStellarOperation()
}

/** An unsigned Stellar transaction to sign on-device, streamed operation by operation. */
data class TrezorStellarSignTx(
    val addressN: List<Int>,
    val networkPassphrase: String,
    val source: String,
    val fee: Int,
    val sequenceNumber: Long,
    val timeboundsStart: Long,
    val timeboundsEnd: Long,
    val memo: TrezorStellarMemo,
    val operations: List<TrezorStellarOperation>,
)
