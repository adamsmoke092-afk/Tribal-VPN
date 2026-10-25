package com.tribal.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.tribal.vpn.MainActivity
import com.tribal.vpn.data.VpnConfig
import com.tribal.vpn.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real VpnService implementation. Connection status here reflects the ACTUAL
 * state of the tun interface / SSH session - it is not toggled cosmetically
 * by the UI. The UI observes ConnectionState via the companion StateFlow and
 * only ever reflects what this service reports.
 */
class TribalVpnService : VpnService() {

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    data class LogEntry(val timestampMillis: Long, val plainMessage: String?, val technicalMessage: String?, val isError: Boolean = false)

    companion object {
        private const val CHANNEL_ID = "tribal_vpn_channel"
        private const val NOTIFICATION_ID = 1

        // Shared, single source of truth for UI to observe. In a real app this
        // would go through a bound Service connection or a repository layer;
        // exposed simply here for clarity.
        private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
        val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

        private const val MAX_LOG_ENTRIES = 100

        fun appendLog(plain: String?, technical: String?, isError: Boolean = false) {
            val entry = LogEntry(System.currentTimeMillis(), plain, technical, isError)
            _logs.value = (_logs.value + entry).takeLast(MAX_LOG_ENTRIES)
            // Durable mirror: fsync'd to disk so the last line before a hard
            // death (native crash / system kill) is never lost.
            Diagnostics.appendLogLine(entry.timestampMillis, plain, technical, isError)
        }

        fun clearLogs() {
            _logs.value = emptyList()
        }

        // Real traffic data, readable by the UI layer without a bound Service.
        // A future refactor should replace this with proper bindService() +
        // a Messenger/AIDL interface; exposed as a StateFlow here for simplicity
        // while keeping the underlying numbers 100% real (see TrafficMonitor).
        val trafficSnapshotFlow: StateFlow<TrafficMonitor.Snapshot>
            get() = sharedTrafficMonitor.snapshot

        private val sharedTrafficMonitor = TrafficMonitor()
    }

    // The service uses the same shared instance so ticks are visible to the UI.
    val trafficMonitor: TrafficMonitor
        get() = sharedTrafficMonitor

    private var vpnInterface: ParcelFileDescriptor? = null
    private var activeSshTunnel: com.tribal.vpn.ssh.SshTunnelService? = null
    // True only while the Xray engine owns the active connection. The two
    // engines never run at the same time for one profile; teardown uses this
    // flag to stop exactly the engine that was started.
    private var activeXrayEngine = false
    private var serviceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trafficTickerJob: Job? = null
    private var lastConnectedConfigId: String? = null

    private val appSettings by lazy { com.tribal.vpn.data.AppSettingsRepository(applicationContext) }

    private val networkMonitor by lazy {
        NetworkChangeMonitor(
            context = applicationContext,
            scope = scope,
            isAutoReconnectEnabled = { appSettings.autoReconnect.value },
            currentConnectionState = { _connectionState.value },
            onReconnectRequested = {
                val id = lastConnectedConfigId
                if (id != null) connect(id)
            },
            onLog = { plain, technical, isError -> appendLog(plain, technical, isError) }
        )
    }

