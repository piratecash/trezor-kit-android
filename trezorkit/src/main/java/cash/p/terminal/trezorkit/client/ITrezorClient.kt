package cash.p.terminal.trezorkit.client

/**
 * Entry point for talking to a Trezor device. One [connect] call maps to one USB session,
 * so any on-device PIN entry happens at most once per call - every operation run inside
 * [block] shares that same unlocked session.
 */
interface ITrezorClient {
    suspend fun <T> connect(block: suspend TrezorClientSession.() -> T): T
}

/** Device operations available within a single [ITrezorClient.connect] session. */
interface TrezorClientSession {
    suspend fun getFeatures(): TrezorFeatures

    /** Derives one or more public keys/addresses in a single session, in the order requested. */
    suspend fun getPublicKeys(requests: List<TrezorPublicKeyRequest>): List<TrezorKeyResult>

    /** Signs an EVM transaction on-device, streaming [TrezorEvmTx.data] in chunks as requested. */
    suspend fun signEthereum(tx: TrezorEvmTx): TrezorSignature

    /** Signs an EIP-191 personal message on-device (ethereumSignMessage) and returns signature + address. */
    suspend fun signEthereumMessage(addressN: List<Int>, message: ByteArray): TrezorMessageSignature

    /**
     * Signs a Bitcoin transaction on-device, streaming input/output/previous-transaction
     * details as the device requests them.
     *
     * @param coinName Trezor coin name, e.g. "Bitcoin" or "Testnet".
     * @param prevTxByHash Previous transactions referenced by [TrezorBtcInput.prevHash], keyed
     *   by that same byte array's lowercase hex encoding (no "0x" prefix, no byte-order reversal).
     */
    suspend fun signBitcoin(coinName: String, tx: TrezorBtcSignTx, prevTxByHash: Map<String, TrezorPrevTx>): TrezorBtcSignResult

    /** Signs a pre-serialized Solana transaction on-device and returns the raw signature. */
    suspend fun signSolana(addressN: List<Int>, serializedTx: ByteArray): ByteArray

    /** Signs a Stellar transaction on-device, streaming its operations one by one, and returns the raw signature. */
    suspend fun signStellar(tx: TrezorStellarSignTx): ByteArray
}
