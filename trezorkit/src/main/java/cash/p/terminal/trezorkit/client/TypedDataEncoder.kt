package cash.p.terminal.trezorkit.client

import cash.p.terminal.trezorkit.TrezorException
import com.google.protobuf.ByteString
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereumEIP712.EthereumTypedDataStructAck
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereumEIP712.EthereumTypedDataStructAck.EthereumDataType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereumEIP712.EthereumTypedDataStructAck.EthereumFieldType
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereumEIP712.EthereumTypedDataStructAck.EthereumStructMember
import com.satoshilabs.trezor.lib.protobuf.TrezorMessageEthereumEIP712.EthereumTypedDataValueAck
import java.math.BigInteger

private const val EIP712_DOMAIN = "EIP712Domain"
private const val ADDRESS_SIZE = 20
private const val MAX_BYTES_SIZE = 32
private const val MAX_INT_BITS = 256
private const val MAX_ARRAY_LENGTH = 0xFFFF

private val ARRAY_TYPE = Regex("""(.+)\[(0|[1-9]\d*)?]""")
private val INT_TYPE = Regex("""(u?)int([1-9]\d*)""")
private val BYTES_TYPE = Regex("""bytes([1-9]\d*)?""")
private val DECIMAL_INTEGER = Regex("""-?\d+""")
private val HEX_INTEGER = Regex("""0[xX][0-9a-fA-F]+""")

/**
 * Answers the device's EIP-712 requests like trezorlib `sign_typed_data`. Construction validates
 * every reachable type and value, so malformed input fails before any USB I/O.
 */
internal class TypedDataEncoder(private val data: TrezorTypedData) {

    private val types = mapOf(EIP712_DOMAIN to emptyList<TrezorTypedMember>()) + data.types
    private val structAcks = mutableMapOf<String, EthereumTypedDataStructAck>()
    private val domainType = rootType(EIP712_DOMAIN)
    private val messageType = rootType(data.primaryType)

    // Domain-only data: firmware and trezorlib ignore the message and never request root 1.
    private val isDomainOnly = data.primaryType == EIP712_DOMAIN

    init {
        validate(domainType, data.domain)
        if (!isDomainOnly) validate(messageType, data.message)
    }

    fun structAck(name: String): EthereumTypedDataStructAck =
        structAcks[name] ?: throw TrezorException("Device requested unknown EIP-712 struct $name")

    fun valueAck(memberPath: List<Int>): EthereumTypedDataValueAck {
        var (type, value) = root(memberPath)
        for (index in memberPath.drop(1)) {
            when (type.dataType) {
                EthereumDataType.STRUCT -> {
                    val member = members(type).getOrNull(index) ?: throw invalidPath(memberPath)
                    type = member.type
                    value = value.field(member.name)
                }
                EthereumDataType.ARRAY -> {
                    value = value.items().getOrNull(index) ?: throw invalidPath(memberPath)
                    type = type.entryType
                }
                else -> throw invalidPath(memberPath)
            }
        }
        return EthereumTypedDataValueAck.newBuilder().setValue(ByteString.copyFrom(encode(type, value))).build()
    }

    private fun root(memberPath: List<Int>): Pair<EthereumFieldType, TrezorTypedValue> = when (memberPath.firstOrNull()) {
        0 -> domainType to data.domain
        1 -> if (isDomainOnly) throw invalidPath(memberPath) else messageType to data.message
        else -> throw invalidPath(memberPath)
    }

    private fun invalidPath(memberPath: List<Int>) = TrezorException("Device requested invalid EIP-712 member path $memberPath")

    private fun rootType(name: String): EthereumFieldType {
        val type = fieldType(name)
        require(type.dataType == EthereumDataType.STRUCT) { "EIP-712 type $name is not a struct" }
        collectStructs(name)
        return type
    }

    private fun collectStructs(name: String) {
        if (name in structAcks) return
        val members = types.getValue(name).map { member ->
            EthereumStructMember.newBuilder().setName(member.name).setType(fieldType(member.type)).build()
        }
        structAcks[name] = EthereumTypedDataStructAck.newBuilder().addAllMembers(members).build()
        members.forEach { member -> member.type.referencedStruct()?.let(::collectStructs) }
    }

    private fun members(type: EthereumFieldType): List<EthereumStructMember> = structAcks.getValue(type.structName).membersList

    private fun validate(type: EthereumFieldType, value: TrezorTypedValue) {
        when (type.dataType) {
            EthereumDataType.STRUCT -> members(type).forEach { validate(it.type, value.field(it.name)) }
            EthereumDataType.ARRAY -> {
                encode(type, value)
                val items = value.items()
                require(!type.hasSize() || items.size == type.size) { "EIP-712 array must have ${type.size} items" }
                items.forEach { validate(type.entryType, it) }
            }
            else -> encode(type, value)
        }
    }

