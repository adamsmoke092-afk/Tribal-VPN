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
}
