package com.tribal.vpn.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UdpGwClientTest {

    // Pipe pairs stand in for the real udpgw server; every test asserts the
    // exact wire bytes (all little-endian, verified against badvpn master).

    private class Pipes {
        val clientIn = PipedInputStream()     // client reads responses here
        val fromClient = PipedInputStream()   // test reads client output here
        private val clientOutStream = PipedOutputStream(fromClient)
        private val toClientStream = PipedOutputStream(clientIn)

        fun client(maxConnections: Int = 256, onLog: (String) -> Unit = {}): UdpGwClient =
            UdpGwClient(
                clientIn, clientOutStream,
                maxConnections = maxConnections,
                keepaliveMs = Long.MAX_VALUE,
                onLog = onLog
            ).apply { start() }

        fun serverRead(count: Int): ByteArray {
            // Inlined read: a (non-inner) nested class cannot call the
            // outer test class's instance members.
            val out = ByteArray(count)
            var off = 0
            while (off < count) {
                val n = fromClient.read(out, off, count - off)
                if (n < 0) throw IOException("eof")
                off += n
            }
            return out
        }

        fun serverWrite(bytes: ByteArray) {
            toClientStream.write(bytes)
            toClientStream.flush()
        }
    }

    private fun bytes(vararg v: Int): ByteArray =
        ByteArray(v.size) { i -> v[i].toByte() }

    @Test
    fun `first datagram carries REBIND with exact little-endian bytes`() {
        val pipes = Pipes()
        val client = pipes.client()
        val owner = Any()
        assertTrue(client.submit(owner, UdpDest(bytes(1, 2, 3, 4), 443, false), "hello".toByteArray()))

        // PacketProto len=14 LE, flags=REBIND, conid=0 LE, IPv4 octets
        // REVERSED (4,3,2,1), port 443 LE (0x01BB -> BB 01), payload.
        assertArrayEquals(
            bytes(0x0E, 0x00, 0x02, 0x00, 0x00, 0x04, 0x03, 0x02, 0x01, 0xBB, 0x01) +
                "hello".toByteArray(),
            pipes.serverRead(16)
        )
        client.stop()
    }

    @Test
    fun `same flow keeps conid and drops REBIND, new flow gets next conid`() {
        val pipes = Pipes()
        val client = pipes.client()
        val owner = Any()
        val destA = UdpDest(bytes(1, 2, 3, 4), 53, false)
        val destB = UdpDest(bytes(5, 6, 7, 8), 53, false)

        assertTrue(client.submit(owner, destA, "a".toByteArray()))
        pipes.serverRead(12) // len=10 + 2-byte prefix

        assertTrue(client.submit(owner, destA, "a2".toByteArray()))
        assertArrayEquals(
            bytes(0x0B, 0x00, 0x00, 0x00, 0x00, 0x04, 0x03, 0x02, 0x01, 0x35, 0x00) +
                "a2".toByteArray(),
            pipes.serverRead(13)
        )

        assertTrue(client.submit(owner, destB, "b".toByteArray()))
        assertArrayEquals(
            bytes(0x0A, 0x00, 0x02, 0x01, 0x00, 0x08, 0x07, 0x06, 0x05, 0x35, 0x00) +
                "b".toByteArray(),
            pipes.serverRead(12)
        )
        client.stop()
    }

    @Test
    fun `response frames are parsed and routed to the owner sink`() {
        val pipes = Pipes()
        val client = pipes.client()
        val owner = Any()
        assertTrue(client.submit(owner, UdpDest(bytes(1, 2, 3, 4), 443, false), "hello".toByteArray()))
        pipes.serverRead(16) // drain the outbound frame

        val latch = CountDownLatch(1)
        var gotDest: UdpDest? = null
        var gotPayload: ByteArray? = null
        client.register(owner) { dest, payload ->
            gotDest = dest
            gotPayload = payload
            latch.countDown()
        }

        // flags=0, conid=0, IPv4 reversed (4,3,2,1), port 443 LE, "pong"
        pipes.serverWrite(
            bytes(0x0D, 0x00, 0x00, 0x00, 0x00, 0x04, 0x03, 0x02, 0x01, 0xBB, 0x01) +
                "pong".toByteArray()
        )
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertArrayEquals(bytes(1, 2, 3, 4), gotDest!!.host)
        assertEquals(443, gotDest!!.port)
        assertFalse(gotDest!!.isIpv6)
        assertArrayEquals("pong".toByteArray(), gotPayload!!)
        client.stop()
    }

    @Test
    fun `full flow table rebinds the least recently used conid`() {
        val pipes = Pipes()
        val client = pipes.client(maxConnections = 2)
        val ownerA = Any()
        val ownerB = Any()
        val ownerC = Any()

        assertTrue(client.submit(ownerA, UdpDest(bytes(10, 0, 0, 1), 53, false), "a".toByteArray()))
        pipes.serverRead(12)
        assertTrue(client.submit(ownerB, UdpDest(bytes(10, 0, 0, 2), 53, false), "b".toByteArray()))
        pipes.serverRead(12)

        // Table (2) is full: ownerC must REBIND the LRU conid (ownerA's 0).
        assertTrue(client.submit(ownerC, UdpDest(bytes(10, 0, 0, 3), 53, false), "c".toByteArray()))
        assertArrayEquals(
            bytes(0x0A, 0x00, 0x02, 0x00, 0x00, 0x03, 0x00, 0x00, 0x0A, 0x35, 0x00) +
                "c".toByteArray(),
            pipes.serverRead(12)
        )

        // A response for conid 0 now belongs to ownerC, not ownerA.
        val latchC = CountDownLatch(1)
        client.register(ownerC) { _, _ -> latchC.countDown() }
        val calledA = CountDownLatch(1)
        client.register(ownerA) { _, _ -> calledA.countDown() }
        pipes.serverWrite(
            bytes(0x0A, 0x00, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x0A, 0x35, 0x00) +
                "x".toByteArray()
        )
        assertTrue(latchC.await(2, TimeUnit.SECONDS))
        assertFalse(calledA.await(200, TimeUnit.MILLISECONDS))
        client.stop()
    }

    @Test
    fun `keepalive frame is a bare little-endian header`() {
        val client = UdpGwClient(PipedInputStream(), PipedOutputStream(), keepaliveMs = Long.MAX_VALUE) { }
        // [len=3 LE][flags=KEEPALIVE][conid=0]
        assertArrayEquals(bytes(0x03, 0x00, 0x01, 0x00, 0x00), client.frame(client.buildKeepalive()))
    }

    @Test
    fun `unregister releases the owner's flows`() {
        val pipes = Pipes()
        val client = pipes.client()
        val owner = Any()
        assertTrue(client.submit(owner, UdpDest(bytes(1, 2, 3, 4), 53, false), "a".toByteArray()))
        pipes.serverRead(12)

        client.unregister(owner)
        // The old flow is gone; a fresh submit allocates the NEXT conid
        // (badvpn's next_conid only wraps at the cap) and REBINDs it.
        assertTrue(client.submit(owner, UdpDest(bytes(1, 2, 3, 4), 53, false), "a2".toByteArray()))
        assertArrayEquals(
            bytes(0x0B, 0x00, 0x02, 0x01, 0x00, 0x04, 0x03, 0x02, 0x01, 0x35, 0x00) +
                "a2".toByteArray(),
            pipes.serverRead(13)
        )

        // The re-registered owner receives via the new conid mapping.
        val called = CountDownLatch(1)
        client.register(owner) { _, _ -> called.countDown() }
        pipes.serverWrite(
            bytes(0x0A, 0x00, 0x00, 0x01, 0x00, 0x04, 0x03, 0x02, 0x01, 0x35, 0x00) +
                "x".toByteArray()
        )
        assertTrue(called.await(2, TimeUnit.SECONDS))
        client.stop()
    }
}
