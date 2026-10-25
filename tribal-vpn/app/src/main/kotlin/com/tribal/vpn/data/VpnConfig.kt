package com.tribal.vpn.data

import com.tribal.vpn.xray.XrayConfigManager
import kotlinx.serialization.Serializable

/**
 * Represents a single saved VPN connection profile.
 * This is pure data - no hardcoded connection stats live here.
 *
 * A profile targets exactly ONE engine ([engine]):
 *  - [VpnEngine.SSH]: the original sshj transport. [host]/[port]/[username]
 *    and the auth fields are the real remote endpoint.
 *  - [VpnEngine.XRAY]: the libv2ray/Xray-core engine. The remote endpoint
 *    lives inside [xrayConfig] and the SSH fields are unused - they are kept
 *    (not blanked) so switching a profile's engine back to SSH does not lose
 *    what was already entered.
 *
 * Backward compatibility: a profile saved before the engine field existed
 * decodes as SSH via the field default, so every pre-existing profile keeps
 * behaving exactly as before.
 */
@Serializable
data class VpnConfig(
    val id: String,
    val name: String,
    // Which engine connects this profile. Default SSH = the value every
    // profile saved before this field existed gets on load.
    val engine: VpnEngine = VpnEngine.SSH,
    val host: String,
    val port: Int = 22,
    val username: String,
    // Full Xray JSON config for the XRAY engine (empty for SSH profiles).
    // Opaque end to end: the app validates it structurally and injects its
    // own loopback inbound at connect time, but never rewrites the user's
    // outbounds/routing. Never written to logs (see XrayConfigManager).
    val xrayConfig: String = "",
    val password: String = "",
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val privateKeyAlias: String? = null, // reference into Android Keystore, never raw key material
    val enableDns: Boolean = true,
    val preferredDns: String = "8.8.8.8",
    val alternateDns: String = "8.8.4.4",
    val enableUdp: Boolean = false,
    val udpGatewayPort: Int = 7300,
    // UDP Custom port range (HTTP Custom style): the TCP ports the VPS
    // may redirect to udpgw. The Phase 5 direct-route probe samples
    // ports from it; the future UDP client will pick its port from it.
    // Default 1-65535 matches the sshkit convention.
    val udpPortRange: String = "1-65535",
    val mtu: Int = 1400,
    // MapDNS (Phase 5): hev-socks5-tunnel answers DNS queries on-device
    // with fake IPs from the pool below; when an app connects to a fake
    // IP, the relay swaps it back to the original domain and sends a
    // domain-name CONNECT, so name resolution happens server-side over
    // TCP - no UDP needed. Default ON: the reverse mapping is verified in
    // hev's pinned source (hev_socks5_addr_from_lwip ->
    // hev_mapped_dns_lookup -> domain-name CONNECT).
    val enableMapDns: Boolean = true,
    // Fake-IP pool. Default 198.18.0.0/15 (RFC 2544 benchmark range): not
    // routable on the real internet and not carrier CGNAT space, so a
    // leaked fake IP is unambiguous.
    val mapDnsNetwork: String = "198.18.0.0",
    val mapDnsNetmask: String = "255.254.0.0"
) {
    @Serializable
    enum class AuthMethod { PASSWORD, KEY }

    /**
     * Which connection engine a profile uses. The persisted values are these
     * constant names - renaming an entry would orphan every saved profile,
     * which is why VpnConfigTest pins the serialized form.
     */
    @Serializable
    enum class VpnEngine { SSH, XRAY }

    /**
     * Honest one-line target label for the profile list and the home card.
     * An Xray profile has no SSH endpoint to show, so it says what it is
     * instead of rendering a misleading ":22" or "user@".
     */
    val endpointLabel: String
        get() = if (engine == VpnEngine.XRAY) {
            "Xray engine"
        } else {
            "$username@$host:$port"
        }

    /**
     * Engine-aware validation.
     *  - SSH: the original rules, unchanged, so existing profiles and their
     *    auth behavior cannot regress.
     *  - XRAY: the profile's own target is its config, so the config must be
     *    structurally valid before anything is allowed to connect. The SSH
     *    fields are deliberately not required.
     */
    fun isValid(): Boolean {
        if (name.isBlank()) return false
        return when (engine) {
            VpnEngine.XRAY -> XrayConfigManager.validateStructured(xrayConfig) == null
            VpnEngine.SSH -> {
                if (host.isBlank() || username.isBlank()) return false
                when (authMethod) {
                    AuthMethod.PASSWORD -> password.isNotBlank()
                    AuthMethod.KEY -> !privateKeyAlias.isNullOrBlank()
                }
            }
        }
    }

    companion object {
        /**
         * Parses a port from free-text input. Returns null for anything that
         * is not a clean integer in 1..65535 ("1-65535", "", "abc", "0",
         * "65536") - callers must surface that as a validation error, never
         * silently substitute a default like 22.
         */
        fun parsePort(raw: String): Int? =
            raw.trim().toIntOrNull()?.takeIf { it in 1..65535 }

        /**
         * Parses a single port ("7300") or an inclusive lo-hi range
         * ("1-65535") into an IntRange. Anything else - reversed ends,
         * ends outside 1..65535, extra dashes, empty - returns null;
         * callers surface that as a validation error, never silently
         * substitute a default.
         */
        fun parsePortRange(raw: String): IntRange? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val dash = text.indexOf('-')
            return if (dash < 0) {
                val port = parsePort(text) ?: return null
                port..port
            } else {
                val lo = parsePort(text.substring(0, dash)) ?: return null
                val hi = parsePort(text.substring(dash + 1)) ?: return null
                if (lo <= hi) lo..hi else null
            }
        }

        /**
         * Parses a dotted-quad IPv4 address into its 32-bit value.
         * Purely textual - never performs a DNS lookup. Returns null for
         * anything malformed; callers surface that as a validation error,
         * never silently substitute a default.
         */
        fun parseIPv4(raw: String): Int? {
            val parts = raw.trim().split('.')
            if (parts.size != 4) return null
            var value = 0L
            for (part in parts) {
                if (part.isEmpty() || part.length > 3) return null
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                value = (value shl 8) or octet.toLong()
            }
            return value.toInt()
        }

        /**
         * Parses a CONTIGUOUS netmask ("255.254.0.0" yes, "255.0.255.0"
         * no) into its 32-bit value. A non-contiguous mask would make the
         * fake-IP pool semantics undefined, so it is rejected, not coerced.
         */
        fun parseNetmask(raw: String): Int? {
            val mask = parseIPv4(raw) ?: return null
            val inverted = mask.inv()
            return if (inverted and (inverted + 1) == 0) mask else null
        }
    }
}
