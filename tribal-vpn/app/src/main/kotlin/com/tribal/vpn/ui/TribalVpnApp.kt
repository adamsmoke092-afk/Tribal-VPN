package com.tribal.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tribal.vpn.data.VpnConfig

private val Gold = Color(0xFFF5B700)
private val Black = Color(0xFF000000)
private val NavBg = Color(0xFF08080A)
private val MidGray = Color(0xFF6B6B70)

private enum class Tab { HOME, PROFILES, LOGS, LAST_RUN, SETTINGS }

/**
 * Top-level navigation shell. This is the composable MainActivity should
 * host instead of calling HomeScreen directly.
 */
@Composable
fun TribalVpnApp(
    viewModel: VpnViewModel,
    onRequestConnect: () -> Unit,
    isIgnoringBatteryOptimizations: Boolean,
    onRequestBatteryOptimizationExemption: () -> Unit
) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var editingProfile by remember { mutableStateOf<EditTarget?>(null) }

    Scaffold(
        containerColor = Black,
        bottomBar = {
            if (editingProfile == null) {
                BottomNavBar(current = tab, onSelect = { tab = it })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                editingProfile != null -> EditProfileScreen(
                    viewModel = viewModel,
                    existing = (editingProfile as? EditTarget.Existing)?.config,
                    onDone = { editingProfile = null }
                )
                tab == Tab.HOME -> HomeScreen(viewModel = viewModel, onRequestConnect = onRequestConnect)
                tab == Tab.PROFILES -> ProfilesScreen(
                    viewModel = viewModel,
                    onAddProfile = { editingProfile = EditTarget.New },
                    onEditProfile = { editingProfile = EditTarget.Existing(it) }
                )
                tab == Tab.LOGS -> LogsScreen(viewModel = viewModel)
                tab == Tab.LAST_RUN -> LastRunScreen()
                tab == Tab.SETTINGS -> AppSettingsScreen(
                    viewModel = viewModel,
                    isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                    onRequestBatteryOptimizationExemption = onRequestBatteryOptimizationExemption
                )
            }
        }
    }
}

private sealed class EditTarget {
    object New : EditTarget()
    data class Existing(val config: VpnConfig) : EditTarget()
}

@Composable
private fun BottomNavBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = NavBg, contentColor = MidGray) {
        NavItem(Icons.Filled.Home, "Home", current == Tab.HOME) { onSelect(Tab.HOME) }
        NavItem(Icons.Filled.Dns, "Profiles", current == Tab.PROFILES) { onSelect(Tab.PROFILES) }
        NavItem(Icons.Filled.Article, "Activity", current == Tab.LOGS) { onSelect(Tab.LOGS) }
        NavItem(Icons.Filled.BugReport, "Last Run", current == Tab.LAST_RUN) { onSelect(Tab.LAST_RUN) }
        NavItem(Icons.Filled.Settings, "Settings", current == Tab.SETTINGS) { onSelect(Tab.SETTINGS) }
    }
}

@Composable
private fun RowScope.NavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, fontSize = 10.sp) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Gold,
            selectedTextColor = Gold,
            unselectedIconColor = MidGray,
            unselectedTextColor = MidGray,
            indicatorColor = Black
        )
    )
}
