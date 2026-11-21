package com.example.domain.model

import java.util.UUID

enum class ProtocolType(val label: String, val badge: String) {
    VELO_BRIDGE("Phone Bridge", "BRIDGE"),
    VLESS("VLESS", "VLESS"),
    VMESS("VMess", "VMESS"),
    TROJAN("Trojan", "TROJAN"),
    SHADOWSOCKS("Shadowsocks", "SS")
}

enum class TransportType(val wireValue: String, val label: String) {
    WEBSOCKET("ws", "WebSocket"),
    TCP_HTTP("tcp-http", "TCP + HTTP Mask"),
    GRPC("grpc", "gRPC"),
    TCP("tcp", "Raw TCP")
}

enum class EncryptionCipher(val wireValue: String, val label: String, val securityNote: String) {
    AES_256_GCM("aes-256-gcm", "AES-256-GCM", "Hardware-accelerated AEAD"),
    CHACHA20_POLY1305("chacha20-poly1305", "ChaCha20-Poly1305", "Fast mobile stream AEAD"),
    AUTO("auto", "Auto Negotiate", "Selects strongest cipher"),
    NONE("none", "TLS-Only (VLESS)", "Relies on outer TLS 1.3")
}

enum class TlsFingerprint(val wireValue: String, val label: String) {
    CHROME("chrome", "Chrome 131"),
    SAFARI("safari", "iOS Safari"),
    FIREFOX("firefox", "Firefox"),
    RANDOM("randomized", "Randomized uTLS")
}

enum class RoutingMode(val label: String, val description: String) {
    BYPASS_LAN("Bypass Local LAN", "Keep local Wi-Fi devices direct, tunnel internet"),
    GLOBAL("Global Tunnel", "Route all device traffic through the encrypted bridge")
}

data class DisguisePreset(
    val id: String,
    val title: String,
    val domain: String,
    val sni: String,
    val hostHeader: String,
    val wsPath: String,
    val alpn: String,
    val transport: TransportType,
    val userAgent: String,
    val beginnerSummary: String
)

object DisguiseCatalog {
    val presets: List<DisguisePreset> = listOf(
        DisguisePreset(
            id = "youtube",
            title = "YouTube",
            domain = "www.youtube.com",
            sni = "www.youtube.com",
            hostHeader = "www.youtube.com",
            wsPath = "/youtubei/v1/player",
            alpn = "h2,http/1.1",
            transport = TransportType.WEBSOCKET,
            userAgent = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
            beginnerSummary = "Blends encrypted tunnel packets with normal YouTube video streaming metadata."
        ),
        DisguisePreset(
            id = "zoom",
            title = "Zoom",
            domain = "zoom.us",
            sni = "us05web.zoom.us",
            hostHeader = "us05web.zoom.us",
            wsPath = "/wc/api/v1/stream",
            alpn = "h2,http/1.1",
            transport = TransportType.WEBSOCKET,
            userAgent = "ZoomVideoSDK/2.1 (Android)",
            beginnerSummary = "Masquerades traffic as Zoom video meeting streams—ideal for strict workplace Wi-Fi."
        ),
        DisguisePreset(
            id = "github",
            title = "GitHub",
            domain = "github.com",
            sni = "github.com",
            hostHeader = "api.github.com",
            wsPath = "/_ws/live",
            alpn = "h2,http/1.1",
            transport = TransportType.WEBSOCKET,
            userAgent = "git/2.47.0",
            beginnerSummary = "Disguises packets as developer HTTPS/API sync traffic to github.com."
        ),
        DisguisePreset(
            id = "cloudflare",
            title = "Cloudflare",
            domain = "cloudflare.com",
            sni = "www.cloudflare.com",
            hostHeader = "www.cloudflare.com",
            wsPath = "/cdn-cgi/trace",
            alpn = "h2,http/1.1",
            transport = TransportType.WEBSOCKET,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0",
            beginnerSummary = "Fronts connections behind standard Cloudflare edge CDN traffic headers."
        ),
        DisguisePreset(
            id = "custom",
            title = "Custom",
            domain = "custom.example.com",
            sni = "custom.example.com",
            hostHeader = "custom.example.com",
            wsPath = "/ray",
            alpn = "h2,http/1.1",
            transport = TransportType.WEBSOCKET,
            userAgent = "Mozilla/5.0 (Linux; Android 15) Chrome/131.0.0.0",
            beginnerSummary = "Specify any trusted domain SNI, Host header, and WebSocket path."
        )
    )

