package cash.p.terminal.trezorkit.protocol

import com.google.protobuf.MessageLite
import com.satoshilabs.trezor.lib.protobuf.TrezorMessage.MessageType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.GetPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.PublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.SignTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckInput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckOutput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckPrevInput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckPrevMeta
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxAckPrevOutput
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageBitcoin.TxRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.ButtonAck
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.ButtonRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.Failure
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.PassphraseAck
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.PassphraseRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageCommon.Success
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumDefinitionAck
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumDefinitionRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumGetPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumMessageSignature
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumSignMessage
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumSignTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumSignTxEIP1559
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumSignTypedHash
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumTxAck
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumTxRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereum.EthereumTypedDataSignature
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereumEIP712.EthereumSignTypedData
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereumEIP712.EthereumTypedDataStructAck
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereumEIP712.EthereumTypedDataStructRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereumEIP712.EthereumTypedDataValueAck
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereumEIP712.EthereumTypedDataValueRequest
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.Features
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.GetFeatures
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageManagement.Initialize
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageSolana.SolanaGetPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageSolana.SolanaPublicKey
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageSolana.SolanaSignTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageSolana.SolanaTxSignature
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageStellar.StellarAddress
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
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronSignTx
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronSignature
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronTransferContract
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageTron.TronTriggerSmartContract
import kotlin.reflect.KClass

/**
 * Maps between the wire-level [MessageType] number and the concrete protobuf-lite message
 * class carried in a Trezor wire message.
 *
 * Protobuf-lite messages carry no runtime descriptor (unlike full `GeneratedMessageV3`), so
 * this mapping is kept as an explicit data table rather than relying on reflection.
 *
 * Scope covers the management/common messages needed to run a session (Initialize,
 * GetFeatures, Features, ButtonRequest, ButtonAck, PassphraseRequest, PassphraseAck,
 * Failure, Success) plus exactly the coin messages used by
 * `cash.p.terminal.trezorkit.client.UsbTrezorClient`: Bitcoin/Ethereum/Solana
 * GetPublicKey+PublicKey (account creation derives addresses locally from the xpub/pubkey,
 * so on-device GetAddress is not needed for those coins), Stellar and Tron GetAddress+Address
 * (no local derivation for those, so the address itself is fetched from the device),
 * the EVM sign flow (EthereumSignTx, EthereumSignTxEIP1559, EthereumTxRequest,
 * EthereumTxAck, EthereumDefinitionRequest, EthereumDefinitionAck), the EVM personal-message sign flow (EthereumSignMessage,
 * EthereumMessageSignature), the EIP-712 typed-data sign flows (EthereumSignTypedData, the
 * EthereumTypedDataStruct/Value Request+Ack pairs, EthereumSignTypedHash, EthereumTypedDataSignature),
 * and the Bitcoin sign flow (SignTx, TxRequest, and the TxAck family).
 * `HDNodeType` is not registered here - it is an embedded field type nested inside
 * `PublicKey`/`EthereumPublicKey`, never a standalone wire message.
 *
 * `TxAckInput`/`TxAckOutput`/`TxAckPrevMeta`/`TxAckPrevInput`/`TxAckPrevOutput` are five
 * distinct generated classes that all share the single wire number `MessageType_TxAck` (see
 * `option (wire_type) = 22` on each in messages-bitcoin.proto) - the device tells them apart
 * by which sub-fields are populated, not by a distinct wire number. They are outgoing-only
 * (the device only ever replies with `TxRequest`), so the number-to-parser side of this
 * mapping is never exercised for them; only [typeOf] is.
 *
 * Also covers the Solana sign flow (SolanaSignTx, SolanaTxSignature), the Stellar sign flow
 * (StellarSignTx, StellarTxOpRequest, StellarPaymentOp, StellarCreateAccountOp,
 * StellarChangeTrustOp, StellarSignedTx), and the Tron sign flow (TronSignTx,
 * TronContractRequest, TronTransferContract, TronTriggerSmartContract, TronSignature) - each
 * of those has its own distinct wire number, no sharing like the Bitcoin TxAck family above.
 * Tron staking/vote contracts (Freeze/Unfreeze/WithdrawUnfreeze/VoteWitness, wire numbers
 * 2207-2210) are intentionally not registered - the client never sends or receives them.
 */
object MessageTypeRegistry {

