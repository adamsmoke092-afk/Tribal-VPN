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
    // 0 = let the kernel assign a free ephemeral port for the local SOCKS5
    // listener. The bound port is exposed by SshTunnelService.socksPort and
    // fed to the relay config, so two instances can never collide on 1080.
    val socksBindPort: Int = 0,
    val serverAliveIntervalSeconds: Int = 15,
    val connectTimeoutMs: Int = 10_000,
    // MapDNS fake-IP pool for leak detection (netmask 0 = detection off):
    // a CONNECT targeting an address inside this range means hev's reverse
    // mapping did not fire and the connection would die at the SSH server.
    // Parsed by TribalVpnService from VpnConfig so this layer never touches
    // the data model.
    val mapDnsNetwork: Int = 0,
    val mapDnsNetmask: Int = 0,
    // Port the Phase 5 udpgw probe targets on the SSH host's loopback
    // (passed from VpnConfig.udpGatewayPort; 7300 is badvpn-udpgw's default).
    val udpgwPort: Int = 7300,
    // UDP Custom port range (parsed from VpnConfig.udpPortRange by
    // TribalVpnService): the direct-route probe samples ports from this
    // range against the SSH host - the HTTP Custom redirect mechanism.
    val udpPortRange: IntRange = 1..65535,
    // When true, SshTunnelService arms the udpgw client and accepts hev's
    // FWD_UDP connections (the UDP Custom feature). When false, FWD_UDP
    // handshakes are refused honestly like before.
    val enableUdp: Boolean = false,
    // Upstream DNS server the UDP bridge's NAME resolver queries through
    // the SSH session (from VpnConfig.preferredDns).
    val dnsAddress: String = "8.8.8.8"
)
