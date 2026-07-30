package cash.p.terminal.trezorkit.transport

/**
 * Lease-scoped, blocking packet channel for native integrations.
 *
 * The coordinator owns the underlying Android connection. Callers receive only raw packet I/O.
 * Methods must run off the main thread. [deadlineNanos] uses the [System.nanoTime] time base and
 * must remain unchanged across every packet read that belongs to one wire-protocol response.
 */
interface TrezorRawUsbChannel {
    fun writePacket(packet: ByteArray)

    fun readPacket(): ByteArray

    fun readPacket(deadlineNanos: Long): ByteArray

    fun cancel()
}
