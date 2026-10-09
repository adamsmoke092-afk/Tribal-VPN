package com.tribal.vpn.ssh

/**
 * Minimal parameter bag passed from VpnConfig into SshTunnelService.
 * Kept separate from VpnConfig so the SSH layer doesn't depend on the
 * UI/storage-facing data model.
 */
data class SshConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val privateKeyAlias: String?,
    val socksBindPort: Int = 1080,
    val serverAliveIntervalSeconds: Int = 15,
    val connectTimeoutMs: Int = 10_000
)
