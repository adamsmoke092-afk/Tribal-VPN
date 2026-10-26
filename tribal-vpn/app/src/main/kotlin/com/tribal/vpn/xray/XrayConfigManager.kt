package com.tribal.vpn.xray

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Pure (zero Android dependencies) validation and runtime-config assembly for
 * the Xray engine, so every rule below is unit-testable on the JVM.
 *
 * SUPPORTED INPUT FORMAT (deliberate, documented, and the ONLY format the app
 * claims to support): a standard Xray JSON config whose `outbounds` describe
 * the proxy. Share links (vless://, vmess://, trojan://, ss://) are rejected
 * with a clear message instead of being half-parsed.
 *
 * WHAT THE APP DOES TO THAT CONFIG (why it is not a "silent default"):
 *  - `inbounds` is REPLACED with the app-owned loopback SOCKS5 inbound. That
 *    inbound is the engine seam: hev-socks5-tunnel (which owns tun0) points at
 *    it, exactly like it points at the SSH engine's SOCKS5 listener. The tag of
 *    the user's first inbound is preserved when present so routing rules that
 *    match on `inboundTag` keep meaning the same thing - and when the user has
 *    no inbounds, a fixed default tag is used.
 *  - `log` is normalized to `{"loglevel":"warning","access":"none"}` so no
 *    access log is written and the console stays quiet. This is a secrecy
 *    control, not a convenience: a user-supplied `access` path or `debug`
 *    level would let core diagnostics write data the app promised not to log.
 *  - Everything else (outbounds, routing, dns, streamSettings, ...) is
 *    preserved verbatim.
 *
 * Field names are verified against Xray-core's own parser:
 * infra/conf/xray.go (InboundDetourConfig: protocol/port/listen/settings/tag/
 * streamSettings/sniffing), infra/conf/socks.go (SocksServerConfig: auth,
 * udp, ip), infra/conf/log.go (LogConfig: loglevel, access, error, dnsLog).
 *
 * KNOWN LIMITATION: no geo assets (geosite.dat / geoip.dat) are shipped, so a
 * config whose routing rules reference `geoip:`/`geosite:` fails at core start
 * with the core's own error - honestly, not silently. Dropping the .dat files
 * into the app's files dir (the `xray.location.asset` path) is the fix.
 */
object XrayConfigManager {

    /** Tag for the app-owned inbound when the user config has no inbound tag. */
    const val DEFAULT_INBOUND_TAG = "tribal-socks-in"

    /** Xray outbound protocol meaning "connect directly" - never a tunnel. */
    private const val PROTOCOL_DIRECT = "freedom"

    /**
     * Recognized share-link prefixes. Checked BEFORE JSON parsing so the user
     * gets "that is a share link, paste the JSON" instead of a JSON syntax
     * error that hides the real problem.
     */
    private val shareLinkSchemes = listOf(
        "vless://", "vmess://", "trojan://", "ss://", "socks://", "ssr://"
    )

    /**
     * Fields REMOVED from Xray's TLS schema that the pinned core hard-errors
     * on. Verified in the pinned source (infra/conf/transport_security.go at
     * 1b1647cd9ddf0abc34b981d4611fc03bcac1ca74 = the v26.10.21 release
     * commit):
     *
     *     AllowInsecure            bool   `json:"allowInsecure"`
     *     ...
     *     if c.AllowInsecure {
     *         return nil, errors.PrintRemovedFeatureError(`allowInsecure`,
     *             `"pinnedPeerCertSha256"(pcs) and "verifyPeerCertByName"(vcn)`)
     *     }
     *
     * The field is a plain bool (not *bool), so the core errors ONLY when the
     * value is true; `allowInsecure: false` parses as a dead field and is
     * tolerated - this validator mirrors exactly that (reject true, tolerate
     * and preserve false). The CURRENT replacements are
     * "tlsSettings.pinnedPeerCertSha256" and "tlsSettings.verifyPeerCertByName"
     * (both comma-separated strings in the same struct); the older
     * "pinnedPeerCertificateChainSha256" no longer exists in the struct, so
     * error messages must never recommend it.
     *
     * If a second removed field is ever added here, the human-readable
     * sentence in validateStructured (which names "allowInsecure") must be
     * generalized with it.
     */
    private val removedTlsFields = setOf("allowInsecure")

    // Mirrors ConfigRepository's Json so normalization/round-trip behavior is
    // identical in app and tests.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Structural validation of a user-supplied Xray JSON config.
     *
     * @return null when the config is usable, otherwise a human-readable
     *   reason. Deliberately does NOT echo parse-exception messages: kotlinx
     *   (and Go) quote fragments of the input in their error strings, and a
     *   config carries credentials (UUIDs, passwords). The caller shows this
     *   string to the user and may log it; it must never contain config text.
     */
    fun validateStructured(userJson: String): String? {
        val trimmed = userJson.trim()
        if (trimmed.isEmpty()) {
            return "Xray configuration is empty - paste an Xray JSON config that contains your outbound(s)"
        }
        val lower = trimmed.lowercase()
        shareLinkSchemes.firstOrNull { lower.startsWith(it) }?.let {
            return "That is a share link (${it}...) - share links are not supported yet. " +
                "Paste the full Xray JSON config instead."
        }

        val root = try {
            json.parseToJsonElement(trimmed)
        } catch (_: Throwable) {
            // The exception message is intentionally dropped: it can quote
            // config contents (and therefore credentials).
            return "Not valid JSON (parse failed). Check for a truncated paste - " +
                "the parser's message is not shown because it can quote config contents."
        }
        if (root !is JsonObject) {
            return "The top level of an Xray config must be a JSON object"
        }

        val outbounds = root["outbounds"]
            ?: return "Missing \"outbounds\" - an Xray config needs at least one proxy outbound"
        if (outbounds !is JsonArray) {
            return "\"outbounds\" must be a JSON array"
        }
        if (outbounds.isEmpty()) {
            return "\"outbounds\" is empty - add your VLESS/Trojan/VMess outbound"
        }

        val protocols = ArrayList<String>(outbounds.size)
        outbounds.forEachIndexed { index, element ->
            val outbound = element as? JsonObject
                ?: return "Outbound #${index + 1} is not a JSON object"
            val protocol = (outbound["protocol"] as? JsonPrimitive)?.contentOrNull
            if (protocol.isNullOrBlank()) {
                return "Outbound #${index + 1} has no \"protocol\" string"
            }
            protocols += protocol
        }

        // Fields the pinned core rejects at config-build time. Caught HERE so
        // the user gets a precise, secret-free error in the editor (at save)
        // and in the connect log - instead of a raw core error after startup
        // began. Paths name keys and indices only, never values, so the
        // message is safe to display and log even though configs carry
        // credentials. The app does NOT rewrite or auto-migrate security
        // fields (there is no fingerprint to invent): the user removes the
        // field from their own JSON.
        val removedPaths = ArrayList<String>()
        collectRemovedTlsFields(root, "", removedPaths)
        if (removedPaths.isNotEmpty()) {
            return "Removed Xray field(s) found: ${removedPaths.joinToString(", ")}. " +
                "\"allowInsecure\" no longer exists in Xray's TLS schema - the pinned core " +
                "(v26.10.21) fails to start while it is true. Its current replacements are " +
                "\"tlsSettings.pinnedPeerCertSha256\" and \"tlsSettings.verifyPeerCertByName\". " +
                "Remove the field from your JSON; this app does not rewrite security-related " +
                "fields. (Configs converted from share links usually carry it under " +
                "outbounds[*].streamSettings.tlsSettings.)"
        }

        // A config whose outbounds are ALL freedom does not tunnel anything:
        // it would connect "successfully" and send all traffic directly. That
        // is exactly the kind of fake success this project forbids, so it is
        // rejected up front with an actionable message.
        if (protocols.all { it == PROTOCOL_DIRECT }) {
            return "Every outbound is \"freedom\" (direct) - nothing would be tunneled. " +
                "Add a proxy outbound (vless, trojan, vmess, shadowsocks, ...)."
        }
        return null
    }

    /**
     * Builds the runtime config handed to the Xray core.
     *
     * Throws [IllegalArgumentException] when the input is not structurally
     * usable (callers must have validated first; this is the no-silent-default
     * guard, not a second validation path).
     */
    fun buildRuntimeConfig(userJson: String, socksPort: Int): String {
        require(socksPort in 1..65535) { "inbound port out of range: $socksPort" }
        validateStructured(userJson)?.let { reason ->
            throw IllegalArgumentException("Xray configuration rejected: $reason")
        }
        val root = json.parseToJsonElement(userJson.trim()).jsonObject

        val preservedTag = (root["inbounds"] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("tag")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_INBOUND_TAG

        val runtime = LinkedHashMap<String, JsonElement>(root)
        runtime["inbounds"] = JsonArray(listOf(socksInbound(preservedTag, socksPort)))
        runtime["log"] = buildJsonObject {
            put("loglevel", "warning")
            put("access", "none")
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(runtime))
    }

    /**
     * Redacted one-line summary of a config for the durable log. Contains
     * protocol names and counts ONLY - never addresses, UUIDs, passwords or
     * any other config text. Safe to call on unvalidated input.
     */
    fun summarizeForLog(userJson: String): String {
        val root = try {
            json.parseToJsonElement(userJson.trim()) as? JsonObject
        } catch (_: Throwable) {
            null
        } ?: return "unparseable configuration (not logged by design)"
        val protocols = (root["outbounds"] as? JsonArray)?.mapNotNull { element ->
            ((element as? JsonObject)?.get("protocol") as? JsonPrimitive)?.contentOrNull
        } ?: emptyList()
        val routingRules = (root["routing"] as? JsonObject)?.get("rules") as? JsonArray
        return buildString {
            append("outbounds=").append(protocols.size)
            append(" [").append(protocols.joinToString(",")).append(']')
            if (routingRules != null) append(", routingRules=").append(routingRules.size)
        }
    }

    /**
     * Allocates a loopback TCP port for the app-owned SOCKS5 inbound by
     * binding (and immediately releasing) 127.0.0.1:0. Same "kernel picks the
     * port" discipline as the SSH engine's SOCKS listener.
     *
     * There is a small TOCTOU window between the release here and the core
     * binding it milliseconds later; if another process wins the race, the
     * core fails to start and the connection is aborted honestly - never
     * reported as Connected.
     */
    fun allocateLoopbackPort(): Int {
        val probe = ServerSocket()
        return try {
            probe.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            probe.localPort
        } finally {
            try {
                probe.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Walks [element] collecting the JSON paths of removed TLS fields whose
     * value would make the pinned core hard-error (see [removedTlsFields]).
     * Only boolean-true occurrences are collected, mirroring the core's own
     * `if c.AllowInsecure` condition; `false` is a dead field the core
     * tolerates, and a wrongly-typed value is left for the core's decoder to
     * reject honestly. Paths contain keys and indices only - never values -
     * so the collected list is safe to show and log.
     */
    private fun collectRemovedTlsFields(
        element: JsonElement,
        path: String,
        out: MutableList<String>
    ) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                val childPath = if (path.isEmpty()) key else "$path.$key"
                if (key in removedTlsFields &&
                    (value as? JsonPrimitive)?.booleanOrNull == true
                ) {
                    out += childPath
                }
                collectRemovedTlsFields(value, childPath, out)
            }
            is JsonArray -> element.forEachIndexed { index, item ->
                collectRemovedTlsFields(item, "$path[$index]", out)
            }
            else -> Unit
        }
    }

    /**
     * The app-owned inbound. Field names verified in Xray-core's
     * infra/conf/socks.go (auth/udp/ip) and infra/conf/xray.go (tag/listen/
     * port/protocol/settings). Bound to loopback only: nothing outside the
     * app (and hev) can reach it.
     */
    private fun socksInbound(tag: String, port: Int): JsonObject = buildJsonObject {
        put("tag", tag)
        put("listen", "127.0.0.1")
        put("port", port)
        put("protocol", "socks")
        put("settings", buildJsonObject {
            put("auth", "noauth")
            put("udp", true)
            put("ip", "127.0.0.1")
        })
    }
}
