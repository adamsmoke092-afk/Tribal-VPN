package com.tribal.vpn.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnConfigTest {

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
}
