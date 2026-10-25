package com.tribal.vpn.xray

import android.content.Context
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * Single-instance wrapper around the pinned Xray engine artifact
 * (`libv2ray.aar` from 2dust/AndroidLibXrayLite v26.10.21, whose go.mod pins
 * Xray-core v1.260327.1-0.20261021115930-1b1647cd9ddf; lib LGPL-3.0, core
 * MPL-2.0). Nothing here is guessed - the API surface below was read from the
 * binding's own Go sources (libv2ray_main.go, libv2ray_android.go) and from
 * v2rayNG's verified usage (core/CoreServiceManager.kt):
 *
 *  - `Seq.setContext(ctx)` then `Libv2ray.initCoreEnv(assetPath, key)` (once)
 *  - `Libv2ray.newCoreController(CoreCallbackHandler) -> CoreController`
 *  - `controller.startLoop(configJson, tunFd)` - errors are thrown
 *    synchronously ("config error", "core init failed", "startup failed") and
 *    the core cleans up after itself on a failed start.
 *  - `controller.stopLoop()`, `controller.isRunning` (gomobile exposes the
 *    exported IsRunning field as a getter).
 *
 * tunFd is 0 - "do not use TUN". That is not a compromise: hev-socks5-tunnel
 * owns tun0 (exactly like v2rayNG, which forces 0 in hev-tun mode), and the
 * core only needs to answer on its loopback SOCKS5 inbound.
 *
 * SOCKET PROTECTION: this binding exposes no per-fd protect callback. Instead
 * TribalVpnService excludes the app's OWN uid from the VPN
 * (Builder.addDisallowedApplication(packageName)) whenever the Xray engine is
 * active, so the core's outbound sockets - and its own DNS resolution of the
 * server address - travel the underlying network directly and can never be
 * routed back into the tun. Verified against v2rayNG's CoreVpnService, which
 * does exactly this for its own package id.
 *
 * Lifecycle note: `startLoop` is a NO-OP that returns success when the core is
 * already running, so [start] always stops a previous instance first. Without
 * that, a second connect with a different config would silently keep running
 * the OLD config.
 */
object XrayEngine {

    private const val TAG = "TribalXrayEngine"

    private val lock = Any()

    @Volatile
    private var controller: CoreController? = null

    @Volatile
    private var envReady = false

    /** True only when the core reports itself running. Never cached/faked. */
    val isRunning: Boolean
        get() = try {
            controller?.isRunning == true
        } catch (_: Throwable) {
            false
        }

    /** Real core version string ("Lib vNN, Xray-core vX.Y.Z..."), or null. */
    fun version(): String? = try {
        Libv2ray.checkVersionX()
    } catch (_: Throwable) {
        null
    }

    /**
     * One-time process-wide environment setup. Must run before [start].
     *
     * @param xudpBasekey stable per-install random key for Xray's XUDP
     *   session-id encryption (`xray.xudp.basekey`); callers persist it.
     * @throws IllegalStateException when the engine library cannot be loaded
     *   (e.g. a local build without the pinned AAR) - surfaced honestly
     *   instead of a silent fallback to the SSH engine.
     */
    fun init(
        context: Context,
        xudpBasekey: String,
        onLog: (plain: String?, technical: String?, isError: Boolean) -> Unit
    ) {
        synchronized(lock) {
            if (envReady) return
            try {
                Seq.setContext(context.applicationContext)
                // assetPath = filesDir: geo assets would be picked up from
                // here (none are shipped - see XrayConfigManager's notes).
                Libv2ray.initCoreEnv(context.filesDir.absolutePath, xudpBasekey)
                envReady = true
                onLog(
                    null,
                    "Xray engine environment initialised (${version() ?: "version unavailable"})",
                    false
                )
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "Xray engine library unavailable in this build " +
                        "(${t.javaClass.simpleName}) - libv2ray.aar is missing or unloadable, " +
                        "so the Xray engine cannot start",
                    t
                )
            }
        }
    }

    /**
     * Starts (or restarts) the core with [runtimeConfigJson].
     *
     * @throws IllegalStateException on any failure - after this throws, nothing
     *   is running, and the caller must abort the connection (close tun0,
     *   stop the relay) rather than claim Connected.
     */
    @Throws(IllegalStateException::class)
    fun start(
        runtimeConfigJson: String,
        onLog: (plain: String?, technical: String?, isError: Boolean) -> Unit
    ) {
        synchronized(lock) {
            val core = controller ?: createController(onLog).also { controller = it }

            if (isRunning) {
                onLog(null, "Xray engine: stopping the previous core instance before restart", false)
                try {
                    core.stopLoop()
                } catch (t: Throwable) {
                    onLog(null, "Xray engine: stopLoop before restart failed: ${t.javaClass.simpleName}", true)
                }
            }

            try {
                // tunFd = 0: hev-socks5-tunnel owns tun0; the core exposes
                // only its loopback SOCKS5 inbound.
                core.startLoop(runtimeConfigJson, 0)
            } catch (t: Throwable) {
                // The core's own error text is the actionable diagnostic
                // (e.g. "config error: ...", "startup failed: ..."), but it is
                // passed through the first-line cap so nothing bulky - and no
                // multi-line config echo - can ever reach the log.
                throw IllegalStateException(
                    "Xray core failed to start: ${firstLineCapped(t.message ?: t.javaClass.simpleName)}",
                    t
                )
            }

            if (!core.isRunning) {
                // startLoop returned without throwing but the core is not
                // running: treat exactly like a failure. No fake Connected.
                throw IllegalStateException("Xray core did not report running after start succeeded")
            }
            onLog(null, "Xray core running (startLoop returned, isRunning=true)", false)
        }
    }

    /** Stops the core when running. Never throws; failures are logged. */
    fun stop(onLog: (plain: String?, technical: String?, isError: Boolean) -> Unit) {
        synchronized(lock) {
            val core = controller ?: return
            try {
                if (core.isRunning) {
                    core.stopLoop()
                    onLog(null, "Xray core stopped (stopLoop returned, isRunning=${core.isRunning})", false)
                }
            } catch (t: Throwable) {
                onLog(null, "Xray engine: stopLoop failed: ${t.javaClass.simpleName}: ${firstLineCapped(t.message ?: "")}", true)
            }
        }
    }

    private fun createController(
        onLog: (plain: String?, technical: String?, isError: Boolean) -> Unit
    ): CoreController = try {
        Libv2ray.newCoreController(CoreCallback(onLog))
    } catch (t: Throwable) {
        throw IllegalStateException(
            "Xray engine could not be created (${t.javaClass.simpleName}) - " +
                "libv2ray.aar is missing or unloadable",
            t
        )
    }

    /** Callbacks fire on core threads; the log sink is the service's. */
    private class CoreCallback(
        private val onLog: (plain: String?, technical: String?, isError: Boolean) -> Unit
    ) : CoreCallbackHandler {
        override fun startup(): Long {
            onLog(null, "Xray core callback: startup", false)
            return 0
        }

        override fun shutdown(): Long {
            onLog(null, "Xray core callback: shutdown", false)
            return 0
        }

        override fun onEmitStatus(l: Long, s: String?): Long {
            onLog(null, "Xray core status: ${s ?: ""}", false)
            return 0
        }
    }

    /**
     * Keeps only the first line, capped. Used for third-party error strings so
     * a multi-line message (which could quote configuration) never lands in
     * the durable log whole.
     */
    internal fun firstLineCapped(message: String, limit: Int = 160): String {
        val firstLine = message.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstLine.length <= limit) firstLine else firstLine.take(limit) + "..."
    }
}