    override fun onCreate() {
        super.onCreate()
        networkMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_CONNECT -> {
                val configId = intent.getStringExtra(EXTRA_CONFIG_ID)
                if (configId != null) connect(configId)
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    private fun connect(configId: String) {
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING) return

        _connectionState.value = ConnectionState.CONNECTING
        appendLog("Connecting…", "Starting VPN service for config=$configId")
        startForeground(NOTIFICATION_ID, buildNotification(ConnectionState.CONNECTING))

        serviceJob = scope.launch {
            try {
                // NOTE: Real config lookup happens via injected ConfigRepository
                // in the full implementation. This method establishes the tun
                // interface only - SSH auth (Phase 3) and tunnel wiring (Phase 4/5)
                // hook in via establishSshTunnel() / bindTunToSocks() below.

                val config = requireConfigLookup(configId)
                    ?: throw IllegalStateException("Unknown config id: $configId")

                // --- Phase 3: open SSH session + local SOCKS5 forward ---
                // MapDNS: validate the stored pool honestly - a corrupt
                // profile fails the connect with a clear message, never a
                // silent fix or default substitution.
                var mapDnsNetwork = 0
                var mapDnsNetmask = 0
                if (config.enableMapDns) {
                    if (!config.enableDns) {
                        throw IllegalStateException(
                            "MapDNS is enabled but Custom DNS is off - edit the profile"
                        )
                    }
                    mapDnsNetwork = VpnConfig.parseIPv4(config.mapDnsNetwork)
                        ?: throw IllegalStateException(
                            "MapDNS pool base '${config.mapDnsNetwork}' is not a valid IPv4 - edit the profile"
                        )
                    mapDnsNetmask = VpnConfig.parseNetmask(config.mapDnsNetmask)
                        ?: throw IllegalStateException(
                            "MapDNS netmask '${config.mapDnsNetmask}' is not a contiguous mask - edit the profile"
                        )
                }
                // UDP Custom port range: validated honestly like the mapdns
                // pool - a corrupt profile fails the connect with a clear
                // message, never a silent fix. The direct-route probe
                // samples from it; the future UDP client picks its port
                // from it.
                val udpPortRange = VpnConfig.parsePortRange(config.udpPortRange)
                    ?: throw IllegalStateException(
                        "UDP port range '${config.udpPortRange}' is not a port or lo-hi range - edit the profile"
                    )
                // --- Engine selection: exactly ONE engine per connection ---
                // The engine decides only which LOCAL SOCKS5 endpoint the
                // packet relay forwards into - the SSH engine's own listener,
                // or the Xray core's app-managed inbound. tun0,
                // hev-socks5-tunnel, traffic stats and reconnect logic below
                // are shared and unchanged for both engines.
                val isXray = config.engine == VpnConfig.VpnEngine.XRAY
                val localSocksPort: Int
                if (isXray) {
                    // Inbound port chosen by the kernel (bind 0, read back,
                    // release) - never a hardcoded "usually free" port.
                    val xraySocksPort = com.tribal.vpn.xray.XrayConfigManager.allocateLoopbackPort()
                    val runtimeConfig = com.tribal.vpn.xray.XrayConfigManager.buildRuntimeConfig(
                        config.xrayConfig,
                        xraySocksPort
                    )
                    // Diagnostics carry structure only (protocols/counts) -
                    // never config text, which holds UUIDs and passwords.
                    appendLog(
                        "Starting Xray engine...",
                        "engine=xray inbound=127.0.0.1:$xraySocksPort config[" +
                            com.tribal.vpn.xray.XrayConfigManager.summarizeForLog(config.xrayConfig) + "]"
                    )
                    com.tribal.vpn.xray.XrayEngine.init(
                        context = applicationContext,
                        xudpBasekey = appSettings.xrayXudpBasekey(),
                        onLog = { plain, technical, isError -> appendLog(plain, technical, isError) }
                    )
                    // Throws on any startup failure and leaves nothing
                    // running - the catch block below then aborts the whole
                    // connection honestly (no Connected, tun0 closed).
                    com.tribal.vpn.xray.XrayEngine.start(
                        runtimeConfigJson = runtimeConfig,
                        onLog = { plain, technical, isError -> appendLog(plain, technical, isError) }
                    )
                    activeXrayEngine = true
                    localSocksPort = xraySocksPort
                } else {
                    val sshConfig = com.tribal.vpn.ssh.SshConfig(
                        host = config.host,
                        port = config.port,
                        username = config.username,
                        password = if (config.authMethod == VpnConfig.AuthMethod.PASSWORD) config.password else null,
                        privateKeyAlias = if (config.authMethod == VpnConfig.AuthMethod.KEY) config.privateKeyAlias else null,
                        mapDnsNetwork = mapDnsNetwork,
                        mapDnsNetmask = mapDnsNetmask,
                        udpgwPort = config.udpGatewayPort,
                        udpPortRange = udpPortRange,
                        enableUdp = config.enableUdp,
                        dnsAddress = config.preferredDns.trim()
                    )
                    val sshTunnel = com.tribal.vpn.ssh.SshTunnelService(
                        config = sshConfig,
                        protectSocket = { socket -> protect(socket) },
                        onLog = { plain, technical, isError -> appendLog(plain, technical, isError) }
                    )
                    sshTunnel.connect()
                    activeSshTunnel = sshTunnel
                    // The port the SOCKS listener ACTUALLY bound to (kernel-
                    // assigned, since socksBindPort is 0).
                    localSocksPort = sshTunnel.socksPort
                }

                // --- Phase 1: establish tun interface ---
                val builder = Builder()
                    .setSession("Tribal VPN")
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .setMtu(config.mtu)

                if (config.enableDns) {
                    builder.addDnsServer(config.preferredDns)
                    if (config.enableMapDns) {
                        // mapdns intercepts exactly ONE address:port, so the
                        // alternate DNS would bypass interception and die as
                        // UDP - push only the intercepted one.
                        if (config.alternateDns.isNotBlank() &&
                            config.alternateDns.trim() != config.preferredDns.trim()
                        ) {
                            appendLog(
                                null,
                                "MapDNS active: alternate DNS ${config.alternateDns} not pushed " +
                                    "(only one interception address is supported)",
                                false
                            )
                        }
                    } else if (config.alternateDns.isNotBlank()) {
                        builder.addDnsServer(config.alternateDns)
                    }
                }

                // Socket protection for the Xray engine. The core runs
                // in-process (libv2ray) and that binding exposes no per-fd
                // protect callback, so instead of per-socket protect() we
                // exclude THIS app's own uid from the VPN: the core's outbound
                // sockets - and its own DNS resolution of the server address -
                // then travel the underlying network directly and can never be
                // routed back into tun0. Same mechanism v2rayNG's
                // CoreVpnService uses for its own package id. The SSH engine
                // keeps its protect()-per-socket path, unchanged.
                if (isXray) {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                        // Without the exclusion the core's traffic would loop
                        // back into tun0 - refuse rather than pretend.
                        throw IllegalStateException(
                            "Could not exclude this app's uid from the VPN (addDisallowedApplication " +
                                "failed) - the Xray core's outbound traffic would loop back into tun0, " +
                                "so the connection is aborted",
                            e
                        )
                    }
                    appendLog(
                        null,
                        "Xray engine: app uid excluded from the VPN so core outbound " +
                            "traffic reaches the server directly (never through tun0)"
                    )
                }

                // Bypass list: these apps keep the phone's normal connection
                // (VpnService.Builder.addDisallowedApplication). An unknown
                // package is logged honestly - never silently ignored and
                // never faked as applied.
                val bypass = appSettings.bypassApps.value
                if (bypass.isNotEmpty()) {
                    for (pkg in bypass) {
                        try {
                            builder.addDisallowedApplication(pkg)
                        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                            appendLog(
                                null,
                                "Bypass app '$pkg' is not installed - skipped",
                                isError = true
                            )
                        }
                    }
                    appendLog(
                        null,
                        "Bypassing ${bypass.size} app(s) via addDisallowedApplication: " +
                            bypass.joinToString()
                    )
                }

                vpnInterface = builder.establish()

                if (vpnInterface == null) {
                    throw IllegalStateException("Failed to establish VPN interface (permission or config issue)")
                }

                // --- Phase 4: wire tun fd -> hev-socks5-tunnel -> SSH SOCKS5 proxy ---
                // See NativeTunnelBridge.kt: false here means the native engine
                // really failed to start. We do NOT report CONNECTED as if
                // traffic is flowing when it isn't - the log makes the real
                // state explicit instead.
                val tunnelStarted = NativeTunnelBridge.startTunnel(
                    tunFd = vpnInterface!!.fd,
                    socksAddr = "127.0.0.1",
                    // The port the ACTIVE engine's SOCKS5 endpoint really
                    // bound to (kernel-assigned for both engines) - not a
                    // requested default, which is only the "any free port"
                    // sentinel.
                    socksPort = localSocksPort,
                    filesDir = filesDir.absolutePath,
                    // SSH: the app's SOCKS5 server has no UDP ASSOCIATE, so
                    // hev frames UDP over TCP and the udpgw client bridges it.
                    // Xray: the core's socks inbound supports standard UDP
                    // ASSOCIATE (settings.udp=true) - no udpgw needed.
                    udpMode = if (isXray) "udp" else "tcp",
                    mapDns = if (config.enableMapDns) {
                        MapDnsParams(
                            dnsAddress = config.preferredDns.trim(),
                            network = config.mapDnsNetwork.trim(),
                            netmask = config.mapDnsNetmask.trim()
                        )
                    } else null
                )
                if (!tunnelStarted) {
                    appendLog(
                        "Connection failed: the packet relay did not start",
                        "NativeTunnelBridge.startTunnel() returned false — the native packet " +
                            "relay (hev-socks5-tunnel) did not start, so no traffic can flow " +
                            "end-to-end. Aborting instead of reporting Connected (no fabricated " +
                            "state) and closing tun0 so the phone keeps its normal internet. " +
                            "The startTunnel trace is in the entries above.",
                        isError = true
                    )
                    // A tun0 without a live relay would blackhole the phone's
                    // traffic - fail honestly and let the catch block tear
                    // everything down.
                    throw IllegalStateException("Native packet relay failed to start")
                }

                // SSH engine only: UDP Custom does not go through a native
                // udpgw stub - the Kotlin udpgw client inside SshTunnelService
                // (armed during sshTunnel.connect() when the profile enables
                // it) bridges hev's FWD_UDP flows to the VPS udpgw. The Xray
                // engine needs none of it (standard SOCKS5 UDP ASSOCIATE).

                trafficMonitor.start()
                startTrafficTicker()

                lastConnectedConfigId = configId
                networkMonitor.resetBackoff()

                _connectionState.value = ConnectionState.CONNECTED
                // Reached only after the engine reported running AND the
                // native relay accepted its config - never before.
                appendLog(
                    "Connected",
                    "engine=${if (isXray) "xray" else "ssh"}, tun0 established, mtu=${config.mtu}"
                )
                updateNotification(ConnectionState.CONNECTED)

            } catch (e: CancellationException) {
                // User-initiated disconnect cancelled this job - not a failure.
                return@launch
            } catch (e: Throwable) {
                // Throwable, not Exception: Errors (NoClassDefFoundError,
                // UnsatisfiedLinkError, OutOfMemoryError, ...) must surface as
                // an honest ERROR state, not silently kill the app.
                _connectionState.value = ConnectionState.ERROR
                appendLog("Connection failed: ${e.message}", e.stackTraceToString(), isError = true)
                updateNotification(ConnectionState.ERROR)
                // Teardown of a PARTIAL startup, safe order: stop the relay
                // first (nothing forwards frames any more), then whichever
                // engine did start, then close tun0 last so the phone gets
                // its normal internet back.
                NativeTunnelBridge.stopTunnel()
                activeSshTunnel?.disconnect()
                activeSshTunnel = null
                stopXrayEngineIfActive()
                cleanupInterface()
            }
        }
    }

