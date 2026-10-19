package com.tribal.vpn.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5RefusalsTest {

    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun `first refusal of each shape logs immediately`() {
        val lines = mutableListOf<String>()
        val r = Socks5Refusals(clock = { 0L }, emit = { lines.add(it) })

        r.refused("cmd=0x05", "hev FWD_UDP (UDP-in-TCP)")
        r.refused("cmd=0x03", "UDP ASSOCIATE")

        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("cmd=0x05"))
        assertTrue(lines[0].contains("FWD_UDP"))
        assertTrue(lines[1].contains("cmd=0x03"))
    }

    @Test
    fun `repeats inside the window are silent`() {
        val lines = mutableListOf<String>()
        val r = Socks5Refusals(clock = { 0L }, emit = { lines.add(it) })

        repeat(50) { r.refused("cmd=0x05", "hev FWD_UDP") }

        assertEquals(1, lines.size)
    }

    @Test
    fun `after the window one count line is emitted`() {
        val clock = FakeClock()
        val lines = mutableListOf<String>()
        val r = Socks5Refusals(windowMs = 10_000, clock = clock, emit = { lines.add(it) })

        r.refused("cmd=0x05", "x")                 // t=0: first line
        repeat(9) { r.refused("cmd=0x05", "x") }    // silent
        clock.advance(10_000)
        r.refused("cmd=0x05", "x")                 // t=10s: count line

        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("x10"))
    }

    @Test
    fun `shapes are tracked independently`() {
        val clock = FakeClock()
        val lines = mutableListOf<String>()
        val r = Socks5Refusals(windowMs = 10_000, clock = clock, emit = { lines.add(it) })

        r.refused("cmd=0x05", "a")      // t=0
        clock.advance(2_000)
        r.refused("cmd=0x03", "b")      // t=2s, new shape -> immediate line
        clock.advance(2_000)
        r.refused("cmd=0x05", "a")      // t=4s, still inside its window -> silent

        assertEquals(2, lines.size)
    }

    @Test
    fun `count resets after a count line is emitted`() {
        val clock = FakeClock()
        val lines = mutableListOf<String>()
        val r = Socks5Refusals(windowMs = 10_000, clock = clock, emit = { lines.add(it) })

        r.refused("cmd=0x05", "x")
        clock.advance(10_000)
        r.refused("cmd=0x05", "x")   // count line, reset
        clock.advance(10_000)
        r.refused("cmd=0x05", "x")   // fresh window, zero suppressed

        assertEquals(3, lines.size)
        assertTrue(lines[2].contains("x1"))
        assertTrue(!lines[2].contains("x10"))
    }
}
