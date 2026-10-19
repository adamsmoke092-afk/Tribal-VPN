package com.tribal.vpn.ssh

import net.schmizz.sshj.connection.channel.direct.DirectConnection
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket

/**
 * Minimal SOCKS5 server-side handshake (RFC 1928), just enough to support
 * the CONNECT command with no authentication — sufficient for a loopback
 * proxy that only the local hev-socks5-tunnel process talks to (Phase 4).
 *
 * This is intentionally NOT a general-purpose SOCKS5 server: no BIND,
 * no UDP ASSOCIATE (UDP goes through badvpn-udpgw separately in Phase 5),
 * no username/password auth (not needed for a 127.0.0.1-only listener).
 *
 * Refusals return Negotiation.Refused with a stable "shape" key (e.g.
 * "cmd=0x05") plus a human detail; the caller rate-limits them so a busy
 * relay never writes one fsync'd log line per refused handshake. Command
 * labels come from hev-socks5-core's protocol constants, verified at the
 * pinned submodule: CONNECT=1, UDP_ASC=3, FWD_UDP=5 (hev's UDP-in-TCP
 * extension - what hev sends today while UDP is unsupported here).
 */
sealed interface Negotiation {
    data class Connect(val host: String, val port: Int) : Negotiation
    data class Refused(val shape: String, val detail: String) : Negotiation
}

object Socks5Handshake {

    private const val VERSION = 0x05
    private const val CMD_CONNECT = 0x01
    private const val ATYP_IPV4 = 0x01
    private const val ATYP_DOMAIN = 0x03
    private const val ATYP_IPV6 = 0x04
    private const val NO_AUTH = 0x00
    private const val REPLY_SUCCESS = 0x00
    private const val REPLY_GENERAL_FAILURE = 0x01
    private const val REPLY_CMD_NOT_SUPPORTED = 0x07

    /**
     * Performs the greeting + request phase. The byte stream is exactly
     * RFC 1928; only the RETURN type is richer than a nullable pair, so the
     * caller can log what was actually refused instead of a bare "failed".
     * On Refused the caller should close the socket — never guess or fake
     * a destination.
     */
    @Throws(IOException::class)
    fun negotiate(client: Socket): Negotiation {
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()

        // --- Greeting ---
        val ver = input.readUnsignedByte()
        if (ver != VERSION) {
            return Negotiation.Refused("bad version", "version=0x%02X".format(ver))
        }

        val nMethods = input.readUnsignedByte()
        val methods = ByteArray(nMethods)
        input.readFully(methods)

        // We only support NO_AUTH for this loopback-only listener.
        if (methods.none { it.toInt() == NO_AUTH }) {
            output.write(byteArrayOf(VERSION.toByte(), 0xFF.toByte())) // no acceptable methods
            return Negotiation.Refused(
                "no NO_AUTH in greeting",
                "methods=" + methods.joinToString(",") { "0x%02X".format(it) }
            )
        }
        output.write(byteArrayOf(VERSION.toByte(), NO_AUTH.toByte()))

        // --- Request ---
        val reqVer = input.readUnsignedByte()
        val cmd = input.readUnsignedByte()
        input.readUnsignedByte() // reserved byte
        val atyp = input.readUnsignedByte()

        if (reqVer != VERSION) {
            writeReply(output, REPLY_CMD_NOT_SUPPORTED)
            return Negotiation.Refused("bad request version", "version=0x%02X".format(reqVer))
        }
        if (cmd != CMD_CONNECT) {
            writeReply(output, REPLY_CMD_NOT_SUPPORTED)
            return Negotiation.Refused("cmd=0x%02X".format(cmd), commandName(cmd))
        }

        val destHost: String = when (atyp) {
            ATYP_IPV4 -> {
                val addr = ByteArray(4)
                input.readFully(addr)
                InetAddress.getByAddress(addr).hostAddress
            }
            ATYP_DOMAIN -> {
                val len = input.readUnsignedByte()
                val domain = ByteArray(len)
                input.readFully(domain)
                String(domain, Charsets.US_ASCII)
            }
            ATYP_IPV6 -> {
                val addr = ByteArray(16)
                input.readFully(addr)
                InetAddress.getByAddress(addr).hostAddress
            }
            else -> {
                writeReply(output, REPLY_GENERAL_FAILURE)
                return Negotiation.Refused("atyp=0x%02X".format(atyp), "unsupported address type")
            }
        }
        val destPort = input.readUnsignedShort()

        writeReply(output, REPLY_SUCCESS)
        return Negotiation.Connect(destHost, destPort)
    }

    /** Command names for refusal logs (hev-socks5-core protocol values). */
    private fun commandName(cmd: Int): String = when (cmd) {
        0x01 -> "CONNECT"
        0x02 -> "BIND"
        0x03 -> "UDP ASSOCIATE (hev UDP-in-UDP)"
        0x05 -> "hev FWD_UDP (UDP-in-TCP)"
        else -> "unknown command"
    }

    private fun writeReply(output: OutputStream, replyCode: Int) {
        // BND.ADDR/BND.PORT are zeroed — acceptable for a CONNECT-only relay
        // where the caller doesn't depend on the bound address value.
        output.write(
            byteArrayOf(
                VERSION.toByte(), replyCode.toByte(), 0x00,
                ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0
            )
        )
    }

    /**
     * Bridges bytes bidirectionally between the local SOCKS client socket and
     * the real sshj direct-tcpip channel until either side closes.
     */
    fun pipe(client: Socket, channel: DirectConnection) {
        val clientIn = client.getInputStream()
        val clientOut = client.getOutputStream()
        val channelIn = channel.inputStream
        val channelOut = channel.outputStream

        val t1 = Thread { copyStream(clientIn, channelOut) }
        val t2 = Thread { copyStream(channelIn, clientOut) }
        t1.isDaemon = true
        t2.isDaemon = true
        t1.start()
        t2.start()
        t1.join()
        t2.join()

        try { client.close() } catch (_: Exception) {}
        try { channel.close() } catch (_: Exception) {}
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // Expected when either side closes the connection.
        }
    }
}
