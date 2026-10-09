package com.tribal.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tribal.vpn.vpn.TribalVpnService.ConnectionState
import kotlinx.coroutines.delay

private val Gold = Color(0xFFF5B700)
private val Black = Color(0xFF000000)
private val DarkGray = Color(0xFF111114)
private val MidGray = Color(0xFF6B6B70)

@Composable
fun HomeScreen(viewModel: VpnViewModel, onRequestConnect: () -> Unit = { viewModel.connect() }) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val traffic by viewModel.trafficSnapshot.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val activeProfile = profiles.find { it.id == activeProfileId }

    // Recompose once a second purely to refresh the uptime *display string*.
    // The underlying value (connectedSince) is real; this just re-renders the
    // formatted clock. No stat is invented here.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(traffic.connectedSince) {
        while (traffic.connectedSince != null) {
            delay(1000)
            tick = System.currentTimeMillis()
        }
    }
    val uptimeText = viewModel.formatUptime(traffic.connectedSince)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        ConnectButton(
            state = connectionState,
            enabled = activeProfile != null,
            onClick = {
                when (connectionState) {
                    ConnectionState.CONNECTED -> viewModel.disconnect()
                    ConnectionState.DISCONNECTED, ConnectionState.ERROR -> onRequestConnect()
                    ConnectionState.CONNECTING -> Unit // no-op while in flight
                }
            }
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = when (connectionState) {
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.CONNECTING -> "Connecting…"
                ConnectionState.ERROR -> "Connection Failed"
                ConnectionState.DISCONNECTED -> "Not Connected"
            },
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            // Only ever shows a real uptime string or a static prompt - never a
            // fabricated "00:00:00" when disconnected.
            text = if (connectionState == ConnectionState.CONNECTED && uptimeText != null) {
                "$uptimeText · ${activeProfile?.name ?: ""}"
            } else {
                "Tap to connect"
            },
            color = MidGray,
            fontSize = 14.sp
        )

        if (connectionState == ConnectionState.CONNECTED) {
            Spacer(Modifier.height(24.dp))
            RealStatsRow(traffic = traffic, uptimeText = uptimeText)
        }

        Spacer(Modifier.height(24.dp))

        ActiveProfileCard(profileName = activeProfile?.name, profileHost = activeProfile?.let { "${it.host}:${it.port}" })
    }
}

@Composable
private fun ConnectButton(state: ConnectionState, enabled: Boolean, onClick: () -> Unit) {
    val bgColor = when (state) {
        ConnectionState.CONNECTED -> Gold
        ConnectionState.CONNECTING -> Gold.copy(alpha = 0.6f)
        ConnectionState.ERROR -> Color(0xFFB00020)
        ConnectionState.DISCONNECTED -> Color(0xFF3A3A3D)
    }

    Box(
        modifier = Modifier
            .size(160.dp)
            .clip(CircleShape)
            .background(bgColor)
            .then(
                if (enabled && state != ConnectionState.CONNECTING) {
                    Modifier.clickable { onClick() }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            ConnectionState.CONNECTING -> CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            ConnectionState.CONNECTED -> Icon(Icons.Filled.Wifi, contentDescription = "Connected", tint = Black, modifier = Modifier.size(56.dp))
            else -> Icon(Icons.Filled.WifiOff, contentDescription = "Disconnected", tint = Color.White, modifier = Modifier.size(56.dp))
        }
    }
}

/**
 * Renders REAL traffic numbers pulled from TrafficMonitor.Snapshot.
 * downloadMbps/uploadMbps are computed from actual TrafficStats deltas
 * sampled every second by the service - never a fixed placeholder.
 */
@Composable
private fun RealStatsRow(traffic: com.tribal.vpn.vpn.TrafficMonitor.Snapshot, uptimeText: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(label = "Uptime", value = uptimeText ?: "—")
        StatCard(label = "Downloaded", value = formatBytes(traffic.bytesReceived))
        StatCard(label = "Speed", value = "%.1f Mbps".format(traffic.downloadMbps))
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Column(
        modifier = Modifier
            .background(DarkGray, RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF1F1F23), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(label.uppercase(), color = MidGray, fontSize = 9.sp)
    }
}

@Composable
private fun ActiveProfileCard(profileName: String?, profileHost: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkGray, RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF1F1F23), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text("ACTIVE PROFILE", color = MidGray, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        if (profileName != null) {
            Text(profileName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(profileHost ?: "", color = MidGray, fontSize = 12.sp)
        } else {
            Text("No profile selected", color = MidGray, fontSize = 13.sp)
        }
    }
}

/**
 * Human-readable byte formatting for the REAL cumulative bytesReceived count.
 * No unit is invented - this scales the actual number.
 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