    private class Entry<T : MessageLite>(
        val number: Int,
        val kClass: KClass<T>,
        val parser: (ByteArray) -> T,
    )

    private val entries = listOf(
        Entry(MessageType.MessageType_Initialize_VALUE, Initialize::class, Initialize::parseFrom),
        Entry(MessageType.MessageType_GetFeatures_VALUE, GetFeatures::class, GetFeatures::parseFrom),
        Entry(MessageType.MessageType_Features_VALUE, Features::class, Features::parseFrom),
        Entry(MessageType.MessageType_ButtonRequest_VALUE, ButtonRequest::class, ButtonRequest::parseFrom),
        Entry(MessageType.MessageType_ButtonAck_VALUE, ButtonAck::class, ButtonAck::parseFrom),
        Entry(MessageType.MessageType_PassphraseRequest_VALUE, PassphraseRequest::class, PassphraseRequest::parseFrom),
        Entry(MessageType.MessageType_PassphraseAck_VALUE, PassphraseAck::class, PassphraseAck::parseFrom),
        Entry(MessageType.MessageType_Failure_VALUE, Failure::class, Failure::parseFrom),
        Entry(MessageType.MessageType_Success_VALUE, Success::class, Success::parseFrom),
        Entry(MessageType.MessageType_GetPublicKey_VALUE, GetPublicKey::class, GetPublicKey::parseFrom),
        Entry(MessageType.MessageType_PublicKey_VALUE, PublicKey::class, PublicKey::parseFrom),
        Entry(MessageType.MessageType_EthereumGetPublicKey_VALUE, EthereumGetPublicKey::class, EthereumGetPublicKey::parseFrom),
        Entry(MessageType.MessageType_EthereumPublicKey_VALUE, EthereumPublicKey::class, EthereumPublicKey::parseFrom),
        Entry(MessageType.MessageType_SolanaGetPublicKey_VALUE, SolanaGetPublicKey::class, SolanaGetPublicKey::parseFrom),
        Entry(MessageType.MessageType_SolanaPublicKey_VALUE, SolanaPublicKey::class, SolanaPublicKey::parseFrom),
        Entry(MessageType.MessageType_StellarGetAddress_VALUE, StellarGetAddress::class, StellarGetAddress::parseFrom),
        Entry(MessageType.MessageType_StellarAddress_VALUE, StellarAddress::class, StellarAddress::parseFrom),
        Entry(MessageType.MessageType_EthereumSignTx_VALUE, EthereumSignTx::class, EthereumSignTx::parseFrom),
        Entry(
            MessageType.MessageType_EthereumSignTxEIP1559_VALUE,
            EthereumSignTxEIP1559::class,
            EthereumSignTxEIP1559::parseFrom,
        ),
        Entry(MessageType.MessageType_EthereumTxRequest_VALUE, EthereumTxRequest::class, EthereumTxRequest::parseFrom),
        Entry(MessageType.MessageType_EthereumTxAck_VALUE, EthereumTxAck::class, EthereumTxAck::parseFrom),
        Entry(
            MessageType.MessageType_EthereumDefinitionRequest_VALUE,
            EthereumDefinitionRequest::class,
            EthereumDefinitionRequest::parseFrom,
        ),
        Entry(
            MessageType.MessageType_EthereumDefinitionAck_VALUE,
            EthereumDefinitionAck::class,
            EthereumDefinitionAck::parseFrom,
        ),
        Entry(MessageType.MessageType_EthereumSignMessage_VALUE, EthereumSignMessage::class, EthereumSignMessage::parseFrom),
        Entry(
            MessageType.MessageType_EthereumMessageSignature_VALUE,
            EthereumMessageSignature::class,
            EthereumMessageSignature::parseFrom,
        ),
        Entry(MessageType.MessageType_EthereumSignTypedData_VALUE, EthereumSignTypedData::class, EthereumSignTypedData::parseFrom),
        Entry(
            MessageType.MessageType_EthereumTypedDataStructRequest_VALUE,
            EthereumTypedDataStructRequest::class,
            EthereumTypedDataStructRequest::parseFrom,
        ),
        Entry(
            MessageType.MessageType_EthereumTypedDataStructAck_VALUE,
            EthereumTypedDataStructAck::class,
            EthereumTypedDataStructAck::parseFrom,
        ),
        Entry(
            MessageType.MessageType_EthereumTypedDataValueRequest_VALUE,
            EthereumTypedDataValueRequest::class,
            EthereumTypedDataValueRequest::parseFrom,
        ),
        Entry(
            MessageType.MessageType_EthereumTypedDataValueAck_VALUE,
            EthereumTypedDataValueAck::class,
            EthereumTypedDataValueAck::parseFrom,
        ),
        Entry(
            MessageType.MessageType_EthereumTypedDataSignature_VALUE,
            EthereumTypedDataSignature::class,
            EthereumTypedDataSignature::parseFrom,
        ),
        Entry(MessageType.MessageType_EthereumSignTypedHash_VALUE, EthereumSignTypedHash::class, EthereumSignTypedHash::parseFrom),
        Entry(MessageType.MessageType_SignTx_VALUE, SignTx::class, SignTx::parseFrom),
        Entry(MessageType.MessageType_TxRequest_VALUE, TxRequest::class, TxRequest::parseFrom),
        Entry(MessageType.MessageType_TxAck_VALUE, TxAckInput::class, TxAckInput::parseFrom),
        Entry(MessageType.MessageType_TxAck_VALUE, TxAckOutput::class, TxAckOutput::parseFrom),
        Entry(MessageType.MessageType_TxAck_VALUE, TxAckPrevMeta::class, TxAckPrevMeta::parseFrom),
        Entry(MessageType.MessageType_TxAck_VALUE, TxAckPrevInput::class, TxAckPrevInput::parseFrom),
        Entry(MessageType.MessageType_TxAck_VALUE, TxAckPrevOutput::class, TxAckPrevOutput::parseFrom),
        Entry(MessageType.MessageType_SolanaSignTx_VALUE, SolanaSignTx::class, SolanaSignTx::parseFrom),
        Entry(MessageType.MessageType_SolanaTxSignature_VALUE, SolanaTxSignature::class, SolanaTxSignature::parseFrom),
        Entry(MessageType.MessageType_StellarSignTx_VALUE, StellarSignTx::class, StellarSignTx::parseFrom),
        Entry(MessageType.MessageType_StellarTxOpRequest_VALUE, StellarTxOpRequest::class, StellarTxOpRequest::parseFrom),
        Entry(MessageType.MessageType_StellarPaymentOp_VALUE, StellarPaymentOp::class, StellarPaymentOp::parseFrom),
        Entry(
            MessageType.MessageType_StellarCreateAccountOp_VALUE,
            StellarCreateAccountOp::class,
            StellarCreateAccountOp::parseFrom,
        ),
        Entry(MessageType.MessageType_StellarChangeTrustOp_VALUE, StellarChangeTrustOp::class, StellarChangeTrustOp::parseFrom),
        Entry(MessageType.MessageType_StellarSignedTx_VALUE, StellarSignedTx::class, StellarSignedTx::parseFrom),
        Entry(MessageType.MessageType_TronGetAddress_VALUE, TronGetAddress::class, TronGetAddress::parseFrom),
        Entry(MessageType.MessageType_TronAddress_VALUE, TronAddress::class, TronAddress::parseFrom),
        Entry(MessageType.MessageType_TronSignTx_VALUE, TronSignTx::class, TronSignTx::parseFrom),
        Entry(MessageType.MessageType_TronSignature_VALUE, TronSignature::class, TronSignature::parseFrom),
        Entry(MessageType.MessageType_TronContractRequest_VALUE, TronContractRequest::class, TronContractRequest::parseFrom),
        Entry(
            MessageType.MessageType_TronTransferContract_VALUE,
            TronTransferContract::class,
            TronTransferContract::parseFrom,
        ),
        Entry(
            MessageType.MessageType_TronTriggerSmartContract_VALUE,
            TronTriggerSmartContract::class,
            TronTriggerSmartContract::parseFrom,
        ),
    )

    private val numbersByClass: Map<KClass<out MessageLite>, Int> =
        entries.associate { it.kClass to it.number }

    private val parsersByNumber: Map<Int, (ByteArray) -> MessageLite> =
        entries.associate { it.number to it.parser }

    /** Returns the wire [MessageType] number for an outgoing [message]. */
    fun typeOf(message: MessageLite): Int =
        numbersByClass[message::class]
            ?: error("No MessageType registered for ${message::class.simpleName}")

    /** Parses an incoming message identified by [msgType] from its raw [payload]. */
    fun parse(msgType: Int, payload: ByteArray): MessageLite =
        (parsersByNumber[msgType] ?: error("No parser registered for MessageType $msgType"))(payload)
}
