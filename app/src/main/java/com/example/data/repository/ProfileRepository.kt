package com.example.data.repository

import com.example.data.local.AppPreferences
import com.example.data.local.ProfileDao
import com.example.data.local.ProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.RoutingMode
import com.example.domain.model.V2RayProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProfileRepository(
    private val profileDao: ProfileDao,
    private val settingsDataStore: SettingsDataStore
) {
    val profiles: Flow<List<V2RayProfile>> = profileDao.observeAllProfiles()
        .map { entities -> entities.map { it.toDomain() } }

    val preferences: Flow<AppPreferences> = settingsDataStore.preferencesFlow

    suspend fun ensureServerUuid(): String = settingsDataStore.ensureServerUuid()

    suspend fun getProfileById(id: String): V2RayProfile? =
        profileDao.getProfileById(id)?.toDomain()

    suspend fun saveProfile(profile: V2RayProfile) {
        profileDao.upsertProfile(ProfileEntity.fromDomain(profile))
    }

    suspend fun saveProfiles(list: List<V2RayProfile>) {
        profileDao.upsertProfiles(list.map { ProfileEntity.fromDomain(it) })
    }

    suspend fun applyDisguiseToProfile(
        profileId: String,
        presetId: String,
        customDomain: String = ""
    ) {
        val existing = getProfileById(profileId) ?: return
        val preset = DisguiseCatalog.findById(presetId)
        val effective = if (presetId == "custom" && customDomain.isNotBlank()) {
            customDomain.trim()
        } else {
            preset.domain
        }
        val updated = existing.copy(
            disguisePresetId = presetId,
            customDisguiseDomain = if (presetId == "custom") effective else "",
            sni = effective,
            hostHeader = effective,
            wsPath = if (presetId == "custom") existing.wsPath.ifBlank { "/ray" } else preset.wsPath,
            transport = preset.transport
        )
        saveProfile(updated)
    }

    suspend fun updateLatency(id: String, latencyMs: Long?) {
        profileDao.updateLatency(id, latencyMs, System.currentTimeMillis())
    }

    suspend fun deleteProfile(id: String) {
        profileDao.deleteById(id)
    }

    suspend fun setActiveProfileId(id: String?) = settingsDataStore.setActiveProfileId(id)
    suspend fun setRoutingMode(mode: RoutingMode) = settingsDataStore.setRoutingMode(mode)
    suspend fun setDnsServer(dns: String) = settingsDataStore.setDnsServer(dns)
    suspend fun setBypassLan(enabled: Boolean) = settingsDataStore.setBypassLan(enabled)
    suspend fun setDarkTheme(dark: Boolean) = settingsDataStore.setDarkTheme(dark)

    suspend fun updateServerConfig(
        port: Int,
        uuid: String,
        disguiseId: String,
        customDomain: String,
        encryption: EncryptionCipher,
        wsPath: String,
        lanDiscovery: Boolean
    ) = settingsDataStore.updateServerConfig(
        port = port,
        uuid = uuid,
        disguiseId = disguiseId,
        customDomain = customDomain,
        encryption = encryption,
        wsPath = wsPath,
        lanDiscovery = lanDiscovery
    )
}
