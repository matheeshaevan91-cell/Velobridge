package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.security.SecureVault
import com.example.core.security.VaultDecodeOutcome
import com.example.core.security.VaultProtectOutcome
import com.example.domain.model.AppearanceMode
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.PairedDevice
import com.example.domain.model.RoutingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.veloDataStore: DataStore<Preferences> by preferencesDataStore(name = "velobridge_prefs")

data class AppPreferences(
    val activeProfileId: String? = null,
    val routingMode: RoutingMode = RoutingMode.BYPASS_LAN,
    val dnsServer: String = "1.1.1.1",
    val bypassLan: Boolean = true,
    val appearanceMode: AppearanceMode = AppearanceMode.DARK,
    val hapticFeedback: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val autoReconnect: Boolean = true,
    val sensitiveConfigProtection: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val lastSuccessfulConnectionMillis: Long? = null,
    val serverPort: Int = 10808,
    val serverUuid: String = "",
    val serverDisguiseId: String = "youtube",
    val serverCustomDomain: String = "",
    val serverEncryption: EncryptionCipher = EncryptionCipher.AES_256_GCM,
    val serverWsPath: String = "/youtubei/v1/player",
    val serverLanDiscovery: Boolean = true,
    val highSpeedMode: Boolean = false,
    val pairedDevices: List<PairedDevice> = emptyList(),
    val vaultDegradedReason: String? = null
) {
    val darkTheme: Boolean
        get() = appearanceMode != AppearanceMode.LIGHT
}

class SettingsDataStore(private val context: Context) {
    private object Keys {
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val ROUTING_MODE = stringPreferencesKey("routing_mode")
        val DNS_SERVER = stringPreferencesKey("dns_server")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val SENSITIVE_PROTECTION = booleanPreferencesKey("sensitive_config_protection")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val LAST_SUCCESSFUL_CONNECTION = longPreferencesKey("last_successful_connection")
        val SERVER_PORT = intPreferencesKey("server_port")
        val SERVER_UUID = stringPreferencesKey("server_uuid")
        val SERVER_DISGUISE_ID = stringPreferencesKey("server_disguise_id")
        val SERVER_CUSTOM_DOMAIN = stringPreferencesKey("server_custom_domain")
        val SERVER_ENCRYPTION = stringPreferencesKey("server_encryption")
        val SERVER_WS_PATH = stringPreferencesKey("server_ws_path")
        val SERVER_LAN_DISCOVERY = booleanPreferencesKey("server_lan_discovery")
        val HIGH_SPEED_MODE = booleanPreferencesKey("high_speed_mode")
        val PAIRED_DEVICES_JSON = stringPreferencesKey("paired_devices_json")
    }

    val preferencesFlow: Flow<AppPreferences> = context.veloDataStore.data.map { prefs ->
        val rawUuid = prefs[Keys.SERVER_UUID].orEmpty()
        var vaultError: String? = null
        val decodedUuid = when (val outcome = SecureVault.unprotectSecretStrict(rawUuid)) {
            is VaultDecodeOutcome.Decrypted -> outcome.plaintext
            is VaultDecodeOutcome.LegacyPlaintext -> outcome.plaintext
            is VaultDecodeOutcome.Empty -> ""
            is VaultDecodeOutcome.CorruptedOrInvalidated -> {
                vaultError = outcome.reason
                ""
            }
        }

        val decodedPaired = decodePairedDevices(prefs[Keys.PAIRED_DEVICES_JSON].orEmpty()) { err ->
            if (vaultError == null) vaultError = err
        }

        val appearance = prefs[Keys.APPEARANCE_MODE]?.let {
            runCatching { AppearanceMode.valueOf(it) }.getOrNull()
        } ?: if (prefs[Keys.DARK_THEME] == false) AppearanceMode.LIGHT else AppearanceMode.DARK

        AppPreferences(
            activeProfileId = prefs[Keys.ACTIVE_PROFILE_ID],
            routingMode = prefs[Keys.ROUTING_MODE]?.let {
                runCatching { RoutingMode.valueOf(it) }.getOrDefault(RoutingMode.BYPASS_LAN)
            } ?: RoutingMode.BYPASS_LAN,
            dnsServer = prefs[Keys.DNS_SERVER] ?: "1.1.1.1",
            bypassLan = prefs[Keys.BYPASS_LAN] ?: true,
            appearanceMode = appearance,
            hapticFeedback = prefs[Keys.HAPTIC_FEEDBACK] ?: true,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
            sensitiveConfigProtection = prefs[Keys.SENSITIVE_PROTECTION] ?: true,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            lastSuccessfulConnectionMillis = prefs[Keys.LAST_SUCCESSFUL_CONNECTION],
            serverPort = prefs[Keys.SERVER_PORT] ?: 10808,
            serverUuid = decodedUuid,
            serverDisguiseId = prefs[Keys.SERVER_DISGUISE_ID] ?: "youtube",
            serverCustomDomain = prefs[Keys.SERVER_CUSTOM_DOMAIN] ?: "",
            serverEncryption = prefs[Keys.SERVER_ENCRYPTION]?.let {
                runCatching { EncryptionCipher.valueOf(it) }.getOrDefault(EncryptionCipher.AES_256_GCM)
            } ?: EncryptionCipher.AES_256_GCM,
            serverWsPath = prefs[Keys.SERVER_WS_PATH] ?: "/youtubei/v1/player",
            serverLanDiscovery = prefs[Keys.SERVER_LAN_DISCOVERY] ?: true,
            highSpeedMode = prefs[Keys.HIGH_SPEED_MODE] ?: false,
            pairedDevices = decodedPaired,
            vaultDegradedReason = vaultError
        )
    }

