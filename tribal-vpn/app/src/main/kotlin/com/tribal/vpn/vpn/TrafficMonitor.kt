package com.tribal.vpn.vpn

import android.net.TrafficStats
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads REAL traffic counters from the OS - Android's TrafficStats API,
 * scoped to this app's UID (Process.myUid()). This reflects actual bytes
 * that have moved through the tun interface, not a simulated/hardcoded value.
 *
 * TrafficStats.getUidRxBytes() / getUidTxBytes() are cumulative since last
 * device boot, so we snapshot a baseline at connect time and diff from there.
 *
 * Speed (Mbps) is derived by sampling the delta between two points in time -
 * it is NOT a fixed display value.
 */
class TrafficMonitor {

    data class Snapshot(
        val bytesReceived: Long,   // since connection start
        val bytesSent: Long,       // since connection start
        val downloadMbps: Double,  // instantaneous, derived from last sample interval
        val uploadMbps: Double,
        val connectedSince: Long?  // epoch millis, null if not connected
    )

    private val _snapshot = MutableStateFlow(
        Snapshot(bytesReceived = 0, bytesSent = 0, downloadMbps = 0.0, uploadMbps = 0.0, connectedSince = null)
    )
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private var baselineRx = 0L
    private var baselineTx = 0L
    private var lastSampleTime = 0L
    private var lastSampleRx = 0L
    private var lastSampleTx = 0L
    private var connectedSince: Long? = null

    /** Call exactly once when the tunnel actually establishes. */
    fun start() {
        val uid = Process.myUid()
        baselineRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
        baselineTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
        lastSampleRx = baselineRx
        lastSampleTx = baselineTx
        lastSampleTime = System.currentTimeMillis()
        connectedSince = lastSampleTime
        _snapshot.value = Snapshot(0, 0, 0.0, 0.0, connectedSince)
    }

    /** Call periodically (e.g. every 1s from a coroutine ticker) while connected. */
    fun sample() {
        val uid = Process.myUid()
        val now = System.currentTimeMillis()
        val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
        val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)

        val elapsedSeconds = ((now - lastSampleTime).coerceAtLeast(1)) / 1000.0
        val deltaRxBits = (rx - lastSampleRx).coerceAtLeast(0) * 8.0
        val deltaTxBits = (tx - lastSampleTx).coerceAtLeast(0) * 8.0

        val downloadMbps = (deltaRxBits / elapsedSeconds) / 1_000_000.0
        val uploadMbps = (deltaTxBits / elapsedSeconds) / 1_000_000.0

        lastSampleRx = rx
        lastSampleTx = tx
        lastSampleTime = now

        _snapshot.value = Snapshot(
            bytesReceived = (rx - baselineRx).coerceAtLeast(0),
            bytesSent = (tx - baselineTx).coerceAtLeast(0),
            downloadMbps = downloadMbps,
            uploadMbps = uploadMbps,
            connectedSince = connectedSince
        )
    }

    /** Call when the tunnel disconnects. */
    fun stop() {
        connectedSince = null
        _snapshot.value = Snapshot(0, 0, 0.0, 0.0, null)
    }
}
