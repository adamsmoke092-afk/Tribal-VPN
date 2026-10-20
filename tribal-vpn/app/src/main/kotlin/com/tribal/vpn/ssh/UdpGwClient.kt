package com.tribal.vpn.ssh

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** badvpn udpgw client flags (protocol/udpgw_proto.h). */
internal object UdpGwFlags {
    const val KEEPALIVE = 1
    const val REBIND = 2
    const val DNS = 4
    const val IPV6 = 8
}

/**
 * A UDP flow destination as carried on the udpgw wire: a raw 4- or 16-byte
 * address plus port. Host bytes are in presentation order (a.b.c.d); the
 * wire encoding is handled by [UdpGwClient] and mirrors badvpn exactly.
 */
internal class UdpDest(val host: ByteArray, val port: Int, val isIpv6: Boolean) {

    init {
        require(host.size == if (isIpv6) 16 else 4) { "bad address length" }
    }

    /** Content-based equality key; the flow table is keyed per (owner, dest). */
    val key: String
        get() = (if (isIpv6) "v6:" else "v4:") +
            host.joinToString("") { "%02x".format(it) } + ":" + port
}

/**
 * badvpn-udpgw client - the wire protocol badvpn's tun2socks speaks to a
 * udpgw server. Multiplexes any number of UDP flows over ONE byte stream
 * (an SSH direct-tcpip channel or a plain socket), so every UDP flow in
 * the app shares the single transport.
 *
 * Wire format, verified against badvpn master (protocol/udpgw_proto.h,
 * protocol/packetproto.h, udpgw_client/UdpGwClient.c):
 *  - Every message rides PacketProto framing: [len:u16 LE][message].
 *  - Message = [flags:u8][conid:u16 LE][addr][payload], all values
 *    little-endian. IPv4 addr = 4 octets REVERSED (badvpn serializes a
 *    host-order uint32 little-endian) + u16 LE port; IPv6 (FLAG_IPV6) =
 *    16 raw bytes + u16 LE port.
 *  - The FIRST datagram of a flow carries FLAG_REBIND, mirroring
 *    UdpGwClient.c connection_first_job_handler; reuse of a full table
 *    rebinds the least-recently-used conid, mirroring reuse_connection().
 *  - Keepalive = bare header (FLAG_KEEPALIVE, conid 0); the server never
 *    replies but disconnects silent clients (20s default), so the
 *    interval must stay below that.
 */
