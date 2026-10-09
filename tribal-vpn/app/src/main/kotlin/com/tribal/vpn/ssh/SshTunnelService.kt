package com.tribal.vpn.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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
    private val acceptExecutor = Executors.newCachedThreadPool()

    /**
     * Connects, authenticates, and starts accepting local SOCKS5 clients.
     * Throws on any failure — callers (TribalVpnService) are responsible
     * for catching and reflecting a real ERROR state, never a fake success.
     */
    @Throws(IOException::class)
    fun connect() {
        onLog("Connecting to ${config.host}…", "Opening SSH session to ${config.host}:${config.port}", false)

        // sshj lets us supply our own Socket, which is how protect() gets applied
        // before the TCP handshake completes.
        val rawSocket = Socket()
        val protected = protectSocket(rawSocket)
        if (!protected) {
            throw IOException("VpnService.protect() failed — refusing to connect (would create a routing loop)")
        }
        rawSocket.connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMs)

        ssh.addHostKeyVerifier(PromiscuousVerifier()) // TODO: replace with known_hosts/TOFU pinning before production use
        ssh.connectVia(rawSocket)
        ssh.connection.keepAlive.keepAliveInterval = config.serverAliveIntervalSeconds

        authenticate()
        onLog(null, "SSH authenticated as ${config.username}@${config.host}", false)

        startSocksListener()
        onLog(null, "SOCKS5 proxy opened on 127.0.0.1:${config.socksBindPort}", false)
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
            val (destHost, destPort) = Socks5Handshake.negotiate(client)
                ?: return client.close().also {
                    onLog(null, "SOCKS5 handshake failed for a client", true)
                }

            val channel: DirectConnection = ssh.newDirectConnection(destHost, destPort)
            Socks5Handshake.pipe(client, channel)
        } catch (e: Exception) {
            onLog(null, "SOCKS client relay error: ${e.message}", true)
            try { client.close() } catch (_: Exception) {}
        }
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
}
