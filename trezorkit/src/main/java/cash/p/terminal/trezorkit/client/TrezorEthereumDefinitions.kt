package cash.p.terminal.trezorkit.client

/** Device-requested identifiers for signed Trezor EVM display metadata. */
data class TrezorEthereumDefinitionRequest(
    val chainId: Long,
    val contractAddress: String?,
    val functionSignature: String?,
)

/** Signed metadata blobs returned to the device; every field is independently optional. */
data class TrezorEthereumDefinitions(
    val encodedNetwork: ByteArray? = null,
    val encodedToken: ByteArray? = null,
    val encodedDisplayFormat: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrezorEthereumDefinitions) return false
        return encodedNetwork.contentEquals(other.encodedNetwork) &&
            encodedToken.contentEquals(other.encodedToken) &&
            encodedDisplayFormat.contentEquals(other.encodedDisplayFormat)
    }

    override fun hashCode(): Int {
        var result = encodedNetwork.contentHashCode()
        result = 31 * result + encodedToken.contentHashCode()
        result = 31 * result + encodedDisplayFormat.contentHashCode()
        return result
    }
}

/** Supplies firmware-signed metadata without coupling the USB kit to a networking library. */
fun interface TrezorEthereumDefinitionsProvider {
    suspend fun getDefinitions(request: TrezorEthereumDefinitionRequest): TrezorEthereumDefinitions
}