internal class UdpGwClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val maxConnections: Int = 256,
    private val keepaliveMs: Long = 10_000L,
    private val onLog: (String) -> Unit
) {

    private data class FlowKey(val owner: Any, val destKey: String)

    private class Entry(val owner: Any, val dest: UdpDest)

    private val lock = Any()
    // accessOrder=true gives least-recently-used iteration for the
    // rebind-on-full path, mirroring badvpn's connection reuse.
    private val flows = LinkedHashMap<FlowKey, Int>(16, 0.75f, true)
    private val byConid = HashMap<Int, Entry>()
    private val sinks = ConcurrentHashMap<Any, (UdpDest, ByteArray) -> Unit>()
    private val nextConid = AtomicInteger(0)
    private val alive = AtomicBoolean(false)
    private val lastSendAt = AtomicLong(0)

    val isAlive: Boolean get() = alive.get()

    /** One sink per downstream owner (one hev FWD_UDP connection in the bridge). */
    fun register(owner: Any, sink: (UdpDest, ByteArray) -> Unit) {
        sinks[owner] = sink
    }

    fun unregister(owner: Any) {
        sinks.remove(owner)
        synchronized(lock) {
            flows.entries.removeAll { it.key.owner === owner }
            byConid.values.removeAll { it.owner === owner }
        }
    }

    /**
     * Sends one datagram on its flow. Returns false when the client is
     * dead or the write fails - the caller drops the datagram honestly
     * instead of pretending it went out.
     */
    fun submit(owner: Any, dest: UdpDest, payload: ByteArray): Boolean {
        if (!alive.get()) return false
        val key = FlowKey(owner, dest.key)
        var rebind = false
        val conid: Int
        synchronized(lock) {
            val existing = flows[key]
            if (existing != null) {
                conid = existing
            } else if (byConid.size < maxConnections) {
                conid = allocConidLocked()
                flows[key] = conid
                byConid[conid] = Entry(owner, dest)
                rebind = true
            } else {
                // Table full: rebind the least-recently-used conid to the
                // new flow, exactly like badvpn's reuse_connection().
                val eldest = flows.entries.iterator().next()
                conid = eldest.value
                flows.remove(eldest.key)
                flows[key] = conid
                byConid[conid] = Entry(owner, dest)
                rebind = true
            }
        }
        val flags = if (rebind) UdpGwFlags.REBIND else 0
        return writeFramed(buildMessage(dest, payload, conid, flags))
    }

    fun start() {
        if (!alive.compareAndSet(false, true)) return
        lastSendAt.set(System.currentTimeMillis())
        Thread(::readerLoop, "udp-udpgw-reader").apply { isDaemon = true }.start()
        Thread(::keepaliveLoop, "udp-udpgw-keepalive").apply { isDaemon = true }.start()
    }

    /** Marks the client dead. The transport (streams) stays owned by the caller. */
    fun stop() {
        if (!alive.compareAndSet(true, false)) return
        onLog("udp udpgw client stopped")
    }

    // --- wire codec (internal for byte-exact JVM tests) ---

    internal fun buildMessage(dest: UdpDest, payload: ByteArray, conid: Int, flags: Int): ByteArray {
        val f = flags or (if (dest.isIpv6) UdpGwFlags.IPV6 else 0)
        val out = ByteArray(3 + if (dest.isIpv6) 18 else 6 + payload.size)
        out[0] = f.toByte()
        out[1] = (conid and 0xFF).toByte()
        out[2] = ((conid shr 8) and 0xFF).toByte()
        var p = 3
        if (dest.isIpv6) {
            System.arraycopy(dest.host, 0, out, p, 16)
            p += 16
        } else {
            // Little-endian uint32 of the host-order value: octets reversed.
            for (i in 0 until 4) out[p + i] = dest.host[3 - i]
            p += 4
        }
        out[p] = (dest.port and 0xFF).toByte()
        out[p + 1] = ((dest.port shr 8) and 0xFF).toByte()
        p += 2
        System.arraycopy(payload, 0, out, p, payload.size)
        return out
    }

    internal fun buildKeepalive(): ByteArray = byteArrayOf(UdpGwFlags.KEEPALIVE.toByte(), 0, 0)

    internal fun frame(message: ByteArray): ByteArray {
        val out = ByteArray(message.size + 2)
        out[0] = (message.size and 0xFF).toByte()
        out[1] = ((message.size shr 8) and 0xFF).toByte()
        System.arraycopy(message, 0, out, 2, message.size)
        return out
    }

    // --- internals ---

    private fun allocConidLocked(): Int {
        while (true) {
            val id = nextConid.get()
            val next = if (id >= maxConnections - 1) 0 else id + 1
            nextConid.set(next)
            if (!byConid.containsKey(id)) return id
        }
    }

    private fun writeFramed(message: ByteArray): Boolean {
        if (!alive.get() || message.size > 0xFFFF) return false
        return try {
            synchronized(output) {
                output.write(frame(message))
                output.flush()
            }
            lastSendAt.set(System.currentTimeMillis())
            true
        } catch (e: IOException) {
            onLog("udp udpgw write failed: ${e.message}")
            stop()
            false
        }
    }

    private fun readerLoop() {
        val len = ByteArray(2)
        try {
            while (alive.get()) {
                readFully(len, 2)
                val size = (len[0].toInt() and 0xFF) or ((len[1].toInt() and 0xFF) shl 8)
                if (size < 3) throw IOException("bad packetproto length $size")
                val message = ByteArray(size)
                readFully(message, size)
                handleMessage(message)
            }
        } catch (_: IOException) {
            // transport closed - normal shutdown path
        } catch (t: Throwable) {
            onLog("udp udpgw reader error: ${t.message}")
        } finally {
            stop()
        }
    }

    private fun readFully(buf: ByteArray, count: Int) {
        var off = 0
        while (off < count) {
            val n = input.read(buf, off, count - off)
            if (n < 0) throw IOException("eof")
            off += n
        }
    }

    private fun handleMessage(message: ByteArray) {
        if (message.size < 3) return
        val flags = message[0].toInt() and 0xFF
        val conid = (message[1].toInt() and 0xFF) or ((message[2].toInt() and 0xFF) shl 8)
        if (flags and UdpGwFlags.KEEPALIVE != 0) return
        val isIpv6 = flags and UdpGwFlags.IPV6 != 0
        val addrLen = if (isIpv6) 18 else 6
        if (message.size < 3 + addrLen) return
        val host = ByteArray(if (isIpv6) 16 else 4)
        var p = 3
        if (isIpv6) {
            System.arraycopy(message, p, host, 0, 16)
            p += 16
        } else {
            for (i in 0 until 4) host[i] = message[p + 3 - i]
            p += 4
        }
        val port = (message[p].toInt() and 0xFF) or ((message[p + 1].toInt() and 0xFF) shl 8)
        val payload = message.copyOfRange(p + 2, message.size)
        val entry = synchronized(lock) { byConid[conid] } ?: return
        val sink = sinks[entry.owner] ?: return
        sink(UdpDest(host, port, isIpv6), payload)
    }

    private fun keepaliveLoop() {
        while (alive.get()) {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return
            }
            if (alive.get() && System.currentTimeMillis() - lastSendAt.get() >= keepaliveMs) {
                if (!writeFramed(buildKeepalive())) return
            }
        }
    }
}
