package com.tribal.vpn.ssh

/**
 * Rate-limits SOCKS5 handshake-refusal log lines. The relay can refuse
 * dozens of handshakes per second (hev retries its UDP channel relentlessly
 * while UDP is unsupported), and every durable log line is fsync'd - one
 * line per refusal would churn the log and drown real events.
 *
 * Contract: each distinct shape (a stable reason key like "cmd=0x05") is
 * logged once immediately, then at most one count line per shape per
 * window (10s default). Repeats inside the window are counted silently.
 *
 * Pure JVM - the clock is injected so the behavior is unit-testable
 * without sleeping.
 */
class Socks5Refusals(
    private val windowMs: Long = 10_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val emit: (String) -> Unit
) {

    private class Tally(var suppressed: Int, var lastEmitAt: Long)

    private val shapes = HashMap<String, Tally>()

    @Synchronized
    fun refused(shape: String, detail: String) {
        val now = clock()
        val t = shapes[shape]
        if (t == null) {
            // First occurrence of this shape: log it with full detail.
            shapes[shape] = Tally(0, now)
            emit("SOCKS5 refused: $shape ($detail)")
            return
        }
        if (now - t.lastEmitAt >= windowMs) {
            // Window elapsed: emit one aggregated count line, then reset.
            emit("SOCKS5 refused: $shape x${t.suppressed + 1} in ~${windowMs / 1000}s (e.g. $detail)")
            t.suppressed = 0
            t.lastEmitAt = now
        } else {
            t.suppressed++
        }
    }
}