    private fun disconnect() {
        lastConnectedConfigId = null // user-initiated: don't let network changes resurrect this
        serviceJob?.cancel()
        trafficTickerJob?.cancel()
        NativeTunnelBridge.stopTunnel()
        activeSshTunnel?.disconnect()
        activeSshTunnel = null
        stopXrayEngineIfActive()
        cleanupInterface()
        trafficMonitor.stop()
        _connectionState.value = ConnectionState.DISCONNECTED
        appendLog("Disconnected", "VPN connection closed by user")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Stops the Xray core if this service started it. Idempotent, safe to call
     * from every teardown path (disconnect, failed connect, onDestroy): the
     * flag is cleared before the stop call so a second teardown cannot stop a
     * core some later connection has since started.
     */
    private fun stopXrayEngineIfActive() {
        if (!activeXrayEngine) return
        activeXrayEngine = false
        com.tribal.vpn.xray.XrayEngine.stop { plain, technical, isError ->
            appendLog(plain, technical, isError)
        }
    }

    private fun cleanupInterface() {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        } finally {
            vpnInterface = null
        }
    }

    /** Samples real TrafficStats every second while connected. */
    private fun startTrafficTicker() {
        trafficTickerJob?.cancel()
        trafficTickerJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                trafficMonitor.sample()
                delay(1000)
            }
        }
    }

    // Placeholder for real repository-backed lookup; wired in MainActivity/DI in full build.
    private fun requireConfigLookup(id: String): VpnConfig? = pendingConfigProvider?.invoke(id)

    override fun onRevoke() {
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        networkMonitor.stop()
        trafficTickerJob?.cancel()
        serviceJob?.cancel()
        // Service destruction must not leave the in-process Xray core running
        // once the VPN itself is gone.
        stopXrayEngineIfActive()
        cleanupInterface()
        super.onDestroy()
    }

    // --- Notification plumbing ---

    private fun buildNotification(state: ConnectionState): Notification {
        createChannelIfNeeded()
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = when (state) {
            ConnectionState.CONNECTED -> "Connected"
            ConnectionState.CONNECTING -> "Connecting…"
            ConnectionState.ERROR -> "Connection error"
            ConnectionState.DISCONNECTED -> "Disconnected"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tribal VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .setOngoing(state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING)
            .build()
    }

    private fun updateNotification(state: ConnectionState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    object Actions {
        const val CONNECT = "com.tribal.vpn.action.CONNECT"
        const val DISCONNECT = "com.tribal.vpn.action.DISCONNECT"
    }
}

// Top-level constants referenced above (kept here to avoid a circular companion reference issue)
const val ACTION_CONNECT = "com.tribal.vpn.action.CONNECT"
const val ACTION_DISCONNECT = "com.tribal.vpn.action.DISCONNECT"
const val EXTRA_CONFIG_ID = "config_id"

// Injected by MainActivity at process start so the service can resolve a config id
// without a full DI framework. Replace with Hilt/Koin in production.
var pendingConfigProvider: ((String) -> VpnConfig?)? = null
