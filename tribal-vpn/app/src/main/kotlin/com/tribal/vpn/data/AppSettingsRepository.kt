package com.tribal.vpn.data

import android.content.Context
import android.content.SharedPreferences
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

    fun setAutoReconnect(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()
        _autoReconnect.value = enabled
    }

    fun setRequireBiometrics(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, enabled).apply()
        _requireBiometrics.value = enabled
    }

    companion object {
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_BIOMETRIC_LOCK = "require_biometrics"
    }
}
