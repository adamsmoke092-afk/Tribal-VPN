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
                val sshConfig = com.tribal.vpn.ssh.SshConfig(
                    host = config.host,
                    port = config.port,
                    username = config.username,
                    password = if (config.authMethod == VpnConfig.AuthMethod.PASSWORD) config.password else null,
                    privateKeyAlias = if (config.authMethod == VpnConfig.AuthMethod.KEY) config.privateKeyAlias else null
                )
                val sshTunnel = com.tribal.vpn.ssh.SshTunnelService(
                    config = sshConfig,
                    protectSocket = { socket -> protect(socket) },
                    onLog = { plain, technical, isError -> appendLog(plain, technical, isError) }
                )
                sshTunnel.connect()
                activeSshTunnel = sshTunnel

                // --- Phase 1: establish tun interface ---
                val builder = Builder()
                    .setSession("Tribal VPN")
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .setMtu(config.mtu)

                if (config.enableDns) {
                    builder.addDnsServer(config.preferredDns)
                    if (config.alternateDns.isNotBlank()) {
                        builder.addDnsServer(config.alternateDns)
                    }
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
                    socksPort = sshConfig.socksBindPort
                )
                if (!tunnelStarted) {
                    appendLog(
                        "Tunnel interface is up, but packet routing is not active",
                        "NativeTunnelBridge.startTunnel() returned false — the native packet " +
                            "relay (hev-socks5-tunnel) failed to start. tun0 and the SSH " +
                            "SOCKS5 proxy are both live, but IP packets are not being relayed " +
                            "between them. See logcat (TribalTunnelBridge) and README_DATA_INTEGRITY.md.",
                        isError = true
                    )
                }

                // --- Phase 5: UDP gateway (only meaningful once Phase 4 relay is live) ---
                if (config.enableUdp && tunnelStarted) {
                    val udpStarted = NativeTunnelBridge.startUdpGateway(
                        tunFd = vpnInterface!!.fd,
                        udpgwAddr = "127.0.0.1",
                        udpgwPort = config.udpGatewayPort
                    )
                    if (!udpStarted) {
                        appendLog(
                            null,
                            "NativeTunnelBridge.startUdpGateway() returned false — badvpn-udpgw " +
                                "native library not yet vendored/compiled (Phase 5). UDP Custom " +
                                "toggle will have no effect until this is wired.",
                            isError = false
                        )
                    }
                }

                trafficMonitor.start()
                startTrafficTicker()

                lastConnectedConfigId = configId
                networkMonitor.resetBackoff()

                _connectionState.value = ConnectionState.CONNECTED
                appendLog("Connected", "tun0 established, mtu=${config.mtu}")
                updateNotification(ConnectionState.CONNECTED)

            } catch (e: Exception) {
                _connectionState.value = ConnectionState.ERROR
                appendLog("Connection failed: ${e.message}", e.stackTraceToString(), isError = true)
                updateNotification(ConnectionState.ERROR)
                NativeTunnelBridge.stopUdpGateway()
                NativeTunnelBridge.stopTunnel()
                activeSshTunnel?.disconnect()
                activeSshTunnel = null
                cleanupInterface()
            }
        }
    }

    private fun disconnect() {
        lastConnectedConfigId = null // user-initiated: don't let network changes resurrect this
        serviceJob?.cancel()
        trafficTickerJob?.cancel()
        NativeTunnelBridge.stopUdpGateway()
        NativeTunnelBridge.stopTunnel()
        activeSshTunnel?.disconnect()
        activeSshTunnel = null
        cleanupInterface()
        trafficMonitor.stop()
        _connectionState.value = ConnectionState.DISCONNECTED
        appendLog("Disconnected", "VPN connection closed by user")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