    suspend fun ensureServerUuid(): String {
        var resolved = ""
        context.veloDataStore.edit { prefs ->
            val protect = prefs[Keys.SENSITIVE_PROTECTION] ?: true
            val existing = prefs[Keys.SERVER_UUID].orEmpty()
            if (existing.isBlank()) {
                val raw = UUID.randomUUID().toString()
                resolved = raw
                if (protect) {
                    when (val enc = SecureVault.protectSecretStrict(raw)) {
                        is VaultProtectOutcome.Protected -> prefs[Keys.SERVER_UUID] = enc.ciphertext
                        is VaultProtectOutcome.AlreadyProtected -> prefs[Keys.SERVER_UUID] = enc.ciphertext
                        else -> Unit // Never silently persist plaintext when protection is required
                    }
                } else {
                    prefs[Keys.SERVER_UUID] = raw
                }
            } else {
                when (val decoded = SecureVault.unprotectSecretStrict(existing)) {
                    is VaultDecodeOutcome.Decrypted -> {
                        resolved = decoded.plaintext
                    }
                    is VaultDecodeOutcome.LegacyPlaintext -> {
                        resolved = decoded.plaintext
                        if (protect && resolved.isNotBlank()) {
                            when (val enc = SecureVault.protectSecretStrict(resolved)) {
                                is VaultProtectOutcome.Protected -> prefs[Keys.SERVER_UUID] = enc.ciphertext
                                is VaultProtectOutcome.AlreadyProtected -> prefs[Keys.SERVER_UUID] = enc.ciphertext
                                else -> Unit
                            }
                        }
                    }
                    is VaultDecodeOutcome.Empty -> {
                        val raw = UUID.randomUUID().toString()
                        resolved = raw
                        if (protect) {
                            (SecureVault.protectSecretStrict(raw) as? VaultProtectOutcome.Protected)?.let {
                                prefs[Keys.SERVER_UUID] = it.ciphertext
                            }
                        } else {
                            prefs[Keys.SERVER_UUID] = raw
                        }
                    }
                    is VaultDecodeOutcome.CorruptedOrInvalidated -> {
                        // Preserve existing ciphertext rather than overwriting silently
                        resolved = ""
                    }
                }
            }
        }
        return resolved
    }

