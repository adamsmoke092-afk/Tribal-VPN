package com.tribal.vpn.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectUdpProbePortsTest {

    // Port selection for the direct-route probe: an iptables redirect
    // covers whole ranges, so a few spread samples prove or disprove the
    // path without hammering the provider with 65k connects.

    @Test
    fun `sshkit range samples udpgw port plus three spread across it`() {
        assertEquals(
            listOf(7300, 1, 32768, 65535),
            directUdpProbePorts(1..65535, udpgwPort = 7300, sshPort = 22)
        )
    }

    @Test
    fun `udpgw port outside the range is not sampled`() {
        assertEquals(
            listOf(1, 50, 100),
            directUdpProbePorts(1..100, udpgwPort = 7300, sshPort = 22)
        )
    }

    @Test
    fun `the SSH port is never sampled - sshd would be a false positive`() {
        // Range 20..30 with SSH port 25 = the range's midpoint: it must drop.
        assertEquals(
            listOf(20, 30),
            directUdpProbePorts(20..30, udpgwPort = 7300, sshPort = 25)
        )
    }

    @Test
    fun `range containing only the SSH port samples nothing`() {
        assertEquals(
            emptyList<Int>(),
            directUdpProbePorts(22..22, udpgwPort = 7300, sshPort = 22)
        )
    }

    @Test
    fun `single-port range collapses to one sample`() {
        assertEquals(
            listOf(7300),
            directUdpProbePorts(7300..7300, udpgwPort = 7300, sshPort = 22)
        )
    }
}
