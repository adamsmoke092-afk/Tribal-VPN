package com.tribal.vpn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists VPN profiles to disk using EncryptedSharedPreferences (AES-256-GCM backed
 * by the Android Keystore). Passwords are never stored in plaintext on disk.
 *
 * No connection statistics or "fake" data live here - this is config storage only.
 */
class ConfigRepository(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<VpnConfig>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow(prefs.getString(KEY_ACTIVE_PROFILE, null))
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private fun loadProfiles(): List<VpnConfig> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<VpnConfig>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persist(list: List<VpnConfig>) {
        prefs.edit().putString(KEY_PROFILES, json.encodeToString(list)).apply()
        _profiles.value = list
    }

    fun saveProfile(config: VpnConfig) {
        val current = _profiles.value.toMutableList()
        val idx = current.indexOfFirst { it.id == config.id }
        if (idx >= 0) current[idx] = config else current.add(config)
        persist(current)
    }

    fun deleteProfile(id: String) {
        persist(_profiles.value.filterNot { it.id == id })
        if (_activeProfileId.value == id) setActiveProfile(_profiles.value.firstOrNull()?.id)
    }

    fun setActiveProfile(id: String?) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE, id).apply()
        _activeProfileId.value = id
    }

    fun getProfile(id: String): VpnConfig? = _profiles.value.find { it.id == id }

    companion object {
        private const val PREFS_FILE_NAME = "tribal_vpn_encrypted_prefs"
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"
    }
}
