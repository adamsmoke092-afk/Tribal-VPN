package com.tribal.vpn.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DnsOverSshTest {

    private fun bytes(vararg v: Int): ByteArray =
        ByteArray(v.size) { i -> v[i].toByte() }

    private fun name(vararg labels: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in labels) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        return out.toByteArray()
    }

    /** Minimal valid response: id/flags header + question echo + answers. */
    private fun response(answers: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x12)
        out.write(0x34) // id
        out.write(0x81)
        out.write(0x80) // response, recursion asked+ok, rcode 0
        out.write(0)
        out.write(1) // QD
        out.write((answers.size shr 8) and 0xFF)
        out.write(answers.size and 0xFF) // AN
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(name("example", "com"))
        out.write(0)
        out.write(1) // qtype A
        out.write(0)
        out.write(1) // qclass IN
        for (a in answers) out.write(a)
        return out.toByteArray()
    }

    private fun aRecord(ip: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xC0)
        out.write(0x0C) // pointer to the question name
        out.write(0)
        out.write(1) // type A
        out.write(0)
        out.write(1) // class IN
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(60) // ttl
        out.write(0)
        out.write(ip.size) // rdlength
        out.write(ip)
        return out.toByteArray()
    }

    private fun cnameRecord(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xC0)
        out.write(0x0C)
        out.write(0)
        out.write(5) // type CNAME
        out.write(0)
        out.write(1)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(60)
        out.write(0)
        out.write(2) // rdlength: the rdata is one 2-byte compression pointer
        out.write(0xC0)
        out.write(0x0C)
        return out.toByteArray()
    }

    @Test
    fun `buildQuery produces the exact RFC 1035 message`() {
        assertArrayEquals(
            bytes(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) +
                name("example", "com") + bytes(0x00, 0x01, 0x00, 0x01),
            DnsCodec.buildQuery(0x1234, "example.com", DnsCodec.TYPE_A)
        )
    }

    @Test
    fun `frameTcp prepends a big-endian length`() {
        val msg = bytes(0x00, 0x01, 0x02, 0x03)
        assertArrayEquals(bytes(0x00, 0x04, 0x00, 0x01, 0x02, 0x03), DnsCodec.frameTcp(msg))
    }

    @Test
    fun `parseAddress returns the matching A record`() {
        val ip = bytes(93, 184, 216, 34)
        assertArrayEquals(ip, DnsCodec.parseAddress(response(listOf(aRecord(ip))), DnsCodec.TYPE_A))
    }

    @Test
    fun `parseAddress skips CNAME records and finds the A record`() {
        val ip = bytes(1, 2, 3, 4)
        val resp = response(listOf(cnameRecord(), aRecord(ip)))
        assertArrayEquals(ip, DnsCodec.parseAddress(resp, DnsCodec.TYPE_A))
    }

    @Test
    fun `parseAddress returns null on error rcode`() {
        val resp = response(listOf(aRecord(bytes(1, 2, 3, 4)))).clone()
        resp[3] = (resp[3].toInt() or 0x03).toByte() // rcode = NXDOMAIN
        assertNull(DnsCodec.parseAddress(resp, DnsCodec.TYPE_A))
    }

    @Test
    fun `resolve queries the server then serves from the cache`() {
        val ip = bytes(93, 184, 216, 34)
        val canned = DnsCodec.frameTcp(response(listOf(aRecord(ip))))

        val toServer = PipedOutputStream()
        val serverReads = PipedInputStream(toServer)
        val serverWrites = PipedOutputStream()
        val clientIn = PipedInputStream(serverWrites)
        val connects = AtomicInteger(0)
        val served = CountDownLatch(1)

        Thread {
            try {
                val lenBuf = ByteArray(2)
                readFully(serverReads, lenBuf)
                val len = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                readFully(serverReads, ByteArray(len))
                serverWrites.write(canned)
                serverWrites.flush()
                served.countDown()
            } catch (_: IOException) {
            }
        }.apply { isDaemon = true }.start()

        val resolver = DnsOverSsh(
            connect = {
                connects.incrementAndGet()
                DnsIo(clientIn, toServer) {}
            },
            onLog = {}
        )

        assertArrayEquals(ip, resolver.resolve("example.com"))
        assertTrue2(served.await(2, TimeUnit.SECONDS))
        // Second resolve inside the TTL must not open a new connection.
        assertArrayEquals(ip, resolver.resolve("example.com"))
        assertEquals(1, connects.get())
    }

    private fun assertTrue2(v: Boolean) = org.junit.Assert.assertTrue(v)

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("eof")
            off += n
        }
    }
}
