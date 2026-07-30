package cash.p.terminal.trezorkit.transport

import org.junit.Assert.fail

internal suspend inline fun <reified T : Throwable> assertSuspendThrows(
    crossinline block: suspend () -> Unit,
): T {
    try {
        block()
        fail("Expected ${T::class.simpleName}")
    } catch (error: Throwable) {
        if (error !is T) throw error
        return error
    }
    error("Unreachable")
}
