package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.ProtocolType
import com.example.domain.model.TlsFingerprint
import com.example.domain.model.TransportType
import com.example.domain.model.V2RayProfile

@Entity(tableName = "v2ray_profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val uuidOrPassword: String,
    val encryption: String,
    val transport: String,
    val disguisePresetId: String,
    val customDisguiseDomain: String,
    val sni: String,
    val hostHeader: String,
    val wsPath: String,
    val tlsEnabled: Boolean,
    val allowInsecure: Boolean,
    val tlsFingerprint: String,
    val isPhoneBridge: Boolean,
    val latencyMs: Long?,
    val lastTestedAt: Long?,
    val createdAt: Long,
    val rawJsonOverride: String?
) {
    fun toDomain(): V2RayProfile = V2RayProfile(
        id = id,
        name = name,
        protocol = runCatching { ProtocolType.valueOf(protocol) }.getOrDefault(ProtocolType.VLESS),
        address = address,
        port = port,
        uuidOrPassword = uuidOrPassword,
        encryption = runCatching { EncryptionCipher.valueOf(encryption) }
            .getOrDefault(EncryptionCipher.AES_256_GCM),
        transport = runCatching { TransportType.valueOf(transport) }
            .getOrDefault(TransportType.WEBSOCKET),
        disguisePresetId = disguisePresetId,
        customDisguiseDomain = customDisguiseDomain,
        sni = sni,
        hostHeader = hostHeader,
        wsPath = wsPath,
        tlsEnabled = tlsEnabled,
        allowInsecure = allowInsecure,
        tlsFingerprint = runCatching { TlsFingerprint.valueOf(tlsFingerprint) }
            .getOrDefault(TlsFingerprint.CHROME),
        isPhoneBridge = isPhoneBridge,
        latencyMs = latencyMs,
        lastTestedAt = lastTestedAt,
        createdAt = createdAt,
        rawJsonOverride = rawJsonOverride
    )

    companion object {
        fun fromDomain(profile: V2RayProfile): ProfileEntity = ProfileEntity(
            id = profile.id,
            name = profile.name,
            protocol = profile.protocol.name,
            address = profile.address,
            port = profile.port,
            uuidOrPassword = profile.uuidOrPassword,
            encryption = profile.encryption.name,
            transport = profile.transport.name,
            disguisePresetId = profile.disguisePresetId,
            customDisguiseDomain = profile.customDisguiseDomain,
            sni = profile.sni,
            hostHeader = profile.hostHeader,
            wsPath = profile.wsPath,
            tlsEnabled = profile.tlsEnabled,
            allowInsecure = profile.allowInsecure,
            tlsFingerprint = profile.tlsFingerprint.name,
            isPhoneBridge = profile.isPhoneBridge,
            latencyMs = profile.latencyMs,
            lastTestedAt = profile.lastTestedAt,
            createdAt = profile.createdAt,
            rawJsonOverride = profile.rawJsonOverride
        )
    }
}