    private fun fieldType(type: String): EthereumFieldType {
        val builder = EthereumFieldType.newBuilder()
        val array = ARRAY_TYPE.matchEntire(type)
        val integer = INT_TYPE.matchEntire(type)
        val bytesSize = BYTES_TYPE.matchEntire(type)?.groupValues?.get(1)
        when {
            array != null -> builder.setArray(type, array.groupValues[1], array.groupValues[2])
            integer != null -> builder
                .setDataType(if (integer.groupValues[1].isEmpty()) EthereumDataType.INT else EthereumDataType.UINT)
                .setSize(requireSize(integer.groupValues[2], type) { it % 8 == 0 && it <= MAX_INT_BITS } / 8)
            bytesSize == "" -> builder.setDataType(EthereumDataType.BYTES)
            bytesSize != null -> builder.setDataType(EthereumDataType.BYTES)
                .setSize(requireSize(bytesSize, type) { it <= MAX_BYTES_SIZE })
            type == "string" -> builder.setDataType(EthereumDataType.STRING)
            type == "bool" -> builder.setDataType(EthereumDataType.BOOL)
            type == "address" -> builder.setDataType(EthereumDataType.ADDRESS)
            type in types -> builder.setDataType(EthereumDataType.STRUCT).setStructName(type).setSize(types.getValue(type).size)
            else -> throw IllegalArgumentException("Unsupported EIP-712 type: $type")
        }
        return builder.build()
    }

    private fun EthereumFieldType.Builder.setArray(type: String, entryType: String, size: String): EthereumFieldType.Builder {
        val entry = fieldType(entryType)
        require(entry.dataType != EthereumDataType.ARRAY) { "Nested EIP-712 arrays are not supported: $type" }
        setDataType(EthereumDataType.ARRAY).setEntryType(entry)
        if (size.isNotEmpty()) setSize(requireSize(size, type) { it <= MAX_ARRAY_LENGTH })
        return this
    }
}

private fun requireSize(text: String, type: String, isSupported: (Int) -> Boolean): Int =
    text.toIntOrNull()?.takeIf(isSupported) ?: throw IllegalArgumentException("Unsupported EIP-712 type: $type")

private fun EthereumFieldType.referencedStruct(): String? = when (dataType) {
    EthereumDataType.STRUCT -> structName
    EthereumDataType.ARRAY -> entryType.referencedStruct()
    else -> null
}

/** Encodes a value the way trezorlib `encode_data` does; an array itself encodes as its uint16 length. */
private fun encode(type: EthereumFieldType, value: TrezorTypedValue): ByteArray = when (type.dataType) {
    EthereumDataType.ARRAY -> value.items().size.let { length ->
        require(length <= MAX_ARRAY_LENGTH) { "EIP-712 array is too long" }
        byteArrayOf((length shr 8).toByte(), length.toByte())
    }
    EthereumDataType.STRUCT -> throw TrezorException("Device requested a direct value of EIP-712 struct ${type.structName}")
    EthereumDataType.STRING -> value.text().toByteArray(Charsets.UTF_8)
    EthereumDataType.BOOL -> when (value.text()) {
        "true" -> byteArrayOf(1)
        "false" -> byteArrayOf(0)
        else -> throw IllegalArgumentException("Invalid EIP-712 bool value")
    }
    EthereumDataType.ADDRESS -> value.text().hexToByteArray()
        .also { require(it.size == ADDRESS_SIZE) { "EIP-712 address must be $ADDRESS_SIZE bytes" } }
    EthereumDataType.BYTES -> value.text().hexToByteArray()
        .also { require(!type.hasSize() || it.size == type.size) { "EIP-712 bytes${type.size} has a wrong length" } }
    EthereumDataType.UINT -> encodeInteger(value.text(), type.size, signed = false)
    EthereumDataType.INT -> encodeInteger(value.text(), type.size, signed = true)
}

/** Big-endian, [byteSize] bytes, two's complement for negative values. */
private fun encodeInteger(text: String, byteSize: Int, signed: Boolean): ByteArray {
    val value = when {
        HEX_INTEGER.matches(text) -> BigInteger(text.substring(2), 16)
        DECIMAL_INTEGER.matches(text) -> BigInteger(text)
        else -> throw IllegalArgumentException("Invalid EIP-712 integer value")
    }
    val bits = byteSize * 8
    val min = if (signed) BigInteger.ONE.shiftLeft(bits - 1).negate() else BigInteger.ZERO
    val limit = BigInteger.ONE.shiftLeft(if (signed) bits - 1 else bits)
    require(value >= min && value < limit) { "EIP-712 integer is out of range for ${bits}-bit type" }

    val raw = (if (value.signum() < 0) value + BigInteger.ONE.shiftLeft(bits) else value).toByteArray()
    return ByteArray(byteSize).also {
        raw.copyInto(it, destinationOffset = maxOf(0, byteSize - raw.size), startIndex = maxOf(0, raw.size - byteSize))
    }
}

private fun TrezorTypedValue.field(name: String): TrezorTypedValue {
    require(this is TrezorTypedValue.Struct) { "Expected an EIP-712 struct value for member $name" }
    return requireNotNull(fields[name]) { "Missing EIP-712 value for member $name" }
}

private fun TrezorTypedValue.items(): List<TrezorTypedValue> {
    require(this is TrezorTypedValue.Array) { "Expected an EIP-712 array value" }
    return items
}

private fun TrezorTypedValue.text(): String {
    require(this is TrezorTypedValue.Primitive) { "Expected an EIP-712 primitive value" }
    return value
}