    suspend fun setActiveProfileId(id: String?) {
        context.veloDataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACTIVE_PROFILE_ID)
            else prefs[Keys.ACTIVE_PROFILE_ID] = id
        }
    }

    suspend fun setRoutingMode(mode: RoutingMode) {
        context.veloDataStore.edit { it[Keys.ROUTING_MODE] = mode.name }
    }

    suspend fun setDnsServer(dns: String) {
        context.veloDataStore.edit { it[Keys.DNS_SERVER] = dns.trim() }
    }

    suspend fun setBypassLan(enabled: Boolean) {
        context.veloDataStore.edit { it[Keys.BYPASS_LAN] = enabled }
    }

    suspend fun setAppearanceMode(mode: AppearanceMode) {
        context.veloDataStore.edit { prefs ->
            prefs[Keys.APPEARANCE_MODE] = mode.name
            prefs[Keys.DARK_THEME] = mode != AppearanceMode.LIGHT
        }
    }

    suspend fun setDarkTheme(dark: Boolean) {
        setAppearanceMode(if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT)
    }

    suspend fun setHapticFeedback(enabled: Boolean) {
        context.veloDataStore.edit { it[Keys.HAPTIC_FEEDBACK] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.veloDataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.veloDataStore.edit { it[Keys.AUTO_RECONNECT] = enabled }
    }

    suspend fun setSensitiveConfigProtection(enabled: Boolean) {
        context.veloDataStore.edit { prefs ->
            prefs[Keys.SENSITIVE_PROTECTION] = enabled
            val currentUuid = prefs[Keys.SERVER_UUID].orEmpty()
            if (currentUuid.isNotBlank()) {
                when (val decoded = SecureVault.unprotectSecretStrict(currentUuid)) {
                    is VaultDecodeOutcome.Decrypted -> {
                        if (!enabled) {
                            prefs[Keys.SERVER_UUID] = decoded.plaintext
                        }
                    }
                    is VaultDecodeOutcome.LegacyPlaintext -> {
                        if (enabled) {
                            (SecureVault.protectSecretStrict(decoded.plaintext) as? VaultProtectOutcome.Protected)?.let {
                                prefs[Keys.SERVER_UUID] = it.ciphertext
                            }
                        }
                    }
                    else -> Unit
                }
            }
            val currentPaired = decodePairedDevices(prefs[Keys.PAIRED_DEVICES_JSON].orEmpty()) {}
            if (currentPaired.isNotEmpty()) {
                prefs[Keys.PAIRED_DEVICES_JSON] = encodePairedDevices(currentPaired, enabled)
            }
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.veloDataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setLastSuccessfulConnection(timestampMillis: Long) {
        context.veloDataStore.edit { it[Keys.LAST_SUCCESSFUL_CONNECTION] = timestampMillis }
    }

    suspend fun setHighSpeedMode(enabled: Boolean) {
        context.veloDataStore.edit { it[Keys.HIGH_SPEED_MODE] = enabled }
    }

    suspend fun addPairedDevice(device: PairedDevice) {
        context.veloDataStore.edit { prefs ->
            val protect = prefs[Keys.SENSITIVE_PROTECTION] ?: true
            val existing = decodePairedDevices(prefs[Keys.PAIRED_DEVICES_JSON].orEmpty()) {}
                .filterNot { it.deviceId == device.deviceId }
            val updated = listOf(device) + existing
            prefs[Keys.PAIRED_DEVICES_JSON] = encodePairedDevices(updated, protect)
        }
    }

    suspend fun revokePairedDevice(deviceId: String) {
        context.veloDataStore.edit { prefs ->
            val protect = prefs[Keys.SENSITIVE_PROTECTION] ?: true
            val existing = decodePairedDevices(prefs[Keys.PAIRED_DEVICES_JSON].orEmpty()) {}
                .filterNot { it.deviceId == deviceId }
            prefs[Keys.PAIRED_DEVICES_JSON] = encodePairedDevices(existing, protect)
        }
    }

    suspend fun updateServerConfig(
        port: Int,
        uuid: String,
        disguiseId: String,
        customDomain: String,
        encryption: EncryptionCipher,
        wsPath: String,
        lanDiscovery: Boolean
    ) {
        context.veloDataStore.edit { prefs ->
            val protect = prefs[Keys.SENSITIVE_PROTECTION] ?: true
            val plainUuid = when (val decoded = SecureVault.unprotectSecretStrict(uuid)) {
                is VaultDecodeOutcome.Decrypted -> decoded.plaintext
                is VaultDecodeOutcome.LegacyPlaintext -> decoded.plaintext
                else -> uuid
            }
            prefs[Keys.SERVER_PORT] = port
            if (plainUuid.isNotBlank()) {
                if (protect) {
                    (SecureVault.protectSecretStrict(plainUuid) as? VaultProtectOutcome.Protected)?.let {
                        prefs[Keys.SERVER_UUID] = it.ciphertext
                    }
                } else {
                    prefs[Keys.SERVER_UUID] = plainUuid
                }
            }
            prefs[Keys.SERVER_DISGUISE_ID] = disguiseId
            prefs[Keys.SERVER_CUSTOM_DOMAIN] = customDomain
            prefs[Keys.SERVER_ENCRYPTION] = encryption.name
            prefs[Keys.SERVER_WS_PATH] = wsPath
            prefs[Keys.SERVER_LAN_DISCOVERY] = lanDiscovery
        }
    }

    private fun decodePairedDevices(
        rawJson: String,
        onError: (String) -> Unit
    ): List<PairedDevice> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(rawJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val storedSecret = obj.optString("secret")
                    val plainSecret = when (val dec = SecureVault.unprotectSecretStrict(storedSecret)) {
                        is VaultDecodeOutcome.Decrypted -> dec.plaintext
                        is VaultDecodeOutcome.LegacyPlaintext -> dec.plaintext
                        is VaultDecodeOutcome.Empty -> ""
                        is VaultDecodeOutcome.CorruptedOrInvalidated -> {
                            onError(dec.reason)
                            ""
                        }
                    }
                    if (plainSecret.isNotBlank()) {
                        add(
                            PairedDevice(
                                deviceId = obj.optString("deviceId"),
                                deviceName = obj.optString("deviceName", "Client Phone"),
                                deviceSecret = plainSecret,
                                pairedAtMillis = obj.optLong("pairedAtMillis", 0L)
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodePairedDevices(devices: List<PairedDevice>, protect: Boolean): String {
        val arr = JSONArray()
        for (d in devices) {
            val storedSecret = if (protect) {
                when (val enc = SecureVault.protectSecretStrict(d.deviceSecret)) {
                    is VaultProtectOutcome.Protected -> enc.ciphertext
                    is VaultProtectOutcome.AlreadyProtected -> enc.ciphertext
                    else -> continue
                }
            } else {
                d.deviceSecret
            }
            arr.put(
                JSONObject().apply {
                    put("deviceId", d.deviceId)
                    put("deviceName", d.deviceName)
                    put("secret", storedSecret)
                    put("pairedAtMillis", d.pairedAtMillis)
                }
            )
        }
        return arr.toString()
    }
}
