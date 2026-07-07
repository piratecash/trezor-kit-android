package cash.p.terminal.trezorkit.protocol

import cash.p.terminal.trezorkit.transport.ITrezorTransport

/**
 * In-memory [ITrezorTransport] for tests. Returns pre-scripted [responses] packets in order,
 * regardless of what was written - this mirrors a real device, which always replies to
 * whatever request/ack sequence the session sends without the test needing to inspect it.
 */
class FakeTrezorTransport(
    private val responses: List<ByteArray> = emptyList(),
) : ITrezorTransport {

    val writtenPackets = mutableListOf<ByteArray>()

    /** Invoked before each [readPacket] returns its scripted packet. Lets tests inject pauses/hangs. */
    var beforeRead: suspend (index: Int) -> Unit = {}

    private var readIndex = 0

    override suspend fun open() = Unit
    override fun close() = Unit

    override suspend fun writePacket(packet: ByteArray) {
        writtenPackets.add(packet)
    }

    override suspend fun readPacket(): ByteArray {
        beforeRead(readIndex)
        return responses[readIndex++]
    }
}

/** Decodes a flat list of written packets back into the sequence of messages they represent. */
fun List<ByteArray>.decodeMessages(): List<DecodedMessage> {
    val decoder = FramingDecoder()
    return mapNotNull { decoder.offer(it) }
}
