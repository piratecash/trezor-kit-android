package cash.p.terminal.trezorkit.protocol

import cash.p.terminal.trezorkit.TrezorTransportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FramingCodecTest {

    @Test
    fun encode_shortMessage_producesSinglePacketWithHeaderAndPadding() {
        val msgType = 55
        val payload = ByteArray(10) { (it + 1).toByte() }

        val packets = FramingCodec.encode(msgType, payload)

        assertEquals(1, packets.size)
        val packet = packets[0]
        assertEquals(FramingCodec.PACKET_SIZE, packet.size)
        assertEquals(0x3F.toByte(), packet[0])
        assertEquals(0x23.toByte(), packet[1])
        assertEquals(0x23.toByte(), packet[2])
        assertEquals(((msgType ushr 8) and 0xFF).toByte(), packet[3])
        assertEquals((msgType and 0xFF).toByte(), packet[4])
        assertEquals(0.toByte(), packet[5])
        assertEquals(0.toByte(), packet[6])
        assertEquals(0.toByte(), packet[7])
        assertEquals(payload.size.toByte(), packet[8])
        assertArrayEquals(payload, packet.copyOfRange(9, 9 + payload.size))
        // Everything after the payload must be zero padding.
        for (i in 9 + payload.size until packet.size) {
            assertEquals("byte at index $i must be zero padding", 0, packet[i].toInt())
        }
    }

    @Test
    fun encode_longMessage_spansMultiplePacketsWithZeroPaddedLastPacket() {
        val msgType = 17
        val payload = ByteArray(150) { it.toByte() }

        val packets = FramingCodec.encode(msgType, payload)

        // header(8) + payload(150) = 158 bytes, 63 content bytes per packet -> 3 packets
        assertEquals(3, packets.size)
        packets.forEach { packet ->
            assertEquals(FramingCodec.PACKET_SIZE, packet.size)
            assertEquals(0x3F.toByte(), packet[0])
        }

        // Last packet: 158 - 2*63 = 32 content bytes used, rest must be zero padding.
        val lastPacket = packets.last()
        val usedContentBytes = 32
        for (i in (1 + usedContentBytes) until lastPacket.size) {
            assertEquals("tail byte at index $i must be zero padding", 0, lastPacket[i].toInt())
        }
    }

    @Test
    fun decoder_shortMessage_assemblesOnFirstPacket() {
        val msgType = 55
        val payload = ByteArray(10) { (it + 1).toByte() }
        val packets = FramingCodec.encode(msgType, payload)
        val decoder = FramingDecoder()

        val result = decoder.offer(packets[0])

        assertEquals(DecodedMessage(msgType, payload), result)
    }

    @Test
    fun decoder_longMessage_returnsNullUntilLastPacketThenAssembles() {
        val msgType = 17
        val payload = ByteArray(150) { it.toByte() }
        val packets = FramingCodec.encode(msgType, payload)
        val decoder = FramingDecoder()

        assertNull(decoder.offer(packets[0]))
        assertNull(decoder.offer(packets[1]))
        val result = decoder.offer(packets[2])

        assertEquals(DecodedMessage(msgType, payload), result)
    }

    @Test
    fun decoder_afterCompletingMessage_isReadyForNextMessage() {
        val decoder = FramingDecoder()
        val first = FramingCodec.encode(55, ByteArray(5) { it.toByte() })
        val second = FramingCodec.encode(17, ByteArray(5) { (it + 10).toByte() })

        val firstResult = decoder.offer(first[0])
        val secondResult = decoder.offer(second[0])

        assertTrue(firstResult != null && secondResult != null)
        assertEquals(DecodedMessage(55, ByteArray(5) { it.toByte() }), firstResult)
        assertEquals(DecodedMessage(17, ByteArray(5) { (it + 10).toByte() }), secondResult)
    }

    @Test
    fun decoder_invalidReportByte_throwsTrezorTransportException() {
        val packet = FramingCodec.encode(55, ByteArray(5) { it.toByte() })[0]
        packet[0] = 0x00
        val decoder = FramingDecoder()

        assertThrows(TrezorTransportException::class.java) { decoder.offer(packet) }
    }
}
