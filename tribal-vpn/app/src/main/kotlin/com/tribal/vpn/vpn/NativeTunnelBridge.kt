package com.tribal.vpn.vpn

/**
 * Kotlin-side wrapper for the native tunnel engines (Phase 4: hev-socks5-tunnel,
 * Phase 5: badvpn-udpgw). Method names/signatures here MUST match the JNI
 * exports in app/src/main/cpp/tunnel_bridge.cpp exactly (Java_com_tribal_vpn_...).
 *
 * IMPORTANT: TribalVpnService MUST treat a false return from startTunnel()/
 * startUdpGateway() as a real failure — do not swallow it and report Connected
 * anyway. startTunnel() is backed by the real hev-socks5-tunnel engine
 * (Phase 4, see app/src/main/cpp/Android.mk); startUdpGateway() still
 * returns false until badvpn-udpgw is vendored (Phase 5). If startTunnel()
 * returns false, "Connected" still only means "tun interface + SSH SOCKS5
 * proxy are up" — traffic is NOT flowing end-to-end.
 */
object NativeTunnelBridge {

    private var libraryLoaded = false

    init {
        libraryLoaded = try {
            System.loadLibrary("tribal_tunnel_bridge")
            true
        } catch (e: UnsatisfiedLinkError) {
            // Expected until the NDK build actually produces the .so per ABI.
            // Not fatal — callers check isAvailable before use.
            false
        }
    }

    val isAvailable: Boolean get() = libraryLoaded

    /**
     * Wires the tun fd to the local SOCKS5 proxy opened by SshTunnelService.
     * @return true if the native tunnel actually started, false otherwise —
     *         never assume success.
     */
    fun startTunnel(tunFd: Int, socksAddr: String, socksPort: Int): Boolean {
        if (!libraryLoaded) return false
        return nativeStartTunnel(tunFd, socksAddr, socksPort) == 0
    }

    fun stopTunnel() {
        if (libraryLoaded) nativeStopTunnel()
    }

    /** @return true if the UDP gateway actually started, false otherwise. */
    fun startUdpGateway(tunFd: Int, udpgwAddr: String, udpgwPort: Int): Boolean {
        if (!libraryLoaded) return false
        return nativeStartUdpGateway(tunFd, udpgwAddr, udpgwPort) == 0
    }

    fun stopUdpGateway() {
        if (libraryLoaded) nativeStopUdpGateway()
    }

    // --- JNI externs; implemented in app/src/main/cpp/tunnel_bridge.cpp ---
    private external fun nativeStartTunnel(tunFd: Int, socksAddr: String, socksPort: Int): Int
    private external fun nativeStopTunnel()
    private external fun nativeStartUdpGateway(tunFd: Int, udpgwAddr: String, udpgwPort: Int): Int
    private external fun nativeStopUdpGateway()
}
