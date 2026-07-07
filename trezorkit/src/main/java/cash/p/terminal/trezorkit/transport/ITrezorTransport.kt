package cash.p.terminal.trezorkit.transport

/**
 * Raw 64-byte packet transport for a Trezor device.
 *
 * Implementations only move bytes back and forth - they have no knowledge of the
 * wire-protocol framing (message type, length, payload). See `protocol.FramingCodec`
 * for that layer.
 */
interface ITrezorTransport {

    /** Opens the underlying connection. Must be called before [writePacket]/[readPacket]. */
    suspend fun open()

    /** Closes the underlying connection and releases any held resources. */
    fun close()

    /** Writes exactly [PACKET_SIZE] bytes to the device. */
    suspend fun writePacket(packet: ByteArray)

    /** Reads exactly [PACKET_SIZE] bytes from the device. */
    suspend fun readPacket(): ByteArray

    companion object {
        const val PACKET_SIZE = 64
    }
}
