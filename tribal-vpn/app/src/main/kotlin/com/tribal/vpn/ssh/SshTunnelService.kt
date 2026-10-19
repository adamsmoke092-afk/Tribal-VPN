package com.tribal.vpn.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import java.io.IOException
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
            val (destHost, destPort) = when (val n = Socks5Handshake.negotiate(client)) {
                is Negotiation.Refused -> {
                    refusals.refused(n.shape, n.detail)
                    try { client.close() } catch (_: Exception) {}
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
