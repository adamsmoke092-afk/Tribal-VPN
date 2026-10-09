package com.tribal.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.*

/**
 * Watches for REAL network transitions via ConnectivityManager (WiFi<->LTE,
 * network lost/regained) and triggers a reconnect with exponential backoff.
 *
 * This does not simulate connectivity - onAvailable/onLost fire only when
 * Android's connectivity subsystem actually reports a change. Backoff timing
 * (1s, 2s, 4s, ... capped at 60s) matches the roadmap's Phase 7 spec.
 */
class NetworkChangeMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isAutoReconnectEnabled: () -> Boolean,
    private val currentConnectionState: () -> TribalVpnService.ConnectionState,
    private val onReconnectRequested: () -> Unit,
    private val onLog: (plain: String?, technical: String?, isError: Boolean) -> Unit
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var backoffJob: Job? = null
    private var currentBackoffMs = INITIAL_BACKOFF_MS
    private var lastKnownNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val isNewNetwork = lastKnownNetwork != null && lastKnownNetwork != network
            lastKnownNetwork = network

            if (isNewNetwork && currentConnectionState() == TribalVpnService.ConnectionState.CONNECTED) {
                onLog("Network changed, reconnecting…", "ConnectivityManager.onAvailable: network=$network", false)
                scheduleReconnect()
            } else if (isNewNetwork && currentConnectionState() == TribalVpnService.ConnectionState.ERROR) {
                // We were down due to a network error - a new network appearing
                // is exactly the trigger to retry.
                scheduleReconnect()
            }
        }

        override fun onLost(network: Network) {
            if (network == lastKnownNetwork) {
                onLog(null, "ConnectivityManager.onLost: network=$network", true)
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Available for future use (e.g. detecting metered vs unmetered to
            // adjust MTU/behavior) - intentionally not acted on yet to avoid
            // inventing behavior the roadmap didn't ask for.
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: IllegalArgumentException) {
            // Callback was already unregistered - safe to ignore.
        }
        backoffJob?.cancel()
        currentBackoffMs = INITIAL_BACKOFF_MS
    }

    /** Call after a successful reconnect to reset the backoff clock. */
    fun resetBackoff() {
        currentBackoffMs = INITIAL_BACKOFF_MS
    }

    private fun scheduleReconnect() {
        if (!isAutoReconnectEnabled()) {
            onLog(null, "Auto-reconnect disabled by user setting — skipping", false)
            return
        }

        backoffJob?.cancel()
        backoffJob = scope.launch {
            delay(currentBackoffMs)
            onLog("Reconnecting…", "Retry after ${currentBackoffMs}ms backoff", false)
            onReconnectRequested()
            currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    companion object {
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}
