package com.tribal.vpn.data

/**
 * Represents a single saved VPN connection profile.
 * This is pure data - no hardcoded connection stats live here.
 */
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
    val mtu: Int = 1400
) {
    enum class AuthMethod { PASSWORD, KEY }

    fun isValid(): Boolean {
        if (name.isBlank() || host.isBlank() || username.isBlank()) return false
        return when (authMethod) {
            AuthMethod.PASSWORD -> password.isNotBlank()
            AuthMethod.KEY -> !privateKeyAlias.isNullOrBlank()
        }
    }
}
