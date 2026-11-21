package com.example.data.repository

import com.example.core.crypto.BridgeCrypto
import com.example.core.security.SecureVault
import com.example.core.security.VaultDecodeOutcome
import com.example.core.security.VaultHealthState
import com.example.core.security.VaultProtectOutcome
import com.example.data.local.AppPreferences
import com.example.data.local.ProfileDao
import com.example.data.local.ProfileEntity
import com.example.data.local.SettingsDataStore
import com.example.domain.model.AppearanceMode
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.PairedDevice
import com.example.domain.model.RoutingMode
import com.example.domain.model.SecurityCheckState
import com.example.domain.model.SecurityDiagnosticsReport
import com.example.domain.model.ServerRuntimeState
import com.example.domain.model.V2RayProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

class ProfileRepository(
    private val profileDao: ProfileDao,
    private val settingsDataStore: SettingsDataStore
) {
    val preferences: Flow<AppPreferences> = settingsDataStore.preferencesFlow

    val profiles: Flow<List<V2RayProfile>> = combine(
        profileDao.observeAllProfiles(),
        preferences
    ) { entities, _ ->
        entities.map { entity -> entity.toDomain(SecureVault::unprotectSecret) }
    }

    suspend fun ensureServerUuid(): String = settingsDataStore.ensureServerUuid()

    suspend fun getProfileById(id: String): V2RayProfile? =
        profileDao.getProfileById(id)?.toDomain(SecureVault::unprotectSecret)

    suspend fun saveProfile(profile: V2RayProfile): Boolean {
        val protect = preferences.first().sensitiveConfigProtection
        val encodedSecret = encodeSecretSafely(profile.uuidOrPassword, protect) ?: return false
        profileDao.upsertProfile(ProfileEntity.fromDomain(profile) { encodedSecret })
        return true
    }

    suspend fun saveProfiles(list: List<V2RayProfile>): Boolean {
        if (list.isEmpty()) return true
        val protect = preferences.first().sensitiveConfigProtection
        val entities = mutableListOf<ProfileEntity>()
        for (item in list) {
            val encoded = encodeSecretSafely(item.uuidOrPassword, protect) ?: return false
            entities.add(ProfileEntity.fromDomain(item) { encoded })
        }
        profileDao.upsertProfiles(entities)
        return true
    }

    /**
     * Migrates any legacy plaintext profile credentials to Android Keystore AES-256-GCM when
     * protection is enabled. Leaves rows untouched if encryption fails.
     */
    suspend fun migrateLegacySecretsIfNeeded(): Int {
        val prefs = preferences.first()
        if (!prefs.sensitiveConfigProtection) return 0
        val allEntities = profileDao.getAllOnce()
        val toUpgrade = mutableListOf<ProfileEntity>()
        for (entity in allEntities) {
            if (entity.uuidOrPassword.isNotBlank() && !SecureVault.isEncryptedValue(entity.uuidOrPassword)) {
                when (val outcome = SecureVault.protectSecretStrict(entity.uuidOrPassword)) {
                    is VaultProtectOutcome.Protected -> {
                        toUpgrade.add(entity.copy(uuidOrPassword = outcome.ciphertext))
                    }
                    else -> Unit
                }
            }
        }
        if (toUpgrade.isNotEmpty()) {
            profileDao.upsertProfiles(toUpgrade)
        }
        return toUpgrade.size
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

    suspend fun setFavorite(id: String, isFavorite: Boolean) {
        profileDao.setFavorite(id, isFavorite)
    }

    suspend fun recordSuccessfulConnection(profileId: String, timestampMillis: Long = System.currentTimeMillis()) {
        profileDao.updateLastConnected(profileId, timestampMillis)
        settingsDataStore.setLastSuccessfulConnection(timestampMillis)
    }

    suspend fun deleteProfile(id: String) {
        profileDao.deleteById(id)
    }

    suspend fun setActiveProfileId(id: String?) = settingsDataStore.setActiveProfileId(id)
    suspend fun setRoutingMode(mode: RoutingMode) = settingsDataStore.setRoutingMode(mode)
    suspend fun setDnsServer(dns: String) = settingsDataStore.setDnsServer(dns)
    suspend fun setBypassLan(enabled: Boolean) = settingsDataStore.setBypassLan(enabled)
    suspend fun setAppearanceMode(mode: AppearanceMode) = settingsDataStore.setAppearanceMode(mode)
    suspend fun setDarkTheme(dark: Boolean) = settingsDataStore.setDarkTheme(dark)
    suspend fun setHapticFeedback(enabled: Boolean) = settingsDataStore.setHapticFeedback(enabled)
    suspend fun setNotificationsEnabled(enabled: Boolean) = settingsDataStore.setNotificationsEnabled(enabled)
    suspend fun setAutoReconnect(enabled: Boolean) = settingsDataStore.setAutoReconnect(enabled)
    suspend fun setOnboardingCompleted(completed: Boolean) = settingsDataStore.setOnboardingCompleted(completed)
    suspend fun setHighSpeedMode(enabled: Boolean) = settingsDataStore.setHighSpeedMode(enabled)

    suspend fun addPairedDevice(device: PairedDevice) = settingsDataStore.addPairedDevice(device)
    suspend fun revokePairedDevice(deviceId: String) = settingsDataStore.revokePairedDevice(deviceId)

    suspend fun setSensitiveConfigProtection(enabled: Boolean): Boolean {
        settingsDataStore.setSensitiveConfigProtection(enabled)
        val entities = profileDao.getAllOnce()
        val reEncoded = mutableListOf<ProfileEntity>()
        var allSucceeded = true
        for (entity in entities) {
            if (entity.uuidOrPassword.isBlank()) continue
            when (val decoded = SecureVault.unprotectSecretStrict(entity.uuidOrPassword)) {
                is VaultDecodeOutcome.Decrypted -> {
                    if (!enabled) {
                        reEncoded.add(entity.copy(uuidOrPassword = decoded.plaintext))
                    }
                }
                is VaultDecodeOutcome.LegacyPlaintext -> {
                    if (enabled) {
                        when (val enc = SecureVault.protectSecretStrict(decoded.plaintext)) {
                            is VaultProtectOutcome.Protected -> {
                                reEncoded.add(entity.copy(uuidOrPassword = enc.ciphertext))
                            }
                            else -> allSucceeded = false
                        }
                    }
                }
                is VaultDecodeOutcome.CorruptedOrInvalidated -> {
                    // Never overwrite existing ciphertext silently
                    allSucceeded = false
                }
                is VaultDecodeOutcome.Empty -> Unit
            }
        }
        if (reEncoded.isNotEmpty()) {
            profileDao.upsertProfiles(reEncoded)
        }
        return allSucceeded
    }

    suspend fun countUnencryptedStoredEntities(): Int {
        return profileDao.getAllOnce().count { entity ->
            entity.uuidOrPassword.isNotBlank() && !SecureVault.isEncryptedValue(entity.uuidOrPassword)
        }
    }

    fun buildSecurityDiagnostics(
        profiles: List<V2RayProfile>,
        prefs: AppPreferences,
        serverState: ServerRuntimeState = ServerRuntimeState(),
        unencryptedCount: Int = 0
    ): SecurityDiagnosticsReport {
        val health = SecureVault.getVaultHealth()
        val insecureCount = profiles.count { it.allowInsecure }
        val unencryptedProfiles = profiles.count { !it.tlsEnabled && it.encryption == EncryptionCipher.NONE }

        val (vaultState, vaultDetail) = when {
            !prefs.sensitiveConfigProtection ->
                SecurityCheckState.DISABLED to "Encryption disabled in preferences"
            prefs.vaultDegradedReason != null ->
                SecurityCheckState.DEGRADED to "Vault read error: ${prefs.vaultDegradedReason}"
            health == VaultHealthState.VERIFIED_KEYSTORE && unencryptedCount == 0 ->
                SecurityCheckState.VERIFIED to "Android Keystore AES-256-GCM verified"
            health == VaultHealthState.VERIFIED_KEYSTORE && unencryptedCount > 0 ->
                SecurityCheckState.DEGRADED to "$unencryptedCount legacy plaintext secret(s) pending migration"
            health == VaultHealthState.TEST_EPHEMERAL_FALLBACK ->
                SecurityCheckState.VERIFIED to "JVM test AES-256-GCM vault active (no hardware keystore)"
            health == VaultHealthState.DEGRADED_KEY_INVALIDATED ->
                SecurityCheckState.DEGRADED to "Keystore key invalidated; rotate or re-import secrets"
            else ->
                SecurityCheckState.DEGRADED to (SecureVault.getLastFailureReason() ?: "Hardware Keystore unavailable")
        }

        val replayOperational = BridgeCrypto.isReplayProtectionOperational()
        val replayState = if (replayOperational) SecurityCheckState.VERIFIED else SecurityCheckState.DEGRADED
        val replayDetail = if (replayOperational) {
            "Mandatory >=12-char nonce + +/-2m window cache"
        } else {
            "Replay cache verification failed"
        }

        val (proxyState, proxyDetail) = when {
            serverState.isRunning && serverState.authGateActive ->
                SecurityCheckState.VERIFIED to "Active (${serverState.rejectedHandshakes} unauthenticated probes blocked)"
            serverState.isRunning && !serverState.authGateActive ->
                SecurityCheckState.DEGRADED to "Server active without verified auth gate"
            else ->
                SecurityCheckState.NOT_APPLICABLE to "Bridge Server standby (enforces HMAC + ECDH on start)"
        }

        val lanPairingState = if (serverState.lanDiscoveryEnabled) {
            SecurityCheckState.VERIFIED
        } else {
            SecurityCheckState.DISABLED
        }
        val lanPairingDetail = if (serverState.lanDiscoveryEnabled) {
            "Zero-secret discovery + ECDH 6-digit approval (${prefs.pairedDevices.size} paired)"
        } else {
            "LAN discovery beacon disabled (manual URI/QR pairing only)"
        }

        val (transportState, transportDetail) = when {
            insecureCount > 0 || unencryptedProfiles > 0 ->
                SecurityCheckState.DEGRADED to "$insecureCount allow-insecure TLS, $unencryptedProfiles unencrypted profile(s)"
            else ->
                SecurityCheckState.VERIFIED to "P-256 ECDH + HKDF AEAD frames (HTTP-Upgrade disguise; not TLS)"
        }

        return SecurityDiagnosticsReport(
            vaultState = vaultState,
            vaultDetail = vaultDetail,
            replayGuardState = replayState,
            replayGuardDetail = replayDetail,
            proxyAuthDefenseState = proxyState,
            proxyAuthDefenseDetail = proxyDetail,
            lanPairingState = lanPairingState,
            lanPairingDetail = lanPairingDetail,
            transportSecurityState = transportState,
            transportSecurityDetail = transportDetail,
            keystoreBacked = health == VaultHealthState.VERIFIED_KEYSTORE,
            sensitiveProtectionEnabled = prefs.sensitiveConfigProtection,
            allowInsecureProfilesCount = insecureCount,
            unencryptedProfilesCount = unencryptedProfiles,
            checkedAtMillis = System.currentTimeMillis()
        )
    }

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

    private fun encodeSecretSafely(plaintext: String, protect: Boolean): String? {
        if (!protect) return plaintext
        return when (val enc = SecureVault.protectSecretStrict(plaintext)) {
            is VaultProtectOutcome.Protected -> enc.ciphertext
            is VaultProtectOutcome.AlreadyProtected -> enc.ciphertext
            is VaultProtectOutcome.Empty -> ""
            is VaultProtectOutcome.Failed -> null
        }
    }
}
