package cash.p.terminal.trezorkit.protocol

import cash.p.terminal.trezorkit.TrezorTransportException

/** Trezor wire-protocol v1 magic byte, repeated twice at the start of every message header. */
private const val MAGIC_BYTE = 0x23.toByte()

/** Header layout: 2 magic bytes + 2-byte big-endian message type + 4-byte big-endian payload length. */
private const val HEADER_SIZE = 8

/** USB HID report id prefixed to every 64-byte packet. */
private const val REPORT_ID = 0x3F.toByte()

/** Number of content bytes available in a packet after the report-id byte. */
private const val CONTENT_SIZE = FramingCodec.PACKET_SIZE - 1

/**
 * Encodes/decodes Trezor wire-protocol v1 messages into/from 64-byte USB packets.
 *
 * Message layout: `MAGIC(2) + msgType(uint16 BE) + dataLen(uint32 BE) + payload`.
 * That byte stream is split into [PACKET_SIZE]-byte packets, each prefixed with the
 * report id [REPORT_ID]; the final packet is zero-padded to [PACKET_SIZE] bytes.
 */
object FramingCodec {
    const val PACKET_SIZE = 64

    /** Encodes [payload] of type [msgType] into a sequence of [PACKET_SIZE]-byte packets. */
    fun encode(msgType: Int, payload: ByteArray): List<ByteArray> {
        val header = byteArrayOf(
            MAGIC_BYTE,
            MAGIC_BYTE,
            (msgType ushr 8).toByte(),
            msgType.toByte(),
            (payload.size ushr 24).toByte(),
            (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(),
            payload.size.toByte(),
        )
        return (header + payload).toList()
            .chunked(CONTENT_SIZE)
            .map(::buildPacket)
    }

    private fun buildPacket(content: List<Byte>): ByteArray {
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = REPORT_ID
        content.forEachIndexed { index, byte -> packet[index + 1] = byte }
        return packet
    }
}

/** A fully assembled Trezor wire-protocol message. */
data class DecodedMessage(val msgType: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedMessage) return false
        return msgType == other.msgType && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * msgType + payload.contentHashCode()
}

/**
 * Stateful decoder that accumulates incoming [FramingCodec.PACKET_SIZE]-byte packets and
 * returns a [DecodedMessage] once `dataLen` bytes of payload have been received.
 */
class FramingDecoder {
    private var msgType: Int = -1
    private var dataLen: Int = -1
    private val payload = mutableListOf<Byte>()

    /** Feeds one packet into the decoder. Returns the completed message, or `null` if more packets are needed. */
    fun offer(packet: ByteArray): DecodedMessage? {
        require(packet.size == FramingCodec.PACKET_SIZE) {
            "Packet must be exactly ${FramingCodec.PACKET_SIZE} bytes, got ${packet.size}"
        }
        if (packet[0] != REPORT_ID) {
            throw TrezorTransportException(
                "Corrupt frame: invalid USB report id 0x${(packet[0].toInt() and 0xFF).toString(16)}, expected 0x3f"
            )
        }
        val content = packet.drop(1)
        if (dataLen == -1) {
            startNewMessage(content)
        } else {
            payload.addAll(content)
        }
        if (payload.size < dataLen) return null

        val result = DecodedMessage(msgType, payload.subList(0, dataLen).toByteArray())
        reset()
        return result
    }

    private fun startNewMessage(content: List<Byte>) {
        require(content[0] == MAGIC_BYTE && content[1] == MAGIC_BYTE) { "Invalid magic bytes in packet header" }
        msgType = ((content[2].toInt() and 0xFF) shl 8) or (content[3].toInt() and 0xFF)
        dataLen = ((content[4].toInt() and 0xFF) shl 24) or
            ((content[5].toInt() and 0xFF) shl 16) or
            ((content[6].toInt() and 0xFF) shl 8) or
            (content[7].toInt() and 0xFF)
        payload.addAll(content.subList(HEADER_SIZE, content.size))
    }

    private fun reset() {
        msgType = -1
        dataLen = -1
        payload.clear()
    }
}
