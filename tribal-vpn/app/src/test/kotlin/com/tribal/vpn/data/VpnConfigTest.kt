package com.tribal.vpn.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnConfigTest {

    // The exact Json configuration ConfigRepository uses for profile
    // persistence, so the round-trip tests mirror real save/load.
    private fun repoJson() = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun baseConfig(
        name: String = "Test",
        host: String = "vps.example.com",
        username: String = "root",
        password: String = "hunter2",
        authMethod: VpnConfig.AuthMethod = VpnConfig.AuthMethod.PASSWORD,
        privateKeyAlias: String? = null
    ) = VpnConfig(
        id = "1", name = name, host = host, username = username,
        password = password, authMethod = authMethod, privateKeyAlias = privateKeyAlias
    )

    @Test
    fun `valid password config passes validation`() {
        assertTrue(baseConfig().isValid())
    }

    @Test
    fun `blank name fails validation`() {
        assertFalse(baseConfig(name = "").isValid())
    }

    @Test
    fun `blank host fails validation`() {
        assertFalse(baseConfig(host = "").isValid())
    }

    @Test
    fun `blank username fails validation`() {
        assertFalse(baseConfig(username = "").isValid())
    }

    @Test
    fun `password auth with blank password fails validation`() {
        assertFalse(baseConfig(password = "").isValid())
    }

    @Test
    fun `key auth with no alias fails validation`() {
        assertFalse(
            baseConfig(
                authMethod = VpnConfig.AuthMethod.KEY,
                password = "", // irrelevant for key auth
                privateKeyAlias = null
            ).isValid()
        )
    }

    @Test
    fun `key auth with alias passes validation even without password`() {
        assertTrue(
            baseConfig(
                authMethod = VpnConfig.AuthMethod.KEY,
                password = "",
                privateKeyAlias = "my-key-alias"
            ).isValid()
        )
    }

    // --- parsePort: the Edit Profile port fields use this. A null result
    // must surface as a validation error, never a silent default like 22. ---

    @Test
    fun `parsePort accepts 22, 8022 and 443`() {
        assertEquals(22, VpnConfig.parsePort("22"))
        assertEquals(8022, VpnConfig.parsePort("8022"))
        assertEquals(443, VpnConfig.parsePort("443"))
    }

    @Test
    fun `parsePort rejects a range string - never silently becomes 22`() {
        // "1-65535" is a port RANGE (HTTP Custom style), not a port number.
        // It must produce a validation error, not the 22 default.
        assertNull(VpnConfig.parsePort("1-65535"))
    }

    @Test
    fun `parsePort rejects empty, non-numeric, zero, negative and out-of-range`() {
        assertNull(VpnConfig.parsePort(""))
        assertNull(VpnConfig.parsePort("abc"))
        assertNull(VpnConfig.parsePort("0"))
        assertNull(VpnConfig.parsePort("-22"))
        assertNull(VpnConfig.parsePort("65536"))
    }

    @Test
    fun `parsePort trims whitespace`() {
        assertEquals(8022, VpnConfig.parsePort(" 8022 "))
    }

    // --- parsePortRange: the UDP Custom range field uses this. A null
    // result must surface as a validation error, never a silent default. ---

    @Test
    fun `parsePortRange accepts single ports and lo-hi ranges`() {
        assertEquals(7300..7300, VpnConfig.parsePortRange("7300"))
        assertEquals(22..22, VpnConfig.parsePortRange("22"))
        assertEquals(1..65535, VpnConfig.parsePortRange("1-65535"))
        assertEquals(1000..2000, VpnConfig.parsePortRange(" 1000 - 2000 "))
    }

    @Test
    fun `parsePortRange rejects garbage, reversed and out-of-range`() {
        assertNull(VpnConfig.parsePortRange(""))
        assertNull(VpnConfig.parsePortRange("abc"))
        assertNull(VpnConfig.parsePortRange("-22"))
        assertNull(VpnConfig.parsePortRange("65535-1"))
        assertNull(VpnConfig.parsePortRange("0-100"))
        assertNull(VpnConfig.parsePortRange("1-65536"))
        assertNull(VpnConfig.parsePortRange("1-65535-9"))
    }

    @Test
    fun `udpPortRange round trips through save and load`() {
        val json = repoJson()
        val original = baseConfig().copy(udpPortRange = "7300-7400")
        val decoded = json.decodeFromString<VpnConfig>(json.encodeToString(original))
        assertEquals("7300-7400", decoded.udpPortRange)
    }

    @Test
    fun `old profiles without udpPortRange decode to 1-65535`() {
        // Pinned load-side behavior: profiles saved before the range
        // field existed decode with the sshkit-convention default. A
        // future default change must touch this test first.
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<VpnConfig>(
            """{"id":"1","name":"Test","host":"vps.example.com","username":"root"}"""
        )
        assertEquals("1-65535", decoded.udpPortRange)
        // UDP Custom stays OFF for old profiles: the toggle must be
        // flipped deliberately, never silently enabled by an app update.
        assertEquals(false, decoded.enableUdp)
    }

    @Test
    fun `legacy port 65535 is preserved, never reinterpreted`() {
        // The one-box bug once saved SSH port 65535 by mistake. The loader
        // must hand back exactly what is stored - reinterpretation, silent
        // repair, or a port-22 fallback would all hide real state.
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<VpnConfig>(
            """{"id":"1","name":"Test","host":"vps.example.com","username":"root","port":65535}"""
        )
        assertEquals(65535, decoded.port)
    }

    @Test
    fun `ssh port and udp gateway port stay independent`() {
        // The two numbers belong to two different protocols; persistence
        // must never cross-wire them.
        val json = repoJson()
        val original = baseConfig().copy(
            port = 8022,
            udpGatewayPort = 7300,
            udpPortRange = "1-65535"
        )
        val decoded = json.decodeFromString<VpnConfig>(json.encodeToString(original))
        assertEquals(8022, decoded.port)
        assertEquals(7300, decoded.udpGatewayPort)
        assertEquals("1-65535", decoded.udpPortRange)
    }

    // --- save/load round trip: the port a user saved must come back. ---

    @Test
    fun `port round trip preserves 22, 8022 and 443`() {
        val json = repoJson()
        for (port in listOf(22, 8022, 443)) {
            val original = baseConfig().copy(port = port)
            val decoded = json.decodeFromString<VpnConfig>(json.encodeToString(original))
            assertEquals("port $port must survive a save/load round trip", port, decoded.port)
        }
    }

    @Test
    fun `saved json contains the port explicitly even when it equals the default`() {
        // encodeDefaults=true (ConfigRepository) writes every field, so a
        // future default change can never silently rewrite old saves.
        val json = repoJson()
        val encoded = json.encodeToString(baseConfig().copy(port = 22))
        assertTrue("port must be stored explicitly, not implied by a default",
            encoded.contains("\"port\":22"))
    }

    @Test
    fun `json missing the port field decodes to the data class default`() {
        // Documented hazard: a save that omits `port` (written before
        // encodeDefaults=true, or by any external writer) restores port=22
        // silently. UI-side parsePort validation is what stops bad input at
        // the door; this test pins the load-side behavior as a known,
        // deliberate value - not an accident.
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<VpnConfig>(
            """{"id":"1","name":"Test","host":"vps.example.com","username":"root"}"""
        )
        assertEquals(22, decoded.port)
    }

    // --- parseIPv4 / parseNetmask: the MapDNS pool fields use these. A
    // null result must surface as a validation error, never a silent
    // default. ---

    @Test
    fun `parseIPv4 accepts valid dotted quads`() {
        assertEquals(0xC6120000.toInt(), VpnConfig.parseIPv4("198.18.0.0"))
        assertEquals(0x08080808, VpnConfig.parseIPv4("8.8.8.8"))
        assertEquals(0, VpnConfig.parseIPv4("0.0.0.0"))
        assertEquals(0xFFFFFFFF.toInt(), VpnConfig.parseIPv4("255.255.255.255"))
    }

    @Test
    fun `parseIPv4 rejects malformed and out-of-range input`() {
        assertNull(VpnConfig.parseIPv4("abc"))
        assertNull(VpnConfig.parseIPv4("1.2.3"))
        assertNull(VpnConfig.parseIPv4("256.0.0.1"))
        assertNull(VpnConfig.parseIPv4("1.2.3.4.5"))
        assertNull(VpnConfig.parseIPv4(""))
    }

    @Test
    fun `parseNetmask accepts contiguous masks and rejects holey ones`() {
        assertEquals(0xFFFE0000.toInt(), VpnConfig.parseNetmask("255.254.0.0"))
        assertEquals(0xFFFFFF00.toInt(), VpnConfig.parseNetmask("255.255.255.0"))
        // Non-contiguous mask: fake-IP pool semantics would be undefined.
        assertNull(VpnConfig.parseNetmask("255.0.255.0"))
        assertNull(VpnConfig.parseNetmask("1.2.3.4"))
        assertNull(VpnConfig.parseNetmask("nonsense"))
    }

    @Test
    fun `mapdns fields round trip through save and load`() {
        val json = repoJson()
        val original = baseConfig().copy(
            enableMapDns = true,
            mapDnsNetwork = "198.18.0.0",
            mapDnsNetmask = "255.254.0.0"
        )
        val decoded = json.decodeFromString<VpnConfig>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `old profiles without mapdns fields decode to mapdns defaults`() {
        // Pinned load-side behavior: profiles saved before the MapDNS
        // fields existed decode with mapdns ENABLED and the 198.18.0.0/15
        // pool (default ON is a deliberate decision - the reverse mapping
        // is verified in hev's pinned source). A future default change
        // must touch this test first.
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<VpnConfig>(
            """{"id":"1","name":"Test","host":"vps.example.com","username":"root"}"""
        )
        assertEquals(true, decoded.enableMapDns)
        assertEquals("198.18.0.0", decoded.mapDnsNetwork)
        assertEquals("255.254.0.0", decoded.mapDnsNetmask)
    }

    // --- Engine selection (SSH vs Xray) ---------------------------------

    private fun xrayConfigJson(): String =
        """{"outbounds": [{"protocol": "vless", "settings": {"vnext": []}}]}"""

    @Test
    fun `old profiles without an engine field decode as SSH`() {
        // Pinned load-side behavior: every profile saved before the engine
        // field existed must keep behaving as the SSH profile it always was -
        // same validation, same endpoint label, no migration step.
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<VpnConfig>(
            """{"id":"1","name":"Test","host":"vps.example.com","username":"root","password":"pw"}"""
        )
        assertEquals(VpnConfig.VpnEngine.SSH, decoded.engine)
        assertTrue(decoded.isValid())
        assertEquals("root@vps.example.com:22", decoded.endpointLabel)
    }

    @Test
    fun `engine is written explicitly so a future default change cannot rewrite saves`() {
        val json = repoJson()
        val encoded = json.encodeToString(baseConfig())
        assertTrue(
            "engine must be stored explicitly, not implied by a default",
            encoded.contains("\"engine\":\"SSH\"")
        )
    }

    @Test
    fun `xray profile round trips with its config`() {
        val json = repoJson()
        val original = baseConfig(
            host = "", username = "", password = ""
        ).copy(
            engine = VpnConfig.VpnEngine.XRAY,
            xrayConfig = xrayConfigJson()
        )
        val decoded = json.decodeFromString<VpnConfig>(json.encodeToString(original))
        assertEquals(VpnConfig.VpnEngine.XRAY, decoded.engine)
        assertEquals(xrayConfigJson(), decoded.xrayConfig)
        assertEquals("Xray engine", decoded.endpointLabel)
    }

    @Test
    fun `xray profile is valid without an ssh host or username`() {
        val config = baseConfig(host = "", username = "", password = "").copy(
            engine = VpnConfig.VpnEngine.XRAY,
            xrayConfig = xrayConfigJson()
        )
        assertTrue(config.isValid())
    }

    @Test
    fun `xray profile with an empty or invalid config fails validation`() {
        val empty = baseConfig(host = "", username = "", password = "").copy(
            engine = VpnConfig.VpnEngine.XRAY,
            xrayConfig = ""
        )
        assertFalse(empty.isValid())

        val noOutbounds = empty.copy(xrayConfig = """{"outbounds": []}""")
        assertFalse(noOutbounds.isValid())

        val directOnly = empty.copy(xrayConfig = """{"outbounds": [{"protocol": "freedom"}]}""")
        assertFalse(directOnly.isValid())
    }

    @Test
    fun `ssh profile validation and behavior are unchanged by the engine field`() {
        // Exactly the rules that existed before the Xray engine - an SSH
        // profile that was valid stays valid, and the SSH-only requirements
        // still hold.
        assertTrue(baseConfig().isValid())
        assertFalse(baseConfig(host = "").isValid())
        assertFalse(baseConfig(username = "").isValid())
        assertFalse(baseConfig(password = "").isValid())
        // An SSH profile ignores xrayConfig entirely (it is unused there).
        assertTrue(baseConfig().copy(xrayConfig = "not even json").isValid())
    }
}
