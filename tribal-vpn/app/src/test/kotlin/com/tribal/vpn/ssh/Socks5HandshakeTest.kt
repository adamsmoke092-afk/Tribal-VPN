package com.tribal.vpn.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Exercises Socks5Handshake.negotiate() against a REAL loopback socket pair
 * (no mocking of Socket/streams) so the byte-level RFC 1928 framing is
 * actually verified, not just assumed correct.
 */
class Socks5HandshakeTest {

    private val executor = Executors.newCachedThreadPool()

    /** Spins up a loopback ServerSocket and returns (serverSideSocket, clientSideSocket). */
    private fun loopbackPair(): Pair<Socket, Socket> {
        val server = ServerSocket(0)
        val clientFuture = executor.submit<Socket> { Socket("127.0.0.1", server.localPort) }
        val serverSide = server.accept()
        val clientSide = clientFuture.get(5, TimeUnit.SECONDS)
        server.close()
        return serverSide to clientSide
    }

    @Test
    fun `negotiate parses IPv4 CONNECT request correctly`() {
        val (serverSide, clientSide) = loopbackPair()

        val resultFuture = executor.submit<Pair<String, Int>?> {
            Socks5Handshake.negotiate(serverSide)
        }

        // Client writes: greeting (VER=5, NMETHODS=1, [NO_AUTH])
        clientSide.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        clientSide.getOutputStream().flush()

        // Read server's method selection reply (VER=5, METHOD=0x00)
        val methodReply = ByteArray(2)
        clientSide.getInputStream().read(methodReply)
        assertEquals(0x05, methodReply[0].toInt())
        assertEquals(0x00, methodReply[1].toInt())

        // Client writes CONNECT request for 93.184.216.34:443 (example.com's old IP, just bytes)
        clientSide.getOutputStream().write(
            byteArrayOf(
                0x05, 0x01, 0x00, 0x01, // VER, CMD=CONNECT, RSV, ATYP=IPv4
                93.toByte(), 184.toByte(), 216.toByte(), 34.toByte(),
                0x01, 0xBB.toByte() // port 443 big-endian
            )
        )
        clientSide.getOutputStream().flush()

        val result = resultFuture.get(5, TimeUnit.SECONDS)
        assertEquals("93.184.216.34", result?.first)
        assertEquals(443, result?.second)

        clientSide.close()
        serverSide.close()
    }

    @Test
    fun `negotiate parses domain name CONNECT request correctly`() {
        val (serverSide, clientSide) = loopbackPair()

        val resultFuture = executor.submit<Pair<String, Int>?> {
            Socks5Handshake.negotiate(serverSide)
        }

        clientSide.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        clientSide.getOutputStream().flush()
        clientSide.getInputStream().read(ByteArray(2))

        val domain = "vps.example.com"
        val domainBytes = domain.toByteArray(Charsets.US_ASCII)
        val request = ByteArray(5 + domainBytes.size + 2)
        request[0] = 0x05; request[1] = 0x01; request[2] = 0x00; request[3] = 0x03
        request[4] = domainBytes.size.toByte()
        domainBytes.copyInto(request, 5)
        request[5 + domainBytes.size] = 0x00
        request[5 + domainBytes.size + 1] = 0x16 // port 22

        clientSide.getOutputStream().write(request)
        clientSide.getOutputStream().flush()

        val result = resultFuture.get(5, TimeUnit.SECONDS)
        assertEquals(domain, result?.first)
        assertEquals(22, result?.second)

        clientSide.close()
        serverSide.close()
    }

    @Test
    fun `negotiate rejects wrong SOCKS version`() {
        val (serverSide, clientSide) = loopbackPair()

        val resultFuture = executor.submit<Pair<String, Int>?> {
            Socks5Handshake.negotiate(serverSide)
        }

        // SOCKS4 version byte (0x04) instead of 0x05 - must be rejected, not
        // silently coerced into a "best guess" destination.
        clientSide.getOutputStream().write(byteArrayOf(0x04, 0x01, 0x00))
        clientSide.getOutputStream().flush()

        val result = resultFuture.get(5, TimeUnit.SECONDS)
        assertNull(result)

        clientSide.close()
        serverSide.close()
    }
}
