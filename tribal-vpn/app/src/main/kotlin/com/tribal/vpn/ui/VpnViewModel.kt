package com.tribal.vpn.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tribal.vpn.data.AppSettingsRepository
import com.tribal.vpn.data.ConfigRepository
import com.tribal.vpn.data.VpnConfig
import com.tribal.vpn.vpn.ACTION_CONNECT
import com.tribal.vpn.vpn.ACTION_DISCONNECT
import com.tribal.vpn.vpn.EXTRA_CONFIG_ID
import com.tribal.vpn.vpn.TribalVpnService
import com.tribal.vpn.vpn.pendingConfigProvider
import kotlinx.coroutines.flow.*

/**
 * Exposes UI state derived ONLY from real sources:
 *  - connectionState:      TribalVpnService.connectionState (actual service state)
 *  - trafficSnapshot:      TribalVpnService.trafficMonitor (actual TrafficStats deltas)
 *  - logs:                 TribalVpnService.logs (actual lifecycle events)
 *  - profiles:             ConfigRepository (actual persisted data)
 *
 * Nothing here is a placeholder/simulated value. If the service isn't running,
 * traffic values are simply 0 / null - the UI must render that honestly rather
 * than showing invented numbers.
 */
class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ConfigRepository(application)
    private val settingsRepository = AppSettingsRepository(application)

    val profiles: StateFlow<List<VpnConfig>> = repository.profiles
    val activeProfileId: StateFlow<String?> = repository.activeProfileId

    val autoReconnect: StateFlow<Boolean> = settingsRepository.autoReconnect
    val requireBiometrics: StateFlow<Boolean> = settingsRepository.requireBiometrics

    fun setAutoReconnect(enabled: Boolean) = settingsRepository.setAutoReconnect(enabled)
    fun setRequireBiometrics(enabled: Boolean) = settingsRepository.setRequireBiometrics(enabled)

    val connectionState: StateFlow<TribalVpnService.ConnectionState> =
        TribalVpnService.connectionState

    val logs: StateFlow<List<TribalVpnService.LogEntry>> = TribalVpnService.logs

    // Real traffic stats, sourced directly from the running service's TrafficMonitor
    // via a bound connection. UI must handle connectedSince == null (not connected)
    // and zero byte counts (just connected, no data yet) as legitimate states -
    // never substitute a fake "6.2 Mbps" placeholder.
    val trafficSnapshot: StateFlow<com.tribal.vpn.vpn.TrafficMonitor.Snapshot> =
        TribalVpnService.trafficSnapshotFlow

    init {
        pendingConfigProvider = { id -> repository.getProfile(id) }
    }

    fun saveProfile(config: VpnConfig) = repository.saveProfile(config)

    fun deleteProfile(id: String) = repository.deleteProfile(id)

    fun setActiveProfile(id: String) = repository.setActiveProfile(id)

    fun connect() {
        val id = activeProfileId.value ?: return
        val config = repository.getProfile(id) ?: return
        if (!config.isValid()) return

        val intent = Intent(getApplication(), TribalVpnService::class.java).apply {
            action = ACTION_CONNECT
            putExtra(EXTRA_CONFIG_ID, id)
        }
        getApplication<Application>().startService(intent)
    }

    fun disconnect() {
        val intent = Intent(getApplication(), TribalVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        getApplication<Application>().startService(intent)
    }

    fun clearLogs() = TribalVpnService.clearLogs()

    /**
     * Formats real uptime from connectedSince. Returns null (not "00:00:00")
     * when there is no active connection - callers must handle the null case
     * explicitly rather than defaulting to a fake zero display.
     */
    fun formatUptime(connectedSinceMillis: Long?): String? {
        if (connectedSinceMillis == null) return null
        val elapsedSeconds = (System.currentTimeMillis() - connectedSinceMillis) / 1000
        val h = elapsedSeconds / 3600
        val m = (elapsedSeconds % 3600) / 60
        val s = elapsedSeconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
