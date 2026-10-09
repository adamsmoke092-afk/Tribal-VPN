package com.tribal.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tribal.vpn.vpn.TribalVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Gold = Color(0xFFF5B700)
private val Black = Color(0xFF000000)
private val DarkGray = Color(0xFF111114)
private val CardBorder = Color(0xFF1F1F23)
private val MidGray = Color(0xFF6B6B70)
private val ErrorRed = Color(0xFFCF4A4A)

/**
 * Displays TribalVpnService.logs — real appendLog() calls emitted from
 * actual service lifecycle transitions (connecting, authenticated, error,
 * disconnected). Nothing here is scripted/timed for demo purposes; if the
 * service hasn't done anything yet, the list is genuinely empty.
 */
@Composable
fun LogsScreen(viewModel: VpnViewModel) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    var showTechnical by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Activity", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier
                    .background(DarkGray, RoundedCornerShape(50))
                    .border(1.dp, CardBorder, RoundedCornerShape(50))
                    .clickable { showTechnical = !showTechnical }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (showTechnical) "Technical" else "Simple",
                    color = MidGray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (logs.isEmpty()) {
            EmptyLogsState()
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(if (showTechnical) Black else DarkGray, RoundedCornerShape(16.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs) { entry ->
                    val message = if (showTechnical) {
                        entry.technicalMessage ?: entry.plainMessage
                    } else {
                        entry.plainMessage ?: entry.technicalMessage
                    }
                    if (message != null) {
                        LogLine(
                            time = timeFormat.format(Date(entry.timestampMillis)),
                            message = message,
                            isError = entry.isError,
                            monospace = showTechnical
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { viewModel.clearLogs() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MidGray)
            ) {
                Text("Clear Activity")
            }
        }
    }
}

@Composable
private fun LogLine(time: String, message: String, isError: Boolean, monospace: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            time, color = MidGray, fontSize = 11.sp,
            fontFamily = if (monospace) androidx.compose.ui.text.font.FontFamily.Monospace else null
        )
        Text(
            message,
            color = if (isError) ErrorRed else if (monospace) Color(0xFFD8D8DC) else Color.White,
            fontSize = if (monospace) 11.sp else 13.sp,
            fontFamily = if (monospace) androidx.compose.ui.text.font.FontFamily.Monospace else null
        )
    }
}

@Composable
private fun EmptyLogsState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkGray, RoundedCornerShape(16.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Article, contentDescription = null, tint = MidGray, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text("No activity yet", color = MidGray, fontSize = 14.sp)
        Text("Connect to a profile to see events here", color = MidGray.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}
