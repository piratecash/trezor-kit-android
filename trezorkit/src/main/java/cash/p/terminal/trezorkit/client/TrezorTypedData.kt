package cash.p.terminal.trezorkit.client

/** One member of an EIP-712 struct definition, e.g. `name: "amount", type: "uint256"`. */
data class TrezorTypedMember(val name: String, val type: String)

/**
 * EIP-712 typed data to sign on-device. [types] holds every struct definition, including
 * `EIP712Domain` (treated as empty when absent).
 */
data class TrezorTypedData(
    val primaryType: String,
    val types: Map<String, List<TrezorTypedMember>>,
    val domain: TrezorTypedValue.Struct,
    val message: TrezorTypedValue.Struct,
)

sealed class TrezorTypedValue {
    data class Struct(val fields: Map<String, TrezorTypedValue>) : TrezorTypedValue()

    data class Array(val items: List<TrezorTypedValue>) : TrezorTypedValue()

    /** Value as its JSON text: a string, `true`/`false`, a decimal or `0x`-hex integer, or `0x`-hex bytes. */
    data class Primitive(val value: String) : TrezorTypedValue()
}
