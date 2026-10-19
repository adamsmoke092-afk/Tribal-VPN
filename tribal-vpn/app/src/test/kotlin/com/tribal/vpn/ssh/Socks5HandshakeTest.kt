package com.tribal.vpn.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Exercises Socks5Handshake.negotiate() against a REAL loopback socket pair
 * (no mocking of Socket/streams) so the byte-level RFC 1928 framing is
 * actually verified, not just assumed correct. Reply bytes are asserted
 * exactly: the wire stream is the contract with hev-socks5-tunnel. Refused
 * handshakes assert their stable shape key, which the rate-limited refusal
 * logging keys on.
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

    private fun negotiateAsync(serverSide: Socket) =
        executor.submit<Negotiation> { Socks5Handshake.negotiate(serverSide) }

    private fun write(socket: Socket, bytes: ByteArray) {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private fun readExactly(socket: Socket, count: Int): ByteArray {
        val buf = ByteArray(count)
        val input = socket.getInputStream()
        var off = 0
        while (off < count) {
            val n = input.read(buf, off, count - off)
            if (n < 0) throw EOFException("socket closed after $off of $count reply bytes")
            off += n
        }
        return buf
    }

    /** Sends the greeting and asserts the server's exact method-selection reply. */
    private fun greetingAndMethodSelection(client: Socket) {
        write(client, byteArrayOf(0x05, 0x01, 0x00))
        assertTrue(
            "expected [05 00] method reply",
            readExactly(client, 2).contentEquals(byteArrayOf(0x05, 0x00))
        )
    }

    private val connectSuccessReply = byteArrayOf(
        0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0
    )

    private val cmdNotSupportedReply = byteArrayOf(
        0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0
    )

    @Test
    fun `negotiate parses IPv4 CONNECT request correctly`() {
        val (serverSide, clientSide) = loopbackPair()
        val resultFuture = negotiateAsync(serverSide)

        greetingAndMethodSelection(clientSide)
        // CONNECT request for 93.184.216.34:443
        write(clientSide, byteArrayOf(
            0x05, 0x01, 0x00, 0x01,
            93.toByte(), 184.toByte(), 216.toByte(), 34.toByte(),
            0x01, 0xBB.toByte()
        ))

        val result = resultFuture.get(5, TimeUnit.SECONDS)
        assertEquals(Negotiation.Connect("93.184.216.34", 443), result)
        assertTrue(
            "byte-exact CONNECT success reply",
            readExactly(clientSide, 10).contentEquals(connectSuccessReply)
        )

        clientSide.close()
        serverSide.close()
    }

    @Test
    fun `negotiate parses domain name CONNECT request correctly`() {
        val (serverSide, clientSide) = loopbackPair()
        val resultFuture = negotiateAsync(serverSide)

        greetingAndMethodSelection(clientSide)

        val domain = "vps.example.com"
        val domainBytes = domain.toByteArray(Charsets.US_ASCII)
        val request = ByteArray(5 + domainBytes.size + 2)
        request[0] = 0x05; request[1] = 0x01; request[2] = 0x00; request[3] = 0x03
        request[4] = domainBytes.size.toByte()
        domainBytes.copyInto(request, 5)
        request[5 + domainBytes.size] = 0x00
        request[5 + domainBytes.size + 1] = 0x16 // port 22

        write(clientSide, request)

        val result = resultFuture.get(5, TimeUnit.SECONDS)
        assertEquals(Negotiation.Connect(domain, 22), result)
        assertTrue(
            "byte-exact CONNECT success reply",
            readExactly(clientSide, 10).contentEquals(connectSuccessReply)
        )

        clientSide.close()
        serverSide.close()
    }

    @Test
    fun `negotiate rejects wrong SOCKS version`() {
        val (serverSide, clientSide) = loopbackPair()
        val resultFuture = negotiateAsync(serverSide)

        // SOCKS4 version byte (0x04) instead of 0x05 - must be rejected, not
        // silently coerced into a "best guess" destination.
        write(clientSide, byteArrayOf(0x04, 0x01, 0x00))

        val result = resultFuture.get(5, TimeUnit.SECONDS)
        assertTrue("expected Refused, got $result", result is Negotiation.Refused)
        assertEquals("bad version", (result as Negotiation.Refused).shape)

        clientSide.close()
        serverSide.close()
    }

    @Test
    fun `hev FWD_UDP command is refused and labeled`() {
        val (serverSide, clientSide) = loopbackPair()
        val resultFuture = negotiateAsync(serverSide)

        greetingAndMethodSelection(clientSide)
        // cmd = 0x05: hev-socks5-core's FWD_UDP (UDP-in-TCP extension), the
        // command hev sends today while our relay has no UDP path yet.
        write(clientSide, byteArrayOf(
            0x05, 0x05, 0x00, 0x01,
            8, 8, 8, 8, 0x00, 0x35
        ))

        val result = resultFuture.get(5, TimeUnit.SECONDS)
        assertTrue(result is Negotiation.Refused)
        assertEquals("cmd=0x05", (result as Negotiation.Refused).shape)
        assertTrue(result.detail.contains("FWD_UDP"))
        assertTrue(readExactly(clientSide, 10).contentEquals(cmdNotSupportedReply))

        clientSide.close()
        serverSide.close()
    }

    @Test
    fun `UDP ASSOCIATE command is refused and labeled`() {
        val (serverSide, clientSide) = loopbackPair()
        val resultFuture = negotiateAsync(serverSide)

        greetingAndMethodSelection(clientSide)
        write(clientSide, byteArrayOf(
            0x05, 0x03, 0x00, 0x01,
            8, 8, 8, 8, 0x00, 0x35
        ))

        val result = resultFuture.get(5, TimeUnit.SECONDS)
        assertTrue(result is Negotiation.Refused)
        assertEquals("cmd=0x03", (result as Negotiation.Refused).shape)
        assertTrue(result.detail.contains("UDP ASSOCIATE"))
        assertTrue(readExactly(clientSide, 10).contentEquals(cmdNotSupportedReply))

        clientSide.close()
        serverSide.close()
    }

    @Test
    fun `greeting without NO_AUTH is refused with 0xFF reply`() {
        val (serverSide, clientSide) = loopbackPair()
        val resultFuture = negotiateAsync(serverSide)

        // Methods 0x01 and 0x02 only - no NO_AUTH (0x00).
        write(clientSide, byteArrayOf(0x05, 0x02, 0x01, 0x02))

        val result = resultFuture.get(5, TimeUnit.SECONDS)
        assertTrue(result is Negotiation.Refused)
        assertEquals("no NO_AUTH in greeting", (result as Negotiation.Refused).shape)
        assertTrue(
            readExactly(clientSide, 2).contentEquals(byteArrayOf(0x05, 0xFF.toByte()))
        )

        clientSide.close()
        serverSide.close()
    }

    @Test
    fun `unsupported address type is refused with general failure reply`() {
        val (serverSide, clientSide) = loopbackPair()
        val resultFuture = negotiateAsync(serverSide)

        greetingAndMethodSelection(clientSide)
        write(clientSide, byteArrayOf(0x05, 0x01, 0x00, 0x06, 0x00, 0x35))

        val result = resultFuture.get(5, TimeUnit.SECONDS)
        assertTrue(result is Negotiation.Refused)
        assertEquals("atyp=0x06", (result as Negotiation.Refused).shape)
        assertTrue(
            readExactly(clientSide, 10).contentEquals(
                byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
            )
        )

        clientSide.close()
        serverSide.close()
    }
}
