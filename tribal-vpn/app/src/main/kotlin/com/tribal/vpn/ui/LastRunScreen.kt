package com.tribal.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tribal.vpn.diagnostics.Diagnostics
import kotlinx.coroutines.delay

private val Gold = Color(0xFFF5B700)
private val Black = Color(0xFF000000)
private val DarkGray = Color(0xFF111114)
private val CardBorder = Color(0xFF1F1F23)
private val MidGray = Color(0xFF6B6B70)
private val ErrorRed = Color(0xFFCF4A4A)
private val OkGreen = Color(0xFF3DBB61)

/**
 * Forensics screen for deaths the Java crash handler can't see (native
 * crash, linker abort, system kill). Shows the OS's own exit reasons, the
 * abnormal-shutdown marker status, this app's recent logcat (uid-scoped),
 * and the fsync'd durable app log - with a one-tap copy for pasting into a
 * bug report. All of it is captured evidence, not interpretation.
 */
@Composable
fun LastRunScreen() {
    var report by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        report = Diagnostics.readDiagnosticsReport()
        // The launch-time capture runs on a daemon thread - retry once in
        // case we got here faster than logcat/exit-reasons finished.
        delay(1500)
        Diagnostics.readDiagnosticsReport()?.let { report = it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(20.dp)
    ) {
        Text("Last Run", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Evidence captured at launch: exit reasons, logcat, durable app log",
            color = MidGray, fontSize = 11.sp
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (Diagnostics.previousRunEndedAbnormally) ErrorRed.copy(alpha = 0.15f) else OkGreen.copy(alpha = 0.10f),
                    RoundedCornerShape(12.dp)
                )
                .border(
                    1.dp,
                    if (Diagnostics.previousRunEndedAbnormally) ErrorRed else OkGreen,
                    RoundedCornerShape(12.dp)
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (Diagnostics.previousRunEndedAbnormally)
                    "Previous run ended abnormally (crash, system kill, or Android eviction)"
                else
                    "Previous run shut down cleanly",
                color = if (Diagnostics.previousRunEndedAbnormally) ErrorRed else OkGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                clipboard.setText(AnnotatedString(Diagnostics.buildCopyBlob()))
                copied = true
            },
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Black),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy Diagnostics", fontWeight = FontWeight.SemiBold)
        }
        if (copied) {
            Text(
                "Copied - paste it into the Qwen chat",
                color = Gold, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        val currentReport = report
        when {
            currentReport == null -> Text(
                "Capturing diagnostics... (open this tab again in a moment)",
                color = MidGray, fontSize = 12.sp
            )
            else -> LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(DarkGray, RoundedCornerShape(16.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(currentReport.lineSequence().toList()) { line ->
                    val isFatal = line.contains("Fatal signal") ||
                        line.contains("FATAL EXCEPTION") ||
                        line.contains("Abort message") ||
                        line.contains("NATIVE CRASH") ||
                        line.contains("low memory")
                    Text(
                        line,
                        color = if (isFatal) ErrorRed else Color(0xFFD8D8DC),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