    fun findById(id: String): DisguisePreset =
        presets.find { it.id == id } ?: presets.first()

    fun resolveEffectiveDomain(presetId: String, customDomain: String): String {
        return if (presetId == "custom" && customDomain.isNotBlank()) {
            customDomain.trim()
        } else {
            findById(presetId).domain
        }
    }
}

data class V2RayProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: ProtocolType,
    val address: String,
    val port: Int,
    val uuidOrPassword: String,
    val encryption: EncryptionCipher = EncryptionCipher.AES_256_GCM,
    val transport: TransportType = TransportType.WEBSOCKET,
    val disguisePresetId: String = "youtube",
    val customDisguiseDomain: String = "",
    val sni: String = "www.youtube.com",
    val hostHeader: String = "www.youtube.com",
    val wsPath: String = "/youtubei/v1/player",
    val tlsEnabled: Boolean = true,
    val allowInsecure: Boolean = false,
    val tlsFingerprint: TlsFingerprint = TlsFingerprint.CHROME,
    val isPhoneBridge: Boolean = false,
    val latencyMs: Long? = null,
    val lastTestedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val rawJsonOverride: String? = null
) {
    val effectiveDisguiseDomain: String
        get() = when {
            disguisePresetId == "custom" && customDisguiseDomain.isNotBlank() -> customDisguiseDomain.trim()
            hostHeader.isNotBlank() -> hostHeader.trim()
            sni.isNotBlank() -> sni.trim()
            else -> DisguiseCatalog.findById(disguisePresetId).domain
        }
}

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class TelemetrySnapshot(
    val state: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val activeProfileId: String? = null,
    val activeProfileName: String? = null,
    val disguiseDomain: String = "www.youtube.com",
    val cipherLabel: String = "AES-256-GCM",
    val upSpeedBps: Long = 0L,
    val downSpeedBps: Long = 0L,
    val totalUpBytes: Long = 0L,
    val totalDownBytes: Long = 0L,
    val connectedSinceMillis: Long? = null,
    val latencyMs: Long? = null,
    val speedHistory: List<Long> = emptyList(),
    val localProxyPort: Int = 10809,
    val activeFlowCount: Int = 0,
    val errorMessage: String? = null
)

data class PeerSession(
    val id: String,
    val remoteIp: String,
    val disguiseDomain: String,
    val connectedAtMillis: Long,
    val bytesUp: Long,
    val bytesDown: Long
)

data class ServerRuntimeState(
    val isRunning: Boolean = false,
    val listenPort: Int = 10808,
    val uuid: String = "",
    val encryption: EncryptionCipher = EncryptionCipher.AES_256_GCM,
    val disguisePresetId: String = "youtube",
    val customDisguiseDomain: String = "",
    val wsPath: String = "/youtubei/v1/player",
    val lanDiscoveryEnabled: Boolean = true,
    val localIps: List<NetworkInterfaceInfo> = emptyList(),
    val activePeers: List<PeerSession> = emptyList(),
    val totalBytesUp: Long = 0L,
    val totalBytesDown: Long = 0L,
    val startedAtMillis: Long? = null,
    val errorMessage: String? = null
) {
    val effectiveDisguiseDomain: String
        get() = DisguiseCatalog.resolveEffectiveDomain(disguisePresetId, customDisguiseDomain)

    val primaryIp: String
        get() = localIps.firstOrNull { it.isHotspotOrWifi }?.ipAddress
            ?: localIps.firstOrNull()?.ipAddress
            ?: "127.0.0.1"
}

data class NetworkInterfaceInfo(
    val interfaceName: String,
    val ipAddress: String,
    val isHotspotOrWifi: Boolean
)

data class DiscoveredLanServer(
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val uuid: String,
    val disguisePresetId: String,
    val disguiseDomain: String,
    val encryption: EncryptionCipher,
    val wsPath: String,
    val rttMs: Long
)

data class LogEntry(
    val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

enum class LogLevel {
    INFO, SUCCESS, WARNING, ERROR
}

data class DisguiseProbeResult(
    val domain: String,
    val resolvedIp: String?,
    val tcpRttMs: Long?,
    val tlsHandshakeSuccess: Boolean,
    val tlsProtocol: String?,
    val errorMessage: String?
)
