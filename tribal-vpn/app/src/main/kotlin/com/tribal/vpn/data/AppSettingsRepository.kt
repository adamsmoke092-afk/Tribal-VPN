package com.tribal.vpn.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-level (non-per-profile) settings. Uses plain SharedPreferences since
 * none of these values are secrets (unlike VpnConfig passwords, which go
 * through EncryptedSharedPreferences in ConfigRepository).
 *
 * Battery-optimization exemption is intentionally NOT stored here: it is a
 * real OS permission (PowerManager.isIgnoringBatteryOptimizations), not a
 * preference this app can grant itself. MainActivity queries PowerManager
 * directly and AppSettingsScreen displays that live value - see
 * MainActivity.isIgnoringBatteryOptimizations().
 */
class AppSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tribal_vpn_app_settings", Context.MODE_PRIVATE)

    private val _autoReconnect = MutableStateFlow(prefs.getBoolean(KEY_AUTO_RECONNECT, true))
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    private val _requireBiometrics = MutableStateFlow(prefs.getBoolean(KEY_BIOMETRIC_LOCK, true))
    val requireBiometrics: StateFlow<Boolean> = _requireBiometrics.asStateFlow()

    // Bypassed apps are applied via VpnService.Builder.addDisallowedApplication
    // at connect time (TribalVpnService): their traffic keeps the phone's
    // normal connection instead of going through the tunnel. Default is
    // Termux itself so the shell used for debugging never gets blackholed.
    private val _bypassApps = MutableStateFlow(readBypassApps())
    val bypassApps: StateFlow<List<String>> = _bypassApps.asStateFlow()

    // (Diagnostic build) Try every bridge .so variant at startup and log
    // each result; off = stop at the first variant that loads. Default ON
    // while the Android 8.1 RELRO question is open.
    private val _probeAllVariants = MutableStateFlow(prefs.getBoolean(KEY_PROBE_ALL_VARIANTS, true))
    val probeAllVariants: StateFlow<Boolean> = _probeAllVariants.asStateFlow()

    fun setAutoReconnect(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()
        _autoReconnect.value = enabled
    }

    fun setRequireBiometrics(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, enabled).apply()
        _requireBiometrics.value = enabled
    }

    fun setBypassApps(apps: List<String>) {
        prefs.edit().putString(KEY_BYPASS_APPS, apps.joinToString(",")).apply()
        _bypassApps.value = apps
    }

    fun setProbeAllVariants(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROBE_ALL_VARIANTS, enabled).apply()
        _probeAllVariants.value = enabled
    }

    /**
     * Stable per-install random key for the Xray engine's XUDP session-id
     * encryption (`xray.xudp.basekey`, set through libv2ray's initCoreEnv).
     * It is not a credential this app ever transmits - it only has to be
     * random and stable across reconnects and process restarts. Generated
     * lazily on first use and then persisted; plain prefs are fine because
     * the value is not a user secret (unlike VpnConfig passwords).
     */
    fun xrayXudpBasekey(): String {
        prefs.getString(KEY_XRAY_XUDP_BASEKEY, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val generated = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_XRAY_XUDP_BASEKEY, generated).apply()
        return generated
    }

    private fun readBypassApps(): List<String> {
        val raw = prefs.getString(KEY_BYPASS_APPS, null)
            ?: return listOf(DEFAULT_BYPASS_APP)
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    companion object {
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_BIOMETRIC_LOCK = "require_biometrics"
        private const val KEY_PROBE_ALL_VARIANTS = "probe_all_variants"
        private const val KEY_BYPASS_APPS = "bypass_apps"
        private const val KEY_XRAY_XUDP_BASEKEY = "xray_xudp_basekey"
        private const val DEFAULT_BYPASS_APP = "com.termux"
    }
}
