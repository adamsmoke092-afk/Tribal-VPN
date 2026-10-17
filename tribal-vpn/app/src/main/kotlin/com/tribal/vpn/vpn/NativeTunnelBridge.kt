package com.tribal.vpn.vpn

import android.content.Context
import android.os.Debug
import android.util.Log
import com.tribal.vpn.data.AppSettingsRepository
import com.tribal.vpn.diagnostics.Diagnostics
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

    /**
     * Ordered diagnostic variants of the bridge .so (built in Android.mk):
     * a = current bridge, default link flags; b = same source with RELRO
     * disabled; c = the full pre-81fdf0b bridge. This build exists to learn
     * which of them loads on the Android 8.1 phone.
     */
    private val VARIANTS = listOf("tribal_bridge_a", "tribal_bridge_b", "tribal_bridge_c")

    @Volatile private var bootstrapped = false
    @Volatile private var libraryLoaded = false
    @Volatile private var loadedVariant: String? = null

    /** The variant whose successful load registered the natives in use. */
    val activeVariant: String? get() = loadedVariant

    /**
     * Runs the variant cascade ONCE, explicitly from TribalVpnApp.onCreate
     * (not from a class initializer - this needs a Context for the probe
     * setting and the crash-skip store, and must run after
     * Diagnostics.init, which owns the probe markers).
     *
     * Crash safety: a fsync'd marker is written before every
     * System.loadLibrary and deleted after the load returns or throws.
     * Only a process death DURING the load can leave it behind - and that
     * is exactly the death no Java catch can see (linker abort, SIGSEGV
     * inside JNI_OnLoad). The next launch records the killer variant and
     * skips it for the rest of THIS install; the record dies with the
     * install (PackageInfo.lastUpdateTime), so an app update re-tests
     * every variant honestly.
     */
    fun bootstrap(context: Context) {
        if (bootstrapped) return
        bootstrapped = true

        val probeAll = try {
            AppSettingsRepository(context).probeAllVariants.value
        } catch (_: Throwable) {
            true // diagnostic build: default to learning the most
        }

        // A leftover marker = the previous launch died mid-load.
        Diagnostics.consumeLeftoverLoadProbe()?.let { killer ->
            Diagnostics.recordVariantCrash(killer)
            log("variant $killer: crashed the process last time - recording and skipping", isError = true)
        }
        val crashed = Diagnostics.crashedVariants()

        for (variant in VARIANTS) {
            if (variant in crashed) {
                log("variant $variant: skipped (crashed the process this install)", isError = true)
                continue
            }
            Diagnostics.beginLoadProbe(variant)
            try {
                System.loadLibrary(variant)
                if (loadedVariant == null) loadedVariant = variant
                libraryLoaded = true
                log("variant $variant: OK")
            } catch (e: UnsatisfiedLinkError) {
                // Expected for b/c in release builds (debug-only modules
                // there) - the FAIL line is honest, not a defect.
                log("variant $variant: FAIL ${e.message}", isError = true)
            } finally {
                Diagnostics.endLoadProbe()
            }
            if (libraryLoaded && !probeAll) break
        }

        if (loadedVariant == null) {
            log("all bridge variants failed to load - Phase 5 externs unavailable", isError = true)
            logMemoryStats("native library load failure")
        } else if (probeAll) {
            log("probe-all: first loadable variant was $loadedVariant - its natives are the ones in use")
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
        log(
            "startTunnel: natives in use are from variant ${loadedVariant ?: "none"} " +
                "(hev's TProxy natives were registered at its first successful load)"
        )
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

    /**
     * Records memory state at the moment of a native load failure - the
     * "Out of memory" in a dlopen error is often misleading (an old-linker
     * RELRO failure), so these numbers tell us whether the process really was
     * near its limits at that instant.
     */
    private fun logMemoryStats(context: String) {
        try {
            val rt = Runtime.getRuntime()
            val javaHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val javaMaxMb = rt.maxMemory() / (1024 * 1024)
            val nativeAllocMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
            val nativeSizeMb = Debug.getNativeHeapSize() / (1024 * 1024)
            val pssMb = Debug.getPss() / 1024
            log(
                "memory at $context: javaHeap=${javaHeapMb}MB/javaMax=${javaMaxMb}MB " +
                    "nativeHeap=${nativeAllocMb}MB/nativeReserved=${nativeSizeMb}MB " +
                    "appPss=${pssMb}MB",
                isError = true
            )
        } catch (t: Throwable) {
            log("memory stats unavailable: ${t.message}", isError = true)
        }
    }

    private fun log(message: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, message) else Log.i(TAG, message)
        TribalVpnService.appendLog(null, message, isError)
    }

    // --- JNI externs; implemented in app/src/main/cpp/tunnel_bridge.cpp ---
    private external fun nativeStartUdpGateway(tunFd: Int, udpgwAddr: String, udpgwPort: Int): Int
    private external fun nativeStopUdpGateway()
}
