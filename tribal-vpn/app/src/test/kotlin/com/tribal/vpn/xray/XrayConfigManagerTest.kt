package com.tribal.vpn.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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

    // Synthetic on purpose: test fixtures must never contain a real
    // credential (UUIDs, passwords) - not even one from a config someone
    // pasted into a chat.
    private val uuid = "01234567-89ab-cdef-0123-456789abcdef"
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

    // --- removed TLS fields (allowInsecure) ------------------------------

    /**
     * The exact on-device failure this round: the pinned core (v26.10.21)
     * hard-errors while building the outbound's TLS config when
     * tlsSettings.allowInsecure is true. Validation must catch it BEFORE
     * XrayEngine.start() runs, name the exact JSON path, name the CURRENT
     * replacement fields, and leak no config values.
     */
    @Test
    fun `allowInsecure true is rejected with the exact json path before native startup`() {
        val config = """
            {"outbounds": [{"protocol": "vless", "settings": {"vnext": [
                {"address": "$serverHost", "port": 443,
                 "users": [{"id": "$uuid", "encryption": "none"}]}]},
              "streamSettings": {"network": "ws", "security": "tls",
                "tlsSettings": {"serverName": "front.example.test",
                                "alpn": ["h2"],
                                "fingerprint": "chrome",
                                "allowInsecure": true}}}]}
        """.trimIndent()
        val reason = XrayConfigManager.validateStructured(config)
        assertNotNull("allowInsecure:true must be rejected", reason)
        assertTrue(
            "must name the exact JSON path, was: $reason",
            reason!!.contains("outbounds[0].streamSettings.tlsSettings.allowInsecure")
        )
        assertTrue(
            "must name the CURRENT replacement field (not the removed one)",
            reason.contains("pinnedPeerCertSha256")
        )
        assertTrue(
            "must name the CURRENT replacement field",
            reason.contains("verifyPeerCertByName")
        )
        // Secret-safety: the message may name paths only, never values.
        assertFalse("uuid leaked into the error", reason.contains(uuid))
        assertFalse("host leaked into the error", reason.contains(serverHost))
        // Defense in depth: even a caller that skipped validateStructured
        // cannot get this config to the native core.
        try {
            XrayConfigManager.buildRuntimeConfig(config, 12345)
            throw AssertionError("buildRuntimeConfig must refuse allowInsecure:true")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("rejected"))
        }
    }

    @Test
    fun `allowInsecure false is tolerated and preserved verbatim`() {
        // The pinned core declares `AllowInsecure bool` and errors only when
        // it is true (infra/conf/transport_security.go, `if c.AllowInsecure`).
        // A false value is a dead field the core tolerates, so the app must
        // neither reject it nor rewrite it.
        val config = """
            {"outbounds": [{"protocol": "vless", "settings": {"vnext": []},
              "streamSettings": {"network": "tcp", "security": "tls",
                "tlsSettings": {"allowInsecure": false}}}]}
        """.trimIndent()
        assertNull(XrayConfigManager.validateStructured(config))
        val runtime = json.parseToJsonElement(
            XrayConfigManager.buildRuntimeConfig(config, 12345)
        ).jsonObject
        val tls = (runtime["outbounds"] as JsonArray)[0]
            .jsonObject["streamSettings"]!!.jsonObject["tlsSettings"]!!.jsonObject
        assertEquals(false, (tls["allowInsecure"] as JsonPrimitive).booleanOrNull)
    }

    @Test
    fun `allowInsecure true in a non-first outbound is found among multiple outbounds`() {
        val config = """
            {"outbounds": [
               {"tag": "direct", "protocol": "freedom"},
               {"tag": "proxy", "protocol": "vless", "settings": {"vnext": []},
                "streamSettings": {"network": "tcp", "security": "tls",
                  "tlsSettings": {"allowInsecure": true}}}]}
        """.trimIndent()
        val reason = XrayConfigManager.validateStructured(config)
        assertNotNull(reason)
        assertTrue(
            "must point at the second outbound, was: $reason",
            reason!!.contains("outbounds[1].streamSettings.tlsSettings.allowInsecure")
        )
    }

    @Test
    fun `valid tls configuration without obsolete fields passes and preserves everything`() {
        val config = """
            {"outbounds": [{"protocol": "vless", "settings": {"vnext": [
                {"address": "$serverHost", "port": 443,
                 "users": [{"id": "$uuid", "encryption": "none"}]}]},
              "streamSettings": {"network": "ws", "security": "tls",
                "tlsSettings": {"serverName": "front.example.test",
                                "alpn": ["h2", "http/1.1"],
                                "fingerprint": "chrome",
                                "pinnedPeerCertSha256":
                                  "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"}}}]}
        """.trimIndent()
        assertNull(XrayConfigManager.validateStructured(config))
        val runtime = json.parseToJsonElement(
            XrayConfigManager.buildRuntimeConfig(config, 12345)
        ).jsonObject
        val outbound = (runtime["outbounds"] as JsonArray)[0].jsonObject
        // Unrelated fields preserved exactly through validation + runtime
        // assembly: VLESS id, server address, transport, security, server
        // name, alpn, fingerprint - and the modern pin field.
        val vnext = (outbound["settings"]!!.jsonObject["vnext"] as JsonArray)[0].jsonObject
        val user = (vnext["users"] as JsonArray)[0].jsonObject
        assertEquals(uuid, (user["id"] as JsonPrimitive).content)
        assertEquals(serverHost, (vnext["address"] as JsonPrimitive).content)
        val stream = outbound["streamSettings"]!!.jsonObject
        assertEquals("tls", (stream["security"] as JsonPrimitive).content)
        assertEquals("ws", (stream["network"] as JsonPrimitive).content)
        val tls = stream["tlsSettings"]!!.jsonObject
        assertEquals("front.example.test", (tls["serverName"] as JsonPrimitive).content)
        assertEquals("chrome", (tls["fingerprint"] as JsonPrimitive).content)
        assertNotNull("modern pin field must survive", tls["pinnedPeerCertSha256"])
        assertNotNull("alpn must survive", tls["alpn"])
    }

    @Test
    fun `no-tls configuration passes untouched`() {
        // security "none" is expressed purely through the pasted JSON - the
        // editor has no security selector. The pipeline must accept it and
        // preserve it; nothing ever downgrades TLS to no-TLS automatically.
        val config = """
            {"outbounds": [{"protocol": "vless", "settings": {"vnext": [
                {"address": "$serverHost", "port": 80,
                 "users": [{"id": "$uuid", "encryption": "none"}]}]},
              "streamSettings": {"network": "tcp", "security": "none"}}]}
        """.trimIndent()
        assertNull(XrayConfigManager.validateStructured(config))
        val runtime = json.parseToJsonElement(
            XrayConfigManager.buildRuntimeConfig(config, 12345)
        ).jsonObject
        val stream = (runtime["outbounds"] as JsonArray)[0]
            .jsonObject["streamSettings"]!!.jsonObject
        assertEquals("none", (stream["security"] as JsonPrimitive).content)
    }

    @Test
    fun `config with no streamSettings at all passes`() {
        // No streamSettings means no TLS negotiation at all - same no-TLS
        // case as above, expressed by absence.
        val config = """{"outbounds": [{"protocol": "vless", "settings": {"vnext": []}}]}"""
        assertNull(XrayConfigManager.validateStructured(config))
    }
}
