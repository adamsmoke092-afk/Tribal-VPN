package com.tribal.vpn.ssh

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One live transport for a DNS query - opened by the caller so the
 * resolver stays testable and never touches sshj directly.
 */
internal class DnsIo(
    val input: InputStream,
    val output: OutputStream,
    val close: () -> Unit
)

/**
 * DNS wire codec (RFC 1035 message format over DNS/TCP framing:
 * [len:u16 BE][message], RFC 7766). Kept as an object for byte-exact
 * JVM tests.
 */
internal object DnsCodec {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    fun buildQuery(id: Int, name: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id shr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0x01)
        out.write(0x00) // flags: recursion desired
        out.write(0)
        out.write(1) // QDCOUNT = 1
        out.write(0)
        out.write(0) // ANCOUNT
        out.write(0)
        out.write(0) // NSCOUNT
        out.write(0)
        out.write(0) // ARCOUNT
        for (label in name.trimEnd('.').split('.')) {
            if (label.isEmpty() || label.length > 63) {
                throw IllegalArgumentException("bad dns label")
            }
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write((type shr 8) and 0xFF)
        out.write(type and 0xFF)
        out.write(0)
        out.write(1) // QCLASS = IN
        return out.toByteArray()
    }

    fun frameTcp(message: ByteArray): ByteArray {
        val out = ByteArray(message.size + 2)
        out[0] = ((message.size shr 8) and 0xFF).toByte()
        out[1] = (message.size and 0xFF).toByte()
        System.arraycopy(message, 0, out, 2, message.size)
        return out
    }

    /**
     * Returns the rdata of the first answer whose type matches (4 bytes
     * for A, 16 for AAAA), or null. Follows name labels and compression
     * pointers the minimum amount needed to walk the record list.
     */
    fun parseAddress(response: ByteArray, type: Int): ByteArray? {
        if (response.size < 12) return null
        if (response[3].toInt() and 0x0F != 0) return null // rcode != NOERROR
        val answers = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        var p = skipName(response, 12) ?: return null // question name
        p += 4 // qtype + qclass
        var remaining = answers
        while (remaining > 0) {
            p = skipName(response, p) ?: return null // answer name
            if (p + 10 > response.size) return null
            val rtype = ((response[p].toInt() and 0xFF) shl 8) or (response[p + 1].toInt() and 0xFF)
            p += 8 // type + class + ttl
            val rdlen = ((response[p].toInt() and 0xFF) shl 8) or (response[p + 1].toInt() and 0xFF)
            p += 2
            if (p + rdlen > response.size) return null
            if (rtype == type) {
                val expect = if (type == TYPE_A) 4 else 16
                if (rdlen == expect) return response.copyOfRange(p, p + rdlen)
            }
            p += rdlen
            remaining--
        }
        return null
    }

    private fun skipName(message: ByteArray, start: Int): Int? {
        var p = start
        while (true) {
            if (p >= message.size) return null
            val b = message[p].toInt() and 0xFF
            if (b and 0xC0 == 0xC0) return if (p + 2 <= message.size) p + 2 else null
            if (b == 0) return p + 1
            p += 1 + b
        }
    }
}

/**
 * Resolves names to real IP addresses through the encrypted SSH session
 * (direct-tcpip to a DNS server). Needed by the UDP bridge: when MapDNS
 * is on, hev sends UDP frames with domain-NAME destinations (verified in
 * hev-socks5-session-udp.c), and udpgw can only carry binary addresses -
 * so the bridge resolves the NAME here first, exactly like the server
 * already does for domain CONNECTs. With MapDNS off nothing calls this.
 */
internal class DnsOverSsh(
    private val connect: () -> DnsIo?,
    private val timeoutMs: Long = 5_000L,
    private val ttlMs: Long = 60_000L,
    private val onLog: (String) -> Unit
) {

    private class Cached(val address: ByteArray, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val ids = AtomicInteger(0)

    companion object {
        private val executor = Executors.newCachedThreadPool { r ->
            Thread(r, "udp-dns-resolver").apply { isDaemon = true }
        }
    }

    /**
     * Returns a 4-byte (A) or 16-byte (AAAA) address, or null on failure.
     * Never blocks longer than [timeoutMs]; a late answer still lands in
     * the cache so the next datagram is instant.
     */
    fun resolve(name: String): ByteArray? {
        val now = System.currentTimeMillis()
        cache[name]?.let { if (now < it.expiresAt) return it.address }
        val future = executor.submit(
            Callable { queryServer(name) }
        )
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            onLog("dns-over-ssh resolve '$name' timed out or failed: ${e.message}")
            null
        }
    }

    private fun queryServer(name: String): ByteArray? {
        for (type in intArrayOf(DnsCodec.TYPE_A, DnsCodec.TYPE_AAAA)) {
            val io = connect() ?: return null
            try {
                val query = DnsCodec.frameTcp(
                    DnsCodec.buildQuery(ids.incrementAndGet() and 0xFFFF, name, type)
                )
                io.output.write(query)
                io.output.flush()
                val lenBuf = ByteArray(2)
                readFully(io.input, lenBuf)
                val len = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                if (len < 12) return null
                val response = ByteArray(len)
                readFully(io.input, response)
                val address = DnsCodec.parseAddress(response, type) ?: continue
                if (cache.size > 128) cache.clear()
                cache[name] = Cached(address, System.currentTimeMillis() + ttlMs)
                return address
            } catch (e: IOException) {
                onLog("dns-over-ssh query '$name' failed: ${e.message}")
                return null
            } finally {
                try { io.close() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("eof")
            off += n
        }
    }
}
