package cash.p.terminal.trezorkit.client

import java.io.ByteArrayOutputStream

/**
 * Minimal RLP (Recursive Length Prefix) encoder - Ethereum Yellow Paper, Appendix B. No external
 * dependency: used only to deterministically reassemble a signed EVM transaction's serialized
 * bytes from the fields sent to the device plus the v/r/s signature the device returns.
 */
internal object Rlp {

    /** Encodes a single byte string per RLP's string encoding rules. */
    fun encodeBytes(data: ByteArray): ByteArray = when {
        data.size == 1 && data[0].toInt() and 0xFF < 0x80 -> data
        else -> lengthPrefix(data.size, shortBase = 0x80, longBase = 0xb7) + data
    }

    /** Encodes a list of already RLP-encoded elements. */
    fun encodeList(elements: List<ByteArray>): ByteArray {
        val payload = ByteArrayOutputStream().apply { elements.forEach(::write) }.toByteArray()
        return lengthPrefix(payload.size, shortBase = 0xc0, longBase = 0xf7) + payload
    }

    private fun lengthPrefix(length: Int, shortBase: Int, longBase: Int): ByteArray {
        if (length < 56) return byteArrayOf((shortBase + length).toByte())
        val lengthBytes = length.toLong().toMinimalBigEndianBytes()
        return byteArrayOf((longBase + lengthBytes.size).toByte()) + lengthBytes
    }
}

/** Trims leading zero bytes, per RLP's canonical (minimal) integer encoding. */
internal fun ByteArray.trimLeadingZeros(): ByteArray {
    val firstNonZero = indexOfFirst { it != 0.toByte() }
    return if (firstNonZero < 0) ByteArray(0) else copyOfRange(firstNonZero, size)
}

private fun Long.toMinimalBigEndianBytes(): ByteArray =
    ByteArray(8) { i -> (this ushr ((7 - i) * 8)).toByte() }.trimLeadingZeros()

/** Minimal big-endian encoding for RLP fields given as Int/Long, e.g. `v`/`yParity` and `chainId`. */
internal fun Int.toRlpBytes(): ByteArray = toLong().toMinimalBigEndianBytes()

internal fun Long.toRlpBytes(): ByteArray = toMinimalBigEndianBytes()
