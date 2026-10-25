package com.tribal.vpn.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules pinned here (per the integration spec):
 *  - a valid Xray profile's config is accepted,
 *  - malformed/incomplete/direct-only configs are rejected with a useful
 *    message and never silently replaced by a default,
 *  - the generated runtime config contains the app-managed SOCKS5 inbound on
 *    the allocated port and preserves the user's outbounds/routing,
 *  - diagnostics never contain config secrets (UUIDs, addresses, passwords).
 */
class XrayConfigManagerTest {

    private val uuid = "e179c288-6f45-4ef5-b970-66be278faadc"
    private val serverHost = "vpn.example-host.test"

    /** A realistic user config in the supported format. */
    private fun vlessConfig(): String = """
        {
          "log": {"loglevel": "info", "access": "/sdcard/should-never-be-used.log"},
          "inbounds": [
            {"tag": "socks-in", "listen": "127.0.0.1", "port": 10808,
             "protocol": "socks", "settings": {"auth": "noauth"}}
          ],
          "outbounds": [
            {"tag": "proxy", "protocol": "vless",
             "settings": {"vnext": [{"address": "$serverHost", "port": 443,
               "users": [{"id": "$uuid", "encryption": "none", "password": "hunter2"}]}]},
             "streamSettings": {"network": "ws", "security": "tls",
               "wsSettings": {"path": "/x", "headers": {"Host": "front.example.test"}}}},
            {"tag": "direct", "protocol": "freedom"}
          ],
          "routing": {"rules": [
            {"type": "field", "outboundTag": "direct", "domain": ["geosite:private"]},
            {"type": "field", "outboundTag": "direct", "ip": ["geoip:private"]}
          ]}
        }
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    // --- validation -------------------------------------------------------

    @Test
    fun `valid vless config passes structural validation`() {
        assertNull(XrayConfigManager.validateStructured(vlessConfig()))
    }

    @Test
    fun `empty config is rejected`() {
        val reason = XrayConfigManager.validateStructured("")
        assertNotNull(reason)
        assertTrue(reason!!.contains("empty"))
    }

    @Test
    fun `share link is rejected with a useful message, not a JSON syntax error`() {
        for (link in listOf(
            "vless://$uuid@example.test:443?type=ws#name",
            "trojan://pass@example.test:443",
            "vmess://eyJhZGQiOiJleGFtcGxlIn0="
        )) {
            val reason = XrayConfigManager.validateStructured(link)
            assertNotNull("share link must be rejected: $link", reason)
            assertTrue(
                "reason should name share links, was: $reason",
                reason!!.contains("share link", ignoreCase = true)
            )
        }
    }

    @Test
    fun `malformed json is rejected without echoing the parser message`() {
        val broken = """{"outbounds": [{"protocol": "vless",}]}"""
        val reason = XrayConfigManager.validateStructured(broken)
        assertNotNull(reason)
        // The parser's own message can quote input; it must not be forwarded.
        assertFalse(reason!!.contains("Unexpected JSON token"))
        assertFalse(reason.contains("vless"))
    }

    @Test
    fun `top level must be an object`() {
        assertNotNull(XrayConfigManager.validateStructured("[1,2,3]"))
        assertNotNull(XrayConfigManager.validateStructured("\"just a string\""))
    }

    @Test
    fun `missing outbounds is rejected`() {
        val reason = XrayConfigManager.validateStructured("""{"inbounds": []}""")
        assertNotNull(reason)
        assertTrue(reason!!.contains("outbounds"))
    }

    @Test
    fun `empty outbounds is rejected`() {
        assertNotNull(XrayConfigManager.validateStructured("""{"outbounds": []}"""))
    }

    @Test
    fun `outbounds must be an array`() {
        assertNotNull(XrayConfigManager.validateStructured("""{"outbounds": {}}"""))
    }

    @Test
    fun `outbound without a protocol is rejected with its index`() {
        val reason = XrayConfigManager.validateStructured(
            """{"outbounds": [{"tag": "ok", "protocol": "vless"}, {"tag": "bad"}]}"""
        )
        assertNotNull(reason)
        assertTrue("should name the offending outbound, was: $reason", reason!!.contains("#2"))
    }

    @Test
    fun `direct-only config is rejected - it would not tunnel anything`() {
        val reason = XrayConfigManager.validateStructured(
            """{"outbounds": [{"protocol": "freedom"}]}"""
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("freedom"))
        // A proxy + freedom pair is fine (freedom is the routing target).
        assertNull(
            XrayConfigManager.validateStructured(
                """{"outbounds": [{"protocol": "vless"}, {"protocol": "freedom"}]}"""
            )
        )
    }

    // --- runtime config assembly -----------------------------------------

    private fun runtime(port: Int = 23456): JsonObject =
        json.parseToJsonElement(XrayConfigManager.buildRuntimeConfig(vlessConfig(), port)).jsonObject

    @Test
    fun `runtime config replaces inbounds with the app-managed socks inbound on the allocated port`() {
        val root = runtime(port = 23456)
        val inbounds = root["inbounds"] as JsonArray
        assertEquals(1, inbounds.size)
        val inbound = inbounds[0].jsonObject
        assertEquals("socks", (inbound["protocol"] as JsonPrimitive).content)
        assertEquals("127.0.0.1", (inbound["listen"] as JsonPrimitive).content)
        assertEquals(23456, (inbound["port"] as JsonPrimitive).content.toInt())
        val settings = inbound["settings"]!!.jsonObject
        assertEquals("noauth", (settings["auth"] as JsonPrimitive).content)
        assertEquals(true, (settings["udp"] as JsonPrimitive).content.toBoolean())
        assertEquals("127.0.0.1", (settings["ip"] as JsonPrimitive).content)
    }

    @Test
    fun `runtime config keeps the user's first inbound tag so routing rules still match`() {
        val inbound = (runtime()["inbounds"] as JsonArray)[0].jsonObject
        assertEquals("socks-in", (inbound["tag"] as JsonPrimitive).content)
    }

    @Test
    fun `runtime config uses the default tag when the user config has no inbounds`() {
        val cfg = """{"outbounds": [{"protocol": "vless"}]}"""
        val root = json.parseToJsonElement(
            XrayConfigManager.buildRuntimeConfig(cfg, 12345)
        ).jsonObject
        val inbound = (root["inbounds"] as JsonArray)[0].jsonObject
        assertEquals(
            XrayConfigManager.DEFAULT_INBOUND_TAG,
            (inbound["tag"] as JsonPrimitive).content
        )
    }

    @Test
    fun `runtime config preserves outbounds, routing and stream settings verbatim`() {
        val root = runtime()
        val outbounds = root["outbounds"] as JsonArray
        assertEquals(2, outbounds.size)
        val proxy = outbounds[0].jsonObject
        assertEquals("vless", (proxy["protocol"] as JsonPrimitive).content)
        val settings = proxy["settings"]!!.jsonObject
        val vnext = (settings["vnext"] as JsonArray)[0].jsonObject
        assertEquals(serverHost, (vnext["address"] as JsonPrimitive).content)
        val user = (vnext["users"] as JsonArray)[0].jsonObject
        assertEquals(uuid, (user["id"] as JsonPrimitive).content)
        val stream = proxy["streamSettings"]!!.jsonObject
        assertEquals("ws", (stream["network"] as JsonPrimitive).content)
        val rules = (root["routing"]!!.jsonObject["rules"] as JsonArray)
        assertEquals(2, rules.size)
    }

    @Test
    fun `runtime config normalizes log - no access log path, warning level`() {
        val log = runtime()["log"]!!.jsonObject
        assertEquals("warning", (log["loglevel"] as JsonPrimitive).content)
        assertEquals("none", (log["access"] as JsonPrimitive).content)
        // The user's file path must be gone.
        assertFalse(log.toString().contains("/sdcard/"))
    }

    @Test
    fun `buildRuntimeConfig refuses to run on an invalid config`() {
        try {
            XrayConfigManager.buildRuntimeConfig("", 1234)
            throw AssertionError("empty config must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("rejected"))
        }
    }

    @Test
    fun `buildRuntimeConfig refuses an out-of-range port`() {
        for (port in listOf(0, -1, 70000)) {
            try {
                XrayConfigManager.buildRuntimeConfig(vlessConfig(), port)
                throw AssertionError("port $port must be rejected")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    // --- redaction --------------------------------------------------------

    @Test
    fun `summary never contains credentials or addresses`() {
        val summary = XrayConfigManager.summarizeForLog(vlessConfig())
        assertFalse("uuid leaked: $summary", summary.contains(uuid))
        assertFalse("server host leaked: $summary", summary.contains(serverHost))
        assertFalse("password leaked: $summary", summary.contains("hunter2"))
        assertFalse("path leaked: $summary", summary.contains("/x"))
        assertTrue(summary.contains("vless"))
        assertTrue(summary.contains("outbounds=2"))
        assertTrue(summary.contains("routingRules=2"))
    }

    @Test
    fun `summary of unparseable input contains no input text`() {
        val summary = XrayConfigManager.summarizeForLog("""{"secret": "top-secret-value",}""")
        assertFalse(summary.contains("top-secret-value"))
        assertTrue(summary.contains("unparseable"))
    }

    // --- port allocation --------------------------------------------------

    @Test
    fun `allocateLoopbackPort returns a bindable ephemeral port`() {
        val port = XrayConfigManager.allocateLoopbackPort()
        assertTrue("port in range: $port", port in 1..65535)
        // It was released, so binding it again must work right now.
        java.net.ServerSocket().use { probe ->
            probe.bind(java.net.InetSocketAddress("127.0.0.1", port))
        }
    }
}
