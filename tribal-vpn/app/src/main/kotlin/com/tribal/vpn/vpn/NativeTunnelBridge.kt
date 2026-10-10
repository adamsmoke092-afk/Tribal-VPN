package com.tribal.vpn.vpn

import android.util.Log
import java.io.File
import hev.htproxy.TProxyService

/**
 * Kotlin-side wrapper for the native tunnel engines (Phase 4: hev-socks5-tunnel,
 * Phase 5: badvpn-udpgw).
 *
 * Phase 4 calls hev's OWN JNI binding, hev.htproxy.TProxyService - the class
 * upstream bundles inside its AAR. Loading our bridge .so pulls
 * libhev-socks5-tunnel.so in as a DT_NEEDED dependency, and its JNI_OnLoad
 * resolves (dlsym searches dependencies) and registers those methods - that
 * class MUST exist or JNI_OnLoad returns JNI_ERR and ART aborts the process
 * (uncatchable). Phase 5's externs remain in app/src/main/cpp/tunnel_bridge.cpp.
 *
 * IMPORTANT: TribalVpnService MUST treat a false return from startTunnel()/
 * startUdpGateway() as a real failure - never report Connected anyway. A
 * false return here aborts the connection and closes tun0.
 *
 * Every step is logged (logcat tag TribalTunnelBridge AND the durable app
 * log) so a silent false can never happen again without a trace.
 */
object NativeTunnelBridge {

    private const val TAG = "TribalTunnelBridge"

    private var libraryLoaded = false

    init {
        libraryLoaded = try {
            // Also loads libhev-socks5-tunnel.so (dependency) and runs its
            // JNI_OnLoad, registering natives onto hev.htproxy.TProxyService.
            System.loadLibrary("tribal_tunnel_bridge")
            true
        } catch (e: UnsatisfiedLinkError) {
            log("bridge library failed to load: ${e.message}", isError = true)
            false
        }
    }

    val isAvailable: Boolean get() = libraryLoaded

    /**
     * Wires the tun fd to the local SOCKS5 proxy opened by SshTunnelService.
     * The tun fd stays owned by TribalVpnService's ParcelFileDescriptor -
     * we only hand the raw fd to hev and never close it here.
     *
     * @param filesDir absolute path of the app's files dir (for the config)
     * @return true iff hev accepted the config and started its relay worker.
     */
    fun startTunnel(tunFd: Int, socksAddr: String, socksPort: Int, filesDir: String): Boolean {
        if (!libraryLoaded) {
            log("startTunnel: bridge library not loaded - relay cannot start (tun_fd=$tunFd)", isError = true)
            return false
        }
        // Config fields verified against upstream src/hev-config.c. udp 'tcp'
        // because the app's SOCKS5 server has no UDP ASSOCIATE - UDP is
        // Phase 5's job (badvpn-udpgw).
        val config = buildString {
            append("tunnel:\n")
            append("  name: tun0\n")
            append("  mtu: 1400\n")
            append("  ipv4: 10.0.0.2\n")
            append("socks5:\n")
            append("  port: $socksPort\n")
            append("  address: $socksAddr\n")
            append("  udp: 'tcp'\n")
        }
        val configFile = File(filesDir, "hev_tunnel_config.yml")
        return try {
            configFile.writeText(config)
            log("startTunnel: config=${configFile.absolutePath} tun_fd=$tunFd\n$config")
            // Upstream binding (hev-jni.c): returns jboolean - true iff the
            // relay worker started with a readable config path and a valid fd.
            val started = TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            log("startTunnel: TProxyStartService returned $started")
            started
        } catch (t: Throwable) {
            log(
                "startTunnel: ${t.javaClass.simpleName}: ${t.message} " +
                    "(config=${configFile.absolutePath} tun_fd=$tunFd)",
                isError = true
            )
            false
        }
    }

    fun stopTunnel() {
        if (!libraryLoaded) return
        try {
            val stopped = TProxyService.TProxyStopService()
            log("stopTunnel: TProxyStopService returned $stopped")
        } catch (t: Throwable) {
            log("stopTunnel: ${t.javaClass.simpleName}: ${t.message}", isError = true)
        }
    }

    /** @return true if the UDP gateway actually started, false otherwise. */
    fun startUdpGateway(tunFd: Int, udpgwAddr: String, udpgwPort: Int): Boolean {
        if (!libraryLoaded) return false
        return nativeStartUdpGateway(tunFd, udpgwAddr, udpgwPort) == 0
    }

    fun stopUdpGateway() {
        if (libraryLoaded) nativeStopUdpGateway()
    }

    private fun log(message: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, message) else Log.i(TAG, message)
        TribalVpnService.appendLog(null, message, isError)
    }

    // --- JNI externs; implemented in app/src/main/cpp/tunnel_bridge.cpp ---
    private external fun nativeStartUdpGateway(tunFd: Int, udpgwAddr: String, udpgwPort: Int): Int
    private external fun nativeStopUdpGateway()
}
