package com.tribal.vpn

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.tribal.vpn.diagnostics.Diagnostics
import com.tribal.vpn.ui.VpnViewModel
import com.tribal.vpn.vpn.TribalVpnService
import java.io.File

/**
 * Entry point. Handles two real OS-level permissions before the app can
 * claim a corresponding UI state is true:
 *  1. VpnService.prepare() - required before any connect attempt
 *  2. PowerManager.isIgnoringBatteryOptimizations() - queried live, never
 *     assumed from a stored preference (see AppSettingsRepository doc comment)
 */
class MainActivity : ComponentActivity() {

    private val viewModel: VpnViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.connect()
        }
        // If the user denies permission, connectionState simply stays
        // DISCONNECTED - no fallback fake "connected" state is shown.
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Result code from this system dialog isn't reliably meaningful across
        // OEMs, so we don't trust it - we re-query PowerManager directly below
        // instead of assuming success from the activity result.
        refreshBatteryOptimizationState()
    }

    private var isIgnoringBatteryOptimizations by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportLastCrashIfAny()
        Diagnostics.runStartupCapture(applicationContext)
        refreshBatteryOptimizationState()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    com.tribal.vpn.ui.TribalVpnApp(
                        viewModel = viewModel,
                        onRequestConnect = { requestConnectWithPermissionCheck() },
                        isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                        onRequestBatteryOptimizationExemption = { requestBatteryOptimizationExemption() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted/revoked this in system Settings while the
        // app was backgrounded - re-check rather than trusting stale state.
        refreshBatteryOptimizationState()
    }

    override fun onDestroy() {
        // Backing out of the last Activity is the one clean-shutdown signal
        // Android reliably gives us - it clears the abnormal-end marker.
        // Crashes, native aborts and system kills skip this by definition.
        if (isFinishing) Diagnostics.clearRunningMarker()
        super.onDestroy()
    }

    /**
     * If the previous session died from an uncaught crash, surface it in the
     * in-app Logs screen instead of it vanishing (no adb/logcat on the dev
     * phone). Consumed once - the crash file is deleted after being read.
     */
    private fun reportLastCrashIfAny() {
        try {
            val file = File(filesDir, TribalVpnApp.LAST_CRASH_FILE)
            if (!file.exists()) return
            val trace = file.readText()
            file.delete()
            val firstLines = trace.lineSequence().take(6).joinToString("\n")
            TribalVpnService.appendLog(
                "App crashed last session - see technical details",
                "Last crash (top of stack):\n$firstLines",
                isError = true
            )
        } catch (_: Throwable) {
            // Crash reporting must never crash the app.
        }
    }

    private fun refreshBatteryOptimizationState() {
        val powerManager = getSystemService(PowerManager::class.java)
        isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            refreshBatteryOptimizationState()
            return
        }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        batteryOptimizationLauncher.launch(intent)
    }

    /**
     * Call this instead of viewModel.connect() directly from UI entry points
     * that haven't yet confirmed VPN permission. VpnService.prepare() returns
     * null if permission is already granted; in that case we connect immediately.
     */
    fun requestConnectWithPermissionCheck() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.connect()
        }
    }
}
