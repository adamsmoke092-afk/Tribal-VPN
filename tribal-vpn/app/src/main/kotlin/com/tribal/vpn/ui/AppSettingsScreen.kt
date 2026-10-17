package com.tribal.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Gold = Color(0xFFF5B700)
private val Black = Color(0xFF000000)
private val DarkGray = Color(0xFF111114)
private val CardBorder = Color(0xFF1F1F23)
private val MidGray = Color(0xFF6B6B70)

/**
 * Every toggle here reads/writes AppSettingsRepository (real SharedPreferences)
 * via the ViewModel - flipping a switch and restarting the app must show the
 * same state, since this is not local Compose `remember` state.
 *
 * Exception: "Ignore battery optimization" is not a plain preference flip.
 * It reflects a real OS permission (PowerManager.isIgnoringBatteryOptimizations),
 * so this screen takes a callback to launch the actual system settings intent
 * rather than pretending a SharedPreferences write grants the exemption.
 */
@Composable
fun AppSettingsScreen(
    viewModel: VpnViewModel,
    isIgnoringBatteryOptimizations: Boolean,
    onRequestBatteryOptimizationExemption: () -> Unit,
    appVersion: String = "1.0.0"
) {
    val autoReconnect by viewModel.autoReconnect.collectAsStateWithLifecycle()
    val probeAllVariants by viewModel.probeAllVariants.collectAsStateWithLifecycle()
    val bypassApps by viewModel.bypassApps.collectAsStateWithLifecycle()
    var newBypassApp by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(20.dp)
    ) {
        Text("App Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        SettingsGroup {
            SettingsRow(
                icon = Icons.Filled.Refresh,
                title = "Auto-reconnect",
                subtitle = "Reconnect automatically on network change"
            ) {
                GoldSwitchStandalone(autoReconnect) { viewModel.setAutoReconnect(it) }
            }
            SettingsRow(
                icon = Icons.Filled.BatteryChargingFull,
                title = "Ignore battery optimization",
                subtitle = if (isIgnoringBatteryOptimizations)
                    "Granted — tunnel can stay alive in background"
                else
                    "Not granted — tap to open system settings"
            ) {
                // Reflects the REAL PowerManager state, queried by MainActivity.
                // Tapping while ungranted opens Android's actual exemption
                // dialog; the switch cannot be flipped on directly by the app,
                // since that permission cannot be self-granted.
                GoldSwitchStandalone(isIgnoringBatteryOptimizations) {
                    if (!isIgnoringBatteryOptimizations) onRequestBatteryOptimizationExemption()
                }
            }
            SettingsRow(
                icon = Icons.Filled.BugReport,
                title = "Probe all bridge variants",
                subtitle = "(Diagnostic) Try every bridge .so variant at startup and log each result. " +
                    "Off = stop at the first that loads. Variants that crashed the process are " +
                    "skipped until the app updates."
            ) {
                GoldSwitchStandalone(probeAllVariants) { viewModel.setProbeAllVariants(it) }
            }
            SettingsRow(
                icon = Icons.Filled.Lock,
                title = "Require biometrics",
                subtitle = "Not yet implemented — will lock saved passwords behind Face/Fingerprint"
            ) {
                // No fake toggle: nothing enforces this setting yet, so it must
                // not present itself as a working capability (the
                // no-fabricated-state rule in README_DATA_INTEGRITY.md).
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = MidGray.copy(alpha = 0.5f))
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsGroup {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp)
            ) {
                Text(
                    "Bypassed apps (excluded from the VPN)",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium
                )
                Text(
                    "These apps keep the phone's normal connection, applied via " +
                        "addDisallowedApplication when connecting. Unknown packages are " +
                        "logged, never silently dropped.",
                    color = MidGray, fontSize = 11.sp
                )
            }
            bypassApps.forEach { pkg ->
                SettingsRow(icon = Icons.Filled.Apps, title = pkg, subtitle = "") {
                    IconButton(onClick = { viewModel.setBypassApps(bypassApps - pkg) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove $pkg", tint = Color(0xFFCF4A4A))
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newBypassApp,
                    onValueChange = { newBypassApp = it },
                    placeholder = { Text("com.example.app", color = MidGray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Gold,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Gold
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val pkg = newBypassApp.trim()
                        if (pkg.isNotEmpty() && pkg !in bypassApps) {
                            viewModel.setBypassApps(bypassApps + pkg)
                        }
                        newBypassApp = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Black)
                ) {
                    Text("Add", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        SettingsGroup {
            SettingsRow(icon = Icons.Filled.Info, title = "About Tribal VPN", subtitle = "v$appVersion") {
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MidGray.copy(alpha = 0.5f))
            }
            SettingsRow(icon = Icons.Filled.MenuBook, title = "Setup guide", subtitle = "How to prepare your VPS") {
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MidGray.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkGray, RoundedCornerShape(16.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp)),
        content = content
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Black, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MidGray, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MidGray, fontSize = 11.sp)
        }
        trailing()
    }
}

@Composable
private fun GoldSwitchStandalone(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Black,
            checkedTrackColor = Gold,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = CardBorder
        )
    )
}
