package com.tribal.vpn.data

import kotlinx.serialization.Serializable

/**
 * Represents a single saved VPN connection profile.
 * This is pure data - no hardcoded connection stats live here.
 */
@Serializable
data class VpnConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
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

    fun isValid(): Boolean {
        if (name.isBlank() || host.isBlank() || username.isBlank()) return false
        return when (authMethod) {
            AuthMethod.PASSWORD -> password.isNotBlank()
            AuthMethod.KEY -> !privateKeyAlias.isNullOrBlank()
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
