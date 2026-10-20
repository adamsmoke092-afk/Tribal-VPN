package com.tribal.vpn.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens a real SSH session and a local SOCKS5 dynamic port forward
 * (the moral equivalent of `ssh -D 127.0.0.1:<port>`).
 *
 * IMPORTANT — protect() requirement:
 * Every socket this class opens (the SSH TCP connection itself) MUST be
 * passed through VpnService.protect() before connecting, or the SSH
 * traffic will get routed back into the tun interface and loop forever.
 * The `protectSocket` callback is required precisely so this class has
 * zero direct dependency on VpnService and stays unit-testable.
 *
 * NOTE ON DEPENDENCY: this file assumes `com.hierynomus:sshj` is added to
 * app/build.gradle.kts (currently commented out there — uncomment when
 * wiring this in). sshj does not ship a true SOCKS5-over-SSH forward
 * primitive out of the box the way OpenSSH's `-D` flag does; the
 * dynamic-forward implementation below is a minimal SOCKS5 handshake
 * bridging accepted local connections into sshj direct-tcpip channels.
 * This is the standard pattern used by sshj-based SOCKS proxies.
 */
class SshTunnelService(
    private val config: SshConfig,
    private val protectSocket: (Socket) -> Boolean,
    private val onLog: (plain: String?, technical: String?, isError: Boolean) -> Unit
) {

    private val ssh = SSHClient()
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    /** Port the SOCKS listener actually bound to (kernel-assigned). */
    val socksPort: Int get() = serverSocket?.localPort ?: config.socksBindPort

    /** Phase 5 probe result: udpgw reachable on the SSH host's loopback? */
    @Volatile
    var udpgwReachableViaSsh: Boolean? = null
        private set

    /** Phase 5 probe result: direct-route ports that accepted a connection. */
    @Volatile
    var directRouteOpenPorts: List<Int> = emptyList()
        private set
    private val acceptExecutor = Executors.newCachedThreadPool()

    // Rate-limited refusal logging: hev retries its UDP channel relentlessly
    // while UDP is unsupported, and every durable log line is fsync'd - one
    // line per refusal would churn the log and drown real events.
    private val refusals = Socks5Refusals { line -> onLog(null, line, false) }

    // UDP Custom (Phase 5): the shared udpgw client + its single transport,
    // armed in connect() only when the profile enables it. Drops use the
    // same rate-limited discipline as handshake refusals - never one
    // fsync'd log line per datagram.
    @Volatile
    private var udpgwClient: UdpGwClient? = null
    @Volatile
    private var udpgwTransport: UdpGwTransport? = null
    @Volatile
    private var dnsOverSsh: DnsOverSsh? = null
    private val udpDrops = Socks5Refusals { line -> onLog(null, line, false) }

    /**
     * Connects, authenticates, and starts accepting local SOCKS5 clients.
     * Throws on any failure — callers (TribalVpnService) are responsible
     * for catching and reflecting a real ERROR state, never a fake success.
     */
    @Throws(IOException::class)
    fun connect() {
        onLog("Connecting to ${config.host}…", "Opening SSH session to ${config.host}:${config.port}", false)

        // sshj has no connect(Socket): SocketClient creates its own socket via
        // a javax.net.SocketFactory, so the factory is the hook where protect()
        // runs on the socket BEFORE the TCP handshake completes.
        ssh.socketFactory = object : SocketFactory() {
            override fun createSocket(): Socket {
                val socket = Socket()
                // Android 9+ creates the real OS socket lazily: a fresh
                // unconnected Socket has no valid fd, so VpnService.protect()
                // (a setsockopt on the fd) returns false. bind(null) forces
                // fd creation (wildcard address, ephemeral port) while leaving
                // the socket unconnected, so protect() marks a real fd BEFORE
                // the TCP handshake — exactly the order VpnService requires.
                socket.bind(null)
                if (!protectSocket(socket)) {
                    try { socket.close() } catch (_: Exception) {}
                    throw IOException("VpnService.protect() failed — refusing to connect (would create a routing loop)")
                }
                return socket
            }

            // sshj's SocketClient.connect only ever calls the no-arg
            // createSocket() above; the host/port variants are abstract on
            // Android and must exist — fail loudly instead of faking them.
            override fun createSocket(host: String, port: Int): Socket =
                throw UnsupportedOperationException("Not used by sshj")
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                throw UnsupportedOperationException("Not used by sshj")
            override fun createSocket(host: InetAddress, port: Int): Socket =
                throw UnsupportedOperationException("Not used by sshj")
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                throw UnsupportedOperationException("Not used by sshj")
        }
        ssh.connectTimeout = config.connectTimeoutMs

        ssh.addHostKeyVerifier(PromiscuousVerifier()) // TODO: replace with known_hosts/TOFU pinning before production use

        // 20s handshake watchdog: connect + key exchange + authentication
        // must complete inside the window or the session is torn down, so a
        // wedged handshake (filtered port, dead NAT mapping) fails fast and
        // honestly instead of hanging CONNECTING forever. Disarmed the
        // moment authentication completes (or the attempt fails on its
        // own) - it never watches an established session.
        val handshakeDone = AtomicBoolean(false)
        val watchdog = Thread {
            try {
                Thread.sleep(HANDSHAKE_WATCHDOG_MS)
                if (!handshakeDone.get()) {
                    onLog(
                        null,
                        "SSH handshake watchdog: no authentication within " +
                            "${HANDSHAKE_WATCHDOG_MS / 1000}s - aborting the session",
                        true
                    )
                    try { ssh.disconnect() } catch (_: Exception) {}
                }
            } catch (_: InterruptedException) {
                // Disarmed before the deadline - expected path.
            }
        }
        watchdog.isDaemon = true
        watchdog.start()

        try {
            ssh.connect(config.host, config.port)
            ssh.connection.keepAlive.keepAliveInterval = config.serverAliveIntervalSeconds

            authenticate()
        } finally {
            handshakeDone.set(true)
            watchdog.interrupt()
        }
        onLog(null, "SSH authenticated as ${config.username}@${config.host}", false)

        // Phase 5 probe: is a udpgw server listening on the SSH host's
        // loopback? Rides the existing session (direct-tcpip channel), so
        // no extra socket and no routing concerns. Diagnostic only - it
        // never affects the connection state. Per the Phase 5 plan we
        // STOP after this: no udpgw client until the probe result is
        // reviewed.
        Thread {
            try {
                val channel = ssh.newDirectConnection("127.0.0.1", config.udpgwPort)
                udpgwReachableViaSsh = true
                try { channel.close() } catch (_: Exception) {}
                onLog(
                    null,
                    "udpgw probe (via SSH): 127.0.0.1:${config.udpgwPort} opened - " +
                        "a udpgw server is present",
                    false
                )
            } catch (t: Throwable) {
                udpgwReachableViaSsh = false
                onLog(
                    null,
                    "udpgw probe (via SSH): 127.0.0.1:${config.udpgwPort} failed - " +
                        "${t.javaClass.simpleName}: ${t.message}",
                    false
                )
            }
        }.apply { isDaemon = true }.start()

        // Phase 5 direct-route probe (the other half of the evidence
        // pair): HTTP Custom's UDP actually rides a DIRECT TCP socket
        // from the phone to VPS:port-from-range (the provider
        // iptables-redirects the range to udpgw). The probe above tested
        // the encrypted-session route; this one samples the saved range
        // directly, HTTP Custom style. A handful of ports only - any
        // port covered by the redirect answers the same way, and 65k
        // connects would hammer the provider. Informational only, it
        // never affects the connection state; the udpgw client decision
        // still waits for the probe evidence.
        Thread {
            val samples =
                directUdpProbePorts(config.udpPortRange, config.udpgwPort, config.port)
            if (samples.isEmpty()) {
                onLog(
                    null,
                    "udp direct-route probe: skipped - the saved range has " +
                        "no ports to sample (it only contains the SSH port)",
                    false
                )
                return@Thread
            }
            val opened = mutableListOf<Int>()
            for (port in samples) {
                val socket = Socket()
                try {
                    // Same fd rules as the SSH socket itself: bind(null)
                    // forces fd creation so protect() marks a real fd, and
                    // protect() keeps the probe out of the tun - it must
                    // reach the VPS the direct way, not loop through the
                    // tunnel it is measuring.
                    socket.bind(null)
                    if (!protectSocket(socket)) {
                        throw IOException(
                            "VpnService.protect() failed - refusing to " +
                                "probe $port (would loop into the tunnel)"
                        )
                    }
                    socket.connect(
                        InetSocketAddress(config.host, port),
                        DIRECT_PROBE_TIMEOUT_MS
                    )
                    opened.add(port)
                    onLog(
                        null,
                        "udp direct-route probe: ${config.host}:$port opened - " +
                            "the redirect path answers (HTTP Custom style)",
                        false
                    )
                } catch (t: Throwable) {
                    onLog(
                        null,
                        "udp direct-route probe: ${config.host}:$port failed - " +
                            "${t.javaClass.simpleName}: ${t.message}",
                        false
                    )
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }
            }
            directRouteOpenPorts = opened
        }.apply { isDaemon = true }.start()

        // UDP Custom: arm the shared udpgw client BEFORE the SOCKS listener
        // accepts traffic, so the first FWD_UDP handshake finds it alive.
        if (config.enableUdp) {
            armUdpGw()
        }

        startSocksListener()
        // Log the port the kernel actually assigned, not the 0 sentinel.
        onLog(null, "SOCKS5 proxy opened on 127.0.0.1:$socksPort", false)
    }

    private fun authenticate() {
        when {
            config.privateKeyAlias != null -> {
                // Phase 3 note: sshj needs an actual KeyProvider here. Android Keystore
                // keys aren't directly compatible with sshj's KeyProvider interface,
                // so this requires a small adapter (sign challenges via Keystore,
                // wrap as a sshj PKCS to satisfy KeyProvider). Left as an explicit
                // TODO rather than faking success.
                throw UnsupportedOperationException(
                    "SSH key auth requires a Keystore-backed sshj KeyProvider adapter — not yet implemented"
                )
            }
            config.password != null -> {
                ssh.authPassword(config.username, config.password)
            }
            else -> throw IllegalArgumentException("No password or key provided for authentication")
        }
    }

    /**
     * Minimal local SOCKS5 server: accepts loopback connections (from the
     * tun2socks / hev-socks5-tunnel side, Phase 4) and bridges each one into
     * a real sshj direct-tcpip channel to the requested destination.
     */
    private fun startSocksListener() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        // Port 0 = let the kernel assign a free ephemeral port (no collision
        // with another instance's listener). The bound port is read back
        // below and exposed via socksPort so the relay config points at the
        // real listener.
        socket.bind(InetSocketAddress("127.0.0.1", config.socksBindPort))
        serverSocket = socket
        running.set(true)

        acceptExecutor.submit {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    acceptExecutor.submit { handleSocksClient(client) }
                } catch (e: IOException) {
                    if (running.get()) {
                        onLog(null, "SOCKS accept loop error: ${e.message}", true)
                    }
                    // If running=false this is just the socket closing during shutdown — expected.
                }
            }
        }
    }

    private fun handleSocksClient(client: Socket) {
        try {
            val (destHost, destPort) = when (
                val n = Socks5Handshake.negotiate(
                    client,
                    acceptFwdUdp = udpgwClient?.isAlive == true
                )
            ) {
                is Negotiation.Refused -> {
                    refusals.refused(n.shape, n.detail)
                    try { client.close() } catch (_: Exception) {}
                    return
                }
                is Negotiation.FwdUdp -> {
                    startFwdUdpRelay(client)
                    return
                }
                is Negotiation.Connect -> n.host to n.port
            }

            // Fake-IP leak detection (MapDNS): a CONNECT targeting an
            // address inside the configured fake-IP pool means hev's
            // reverse mapping did not fire - the connection would die at
            // the SSH server. Flagged rate-limited like refusals; the
            // connect is still attempted so the failure stays honest.
            if (config.mapDnsNetmask != 0) {
                val targetIp = ipv4ToIntOrNull(destHost)
                if (targetIp != null &&
                    (targetIp and config.mapDnsNetmask) == config.mapDnsNetwork
                ) {
                    refusals.refused(
                        "fake-ip leak",
                        "CONNECT target $destHost is inside the mapdns pool - reverse mapping did not fire"
                    )
                }
            }

            // One durable log line per CONNECT: where the relay wants to go,
            // then whether the SSH direct-tcpip channel actually opened.
            onLog(null, "SOCKS5 CONNECT $destHost:$destPort", false)
            val channel: DirectConnection = try {
                ssh.newDirectConnection(destHost, destPort)
            } catch (e: Exception) {
                onLog(null, "SOCKS5 $destHost:$destPort -> direct-tcpip FAILED: ${e.message}", true)
                try { client.close() } catch (_: Exception) {}
                return
            }
            onLog(null, "SOCKS5 $destHost:$destPort -> direct-tcpip open", false)
            Socks5Handshake.pipe(client, channel)
        } catch (e: Exception) {
            onLog(null, "SOCKS client relay error: ${e.message}", true)
            try { client.close() } catch (_: Exception) {}
        }
    }

    // --- UDP Custom: hev FWD_UDP <-> udpgw bridge ---

    /**
     * Arms the single udpgw transport + client. Route 1 is the SSH session
     * itself (direct-tcpip to the udpgw port on the VPS loopback) - the
     * carrier sees nothing beyond the SSH connection it already sees.
     * Route 2 is HTTP Custom's mechanism: a protected direct socket to the
     * VPS on a port from the redirect range - UNENCRYPTED by design and
     * logged loudly as such. Both failing means UDP honestly does not
     * flow; TCP is unaffected either way.
     */
    private fun armUdpGw() {
        val transport: UdpGwTransport = try {
            val channel = ssh.newDirectConnection("127.0.0.1", config.udpgwPort)
            onLog(
                null,
                "udp transport: via SSH session to 127.0.0.1:${config.udpgwPort} (encrypted)",
                false
            )
            UdpGwTransport(
                input = channel.inputStream,
                output = channel.outputStream,
                close = { try { channel.close() } catch (_: Exception) {} }
            )
        } catch (t: Throwable) {
            try {
                val socket = Socket()
                socket.bind(null)
                if (!protectSocket(socket)) {
                    socket.close()
                    throw IOException("VpnService.protect() failed")
                }
                socket.connect(InetSocketAddress(config.host, config.udpgwPort), 10_000)
                socket.tcpNoDelay = true
                onLog(
                    null,
                    "udp transport: direct to ${config.host}:${config.udpgwPort} - " +
                        "UNENCRYPTED, HTTP Custom style",
                    false
                )
                UdpGwTransport(
                    input = socket.getInputStream(),
                    output = socket.getOutputStream(),
                    close = { try { socket.close() } catch (_: Exception) {} }
                )
            } catch (t2: Throwable) {
                onLog(
                    null,
                    "udp transport unavailable: SSH route failed " +
                        "(${t.javaClass.simpleName}: ${t.message}), direct route failed " +
                        "(${t2.javaClass.simpleName}: ${t2.message}) - UDP will not flow, " +
                        "TCP is unaffected",
                    false
                )
                return
            }
        }
        udpgwTransport = transport
        val client = UdpGwClient(transport.input, transport.output) { line ->
            onLog(null, line, false)
        }
        udpgwClient = client
        client.start()
        dnsOverSsh = DnsOverSsh(
            connect = {
                try {
                    val ch = ssh.newDirectConnection(config.dnsAddress, 53)
                    DnsIo(ch.inputStream, ch.outputStream) {
                        try { ch.close() } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                    null
                }
            },
            onLog = { line -> onLog(null, line, false) }
        )
        onLog(null, "udp udpgw client armed - hev FWD_UDP (UDP-in-TCP) is accepted", false)
    }

    /**
     * One hev FWD_UDP connection = one UDP flow set. hev frames each
     * datagram as [datlen:u16 BE][hdrlen:u8][addr][payload] over this
     * socket (verified in hev-socks5-core hev_socks5_udp_sendmmsg_tcp);
     * the bridge translates every frame into a udpgw datagram on the
     * shared transport and routes responses back the same way. NAME
     * destinations (MapDNS reverse-mapped domains) are resolved through
     * the SSH session first - udpgw carries binary addresses only, and
     * hev ignores our response address for these flows (verified in
     * hev-socks5-session-udp.c fwd_b), so the resolved IP is enough.
     */
    private fun startFwdUdpRelay(client: Socket) {
        val udpgw = udpgwClient
        if (udpgw == null || !udpgw.isAlive) {
            udpDrops.refused("udp not armed", "FWD_UDP arrived while the udpgw client is down")
            try { client.close() } catch (_: Exception) {}
            return
        }
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val writeLock = Any()
        val owner = Any()

        udpgw.register(owner) { dest, payload ->
            // Sink runs on the udpgw reader thread; one lock per hev
            // connection keeps frame writes whole and ordered.
            synchronized(writeLock) {
                try {
                    output.write(hevUdpFrame(dest, payload))
                    output.flush()
                } catch (_: Exception) {
                    // hev hung up; its relay loop notices via EOF next.
                }
            }
        }

        try {
            while (true) {
                val datlen = input.readUnsignedShort()
                val hdrlen = input.readUnsignedByte()
                if (hdrlen < 5 || hdrlen > 266) {
                    throw IOException("bad hev frame header hdrlen=$hdrlen")
                }
                if (datlen > 4096) {
                    throw IOException("bad hev frame length datlen=$datlen")
                }
                val addr = ByteArray(hdrlen - 3)
                input.readFully(addr)
                val payload = ByteArray(datlen)
                input.readFully(payload)
                when (val dest = parseHevAddr(addr)) {
                    is HevAddr.Ip -> {
                        // Fake-IP leak check: an IPv4 dest inside the mapdns
                        // pool means the reverse mapping did not fire.
                        if (!dest.isIpv6 && config.mapDnsNetmask != 0) {
                            val ip = ipv4ToIntOrNull(dest.hostAddress)
                            if (ip != null &&
                                (ip and config.mapDnsNetmask) == config.mapDnsNetwork
                            ) {
                                udpDrops.refused(
                                    "udp fake-ip leak",
                                    "UDP frame target ${dest.hostAddress}:${dest.port} is inside the mapdns pool"
                                )
                                continue
                            }
                        }
                        udpgw.submit(owner, UdpDest(dest.host, dest.port, dest.isIpv6), payload)
                    }
                    is HevAddr.Name -> {
                        val address = dnsOverSsh?.resolve(dest.name)
                        if (address == null) {
                            udpDrops.refused("udp resolve failed", "${dest.name}:${dest.port}")
                            continue
                        }
                        udpgw.submit(owner, UdpDest(address, dest.port, address.size == 16), payload)
                    }
                    null -> udpDrops.refused("udp bad frame addr", "hdrlen=$hdrlen")
                }
            }
        } catch (_: IOException) {
            // hev hung up or the tunnel is closing - normal end of flow.
        } finally {
            udpgw.unregister(owner)
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** Parsed hev SOCKS5 address: binary IP or reverse-mapped domain name. */
    private sealed interface HevAddr {
        data class Ip(val host: ByteArray, val port: Int, val isIpv6: Boolean) : HevAddr {
            val hostAddress: String
                get() = try {
                    InetAddress.getByAddress(host).hostAddress
                } catch (_: Exception) {
                    "?"
                }
        }
        data class Name(val name: String, val port: Int) : HevAddr
    }

    /** hev address bytes: [atype][host][port:u16 BE] (hev-socks5-proto.h). */
    private fun parseHevAddr(addr: ByteArray): HevAddr? {
        if (addr.isEmpty()) return null
        return when (addr[0].toInt()) {
            0x01 -> if (addr.size == 7) {
                HevAddr.Ip(
                    host = addr.copyOfRange(1, 5),
                    port = ((addr[5].toInt() and 0xFF) shl 8) or (addr[6].toInt() and 0xFF),
                    isIpv6 = false
                )
            } else null
            0x04 -> if (addr.size == 19) {
                HevAddr.Ip(
                    host = addr.copyOfRange(1, 17),
                    port = ((addr[17].toInt() and 0xFF) shl 8) or (addr[18].toInt() and 0xFF),
                    isIpv6 = true
                )
            } else null
            0x03 -> {
                if (addr.size < 4) return null
                val len = addr[1].toInt() and 0xFF
                if (2 + len + 2 > addr.size) return null
                HevAddr.Name(
                    name = String(addr, 2, len, Charsets.US_ASCII),
                    port = ((addr[2 + len].toInt() and 0xFF) shl 8) or
                        (addr[3 + len].toInt() and 0xFF)
                )
            }
            else -> null
        }
    }

    /**
     * Response frame back to hev, the mirror of hev's own
     * hev_socks5_udp_sendmmsg_tcp: [datlen:u16 BE][hdrlen:u8][atype]
     * [host][port:u16 BE][payload] with hdrlen = 3 + addr bytes.
     */
    private fun hevUdpFrame(dest: UdpDest, payload: ByteArray): ByteArray {
        val addrLen = if (dest.isIpv6) 19 else 7
        val out = ByteArray(3 + addrLen + payload.size)
        out[0] = ((payload.size shr 8) and 0xFF).toByte()
        out[1] = (payload.size and 0xFF).toByte()
        out[2] = (3 + addrLen).toByte()
        var p = 3
        out[p] = (if (dest.isIpv6) 0x04 else 0x01).toByte()
        p++
        System.arraycopy(dest.host, 0, out, p, if (dest.isIpv6) 16 else 4)
        p += if (dest.isIpv6) 16 else 4
        out[p] = ((dest.port shr 8) and 0xFF).toByte()
        out[p + 1] = (dest.port and 0xFF).toByte()
        System.arraycopy(payload, 0, out, p + 2, payload.size)
        return out
    }

    /**
     * Textual dotted-quad IPv4 parse only - never resolves DNS. Used to
     * detect fake-IP leaks against the configured mapdns pool (the SSH
     * layer keeps no dependency on the data model's parsers).
     */
    private fun ipv4ToIntOrNull(text: String): Int? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value.toInt()
    }

    fun disconnect() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        udpgwClient?.stop()
        udpgwClient = null
        try { udpgwTransport?.close?.invoke() } catch (_: Exception) {}
        udpgwTransport = null
        dnsOverSsh = null
        try {
            if (ssh.isConnected) ssh.disconnect()
        } catch (_: Exception) {
        }
        acceptExecutor.shutdownNow()
        onLog("Disconnected", "SSH session and SOCKS listener closed", false)
    }

    val isConnected: Boolean
        get() = ssh.isConnected && ssh.isAuthenticated

    companion object {
        private const val HANDSHAKE_WATCHDOG_MS = 20_000L
        private const val DIRECT_PROBE_TIMEOUT_MS = 5_000
    }
}

/**
 * The single byte stream every UDP flow shares: either an SSH direct-tcpip
 * channel (encrypted, carrier-visible traffic unchanged) or a protected
 * direct socket (HTTP Custom's mechanism, unencrypted). Both reduce to
 * plain streams for UdpGwClient.
 */
internal class UdpGwTransport(
    val input: InputStream,
    val output: OutputStream,
    val close: () -> Unit
)

/**
 * Ports sampled for the direct-route probe: the udpgw port (when it falls
 * inside the range) plus three spread across the saved range. A redirect
 * rule covers whole ranges, so any covered port answers identically - a
 * handful proves or disproves the path without 65k connects. The SSH
 * port is excluded: connecting to it would reach sshd, not the redirect,
 * and would read as a false positive.
 */
internal fun directUdpProbePorts(
    range: IntRange,
    udpgwPort: Int,
    sshPort: Int
): List<Int> {
    val span = range.last - range.first
    val samples = linkedSetOf<Int>()
    if (udpgwPort in range && udpgwPort != sshPort) samples.add(udpgwPort)
    for (candidate in listOf(range.first, range.first + span / 2, range.last)) {
        if (candidate != sshPort) samples.add(candidate)
    }
    return samples.toList()
}
