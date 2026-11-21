package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.RoutingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.veloDataStore: DataStore<Preferences> by preferencesDataStore(name = "velobridge_prefs")

data class AppPreferences(
    val activeProfileId: String? = null,
    val routingMode: RoutingMode = RoutingMode.BYPASS_LAN,
    val dnsServer: String = "1.1.1.1",
    val bypassLan: Boolean = true,
    val darkTheme: Boolean = true,
    val serverPort: Int = 10808,
    val serverUuid: String = "",
    val serverDisguiseId: String = "youtube",
    val serverCustomDomain: String = "",
    val serverEncryption: EncryptionCipher = EncryptionCipher.AES_256_GCM,
    val serverWsPath: String = "/youtubei/v1/player",
    val serverLanDiscovery: Boolean = true
)

class SettingsDataStore(private val context: Context) {
    private object Keys {
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val ROUTING_MODE = stringPreferencesKey("routing_mode")
        val DNS_SERVER = stringPreferencesKey("dns_server")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val SERVER_PORT = intPreferencesKey("server_port")
        val SERVER_UUID = stringPreferencesKey("server_uuid")
        val SERVER_DISGUISE_ID = stringPreferencesKey("server_disguise_id")
        val SERVER_CUSTOM_DOMAIN = stringPreferencesKey("server_custom_domain")
        val SERVER_ENCRYPTION = stringPreferencesKey("server_encryption")
        val SERVER_WS_PATH = stringPreferencesKey("server_ws_path")
        val SERVER_LAN_DISCOVERY = booleanPreferencesKey("server_lan_discovery")
    }

    val preferencesFlow: Flow<AppPreferences> = context.veloDataStore.data.map { prefs ->
        val uuid = prefs[Keys.SERVER_UUID]?.takeIf { it.isNotBlank() } ?: ""
        AppPreferences(
            activeProfileId = prefs[Keys.ACTIVE_PROFILE_ID],
            routingMode = prefs[Keys.ROUTING_MODE]?.let {
                runCatching { RoutingMode.valueOf(it) }.getOrDefault(RoutingMode.BYPASS_LAN)
            } ?: RoutingMode.BYPASS_LAN,
            dnsServer = prefs[Keys.DNS_SERVER] ?: "1.1.1.1",
            bypassLan = prefs[Keys.BYPASS_LAN] ?: true,
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            serverPort = prefs[Keys.SERVER_PORT] ?: 10808,
            serverUuid = uuid,
            serverDisguiseId = prefs[Keys.SERVER_DISGUISE_ID] ?: "youtube",
            serverCustomDomain = prefs[Keys.SERVER_CUSTOM_DOMAIN] ?: "",
            serverEncryption = prefs[Keys.SERVER_ENCRYPTION]?.let {
                runCatching { EncryptionCipher.valueOf(it) }.getOrDefault(EncryptionCipher.AES_256_GCM)
            } ?: EncryptionCipher.AES_256_GCM,
            serverWsPath = prefs[Keys.SERVER_WS_PATH] ?: "/youtubei/v1/player",
            serverLanDiscovery = prefs[Keys.SERVER_LAN_DISCOVERY] ?: true
        )
    }

    suspend fun ensureServerUuid(): String {
        var generated = ""
        context.veloDataStore.edit { prefs ->
            val existing = prefs[Keys.SERVER_UUID]
            if (existing.isNullOrBlank()) {
                generated = UUID.randomUUID().toString()
                prefs[Keys.SERVER_UUID] = generated
            } else {
                generated = existing
            }
        }
        return generated
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

    suspend fun setDarkTheme(dark: Boolean) {
        context.veloDataStore.edit { it[Keys.DARK_THEME] = dark }
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
            prefs[Keys.SERVER_PORT] = port
            prefs[Keys.SERVER_UUID] = uuid
            prefs[Keys.SERVER_DISGUISE_ID] = disguiseId
            prefs[Keys.SERVER_CUSTOM_DOMAIN] = customDomain
            prefs[Keys.SERVER_ENCRYPTION] = encryption.name
            prefs[Keys.SERVER_WS_PATH] = wsPath
            prefs[Keys.SERVER_LAN_DISCOVERY] = lanDiscovery
        }
    }
}
