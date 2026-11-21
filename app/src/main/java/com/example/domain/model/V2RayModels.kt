package com.example.domain.model

import java.util.UUID

enum class ProtocolSupportLevel(
    val label: String,
    val isNativeTunnelSupported: Boolean,
    val explanation: String
) {
    FULL_NATIVE_TUNNEL(
        label = "Built-in Tunnel",
        isNativeTunnelSupported = true,
        explanation = "End-to-end encrypted Android VPN & Phone Bridge tunnel built into VeloBridge."
    ),
    CONFIG_AND_DIAGNOSTICS_ONLY(
        label = "Import / Probe Only",
        isNativeTunnelSupported = false,
        explanation = "Supports import, export, disguise editing, and TCP/TLS probing. Live packet tunneling requires an external Xray/V2Ray core not bundled in this APK."
    )
}

enum class ProtocolType(
    val label: String,
    val badge: String,
    val supportLevel: ProtocolSupportLevel
) {
    VELO_BRIDGE("Phone Bridge", "BRIDGE", ProtocolSupportLevel.FULL_NATIVE_TUNNEL),
    VLESS("VLESS", "VLESS", ProtocolSupportLevel.CONFIG_AND_DIAGNOSTICS_ONLY),
    VMESS("VMess", "VMESS", ProtocolSupportLevel.CONFIG_AND_DIAGNOSTICS_ONLY),
    TROJAN("Trojan", "TROJAN", ProtocolSupportLevel.CONFIG_AND_DIAGNOSTICS_ONLY),
    SHADOWSOCKS("Shadowsocks", "SS", ProtocolSupportLevel.CONFIG_AND_DIAGNOSTICS_ONLY)
}

enum class TransportType(val wireValue: String, val label: String) {
    WEBSOCKET("ws", "HTTP-Upgrade Camouflage"),
    TCP_HTTP("tcp-http", "TCP + HTTP Mask"),
    GRPC("grpc", "gRPC"),
    TCP("tcp", "Raw TCP")
}

enum class EncryptionCipher(val wireValue: String, val label: String, val securityNote: String) {
    AES_256_GCM("aes-256-gcm", "AES-256-GCM", "Hardware-accelerated 256-bit AEAD"),
    CHACHA20_POLY1305("chacha20-poly1305", "ChaCha20-Poly1305", "Constant-time stream AEAD for mobile CPUs"),
    AUTO("auto", "Auto Negotiate", "Selects strongest supported AEAD cipher"),
    NONE("none", "TLS-Only (External Core)", "Relies on outer TLS 1.3 transport in external Xray core")
}

enum class TlsFingerprint(val wireValue: String, val label: String) {
    CHROME("chrome", "Chrome 131"),
    SAFARI("safari", "iOS Safari"),
    FIREFOX("firefox", "Firefox"),
    RANDOM("randomized", "Randomized uTLS")
}

enum class RoutingMode(val label: String, val description: String) {
    BYPASS_LAN("Bypass Local LAN", "Exclude private LAN subnets from VPN routes while tunneling internet IPv4"),
    GLOBAL("Global Tunnel", "Route all device IPv4 traffic through the encrypted bridge (IPv6 leak-guarded)")
}

enum class AppearanceMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System")
}

enum class ProfileSortOption(val label: String) {
    RECENT("Recently Used"),
    LATENCY("Lowest Latency"),
    NAME("Name (A–Z)")
}

enum class PerformanceMode(
    val label: String,
    val badge: String,
    val summary: String,
    val mtu: Int,
    val socketBufferBytes: Int,
    val relayChunkBytes: Int,
    val tunSegmentBytes: Int
) {
    BALANCED(
        label = "Balanced",
        badge = "STD",
        summary = "Standard MTU 1500 & 64 KB buffers for everyday battery efficiency",
        mtu = 1500,
        socketBufferBytes = 65536,
        relayChunkBytes = 16384,
        tunSegmentBytes = 1360
    ),
    HIGH_SPEED(
        label = "High-Speed Data",
        badge = "TURBO",
        summary = "Jumbo MTU 9000, 256 KB TCP buffers, 48 KB frames & low-delay QoS",
        mtu = 9000,
        socketBufferBytes = 262144,
        relayChunkBytes = 49152,
        tunSegmentBytes = 8800
    )
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
            beginnerSummary = "Formats Phone Bridge HTTP-Upgrade Host/Path headers like YouTube traffic (outer HTTP header obfuscation; payload stays AEAD encrypted)."
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
            beginnerSummary = "Formats Phone Bridge HTTP-Upgrade Host/Path headers like Zoom streams (outer HTTP header obfuscation; payload stays AEAD encrypted)."
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
            beginnerSummary = "Formats Phone Bridge HTTP-Upgrade Host/Path headers like GitHub requests (outer HTTP header obfuscation; payload stays AEAD encrypted)."
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
            beginnerSummary = "Formats Phone Bridge HTTP-Upgrade headers with Cloudflare CDN request metadata (outer HTTP header obfuscation; payload stays AEAD encrypted)."
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
            beginnerSummary = "Specify a custom Host header & path for Phone Bridge obfuscation, or SNI for exported Xray profiles."
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
    val isFavorite: Boolean = false,
    val latencyMs: Long? = null,
    val lastTestedAt: Long? = null,
    val lastConnectedAt: Long? = null,
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

    /** Stable fingerprint for duplicate configuration detection (excludes display name & timestamps). */
    val endpointFingerprint: String
        get() = "${protocol.name}|${address.trim().lowercase()}|$port|${uuidOrPassword.trim()}|${transport.name}"
}

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

data class TelemetrySample(
    val timestampMillis: Long,
    val upBps: Long,
    val downBps: Long
)

data class LatencySample(
    val timestampMillis: Long,
    val rttMs: Long,
    val targetLabel: String
)

enum class ConnectionQualityTier(val label: String) {
    IDLE("Standby"),
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    DEGRADED("Degraded")
}

data class TelemetrySnapshot(
    val state: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val activeProfileId: String? = null,
    val activeProfileName: String? = null,
    val activeProtocol: ProtocolType? = null,
    val disguiseDomain: String = "www.youtube.com",
    val cipherLabel: String = "AES-256-GCM",
    val networkType: String = "Unknown",
    val upSpeedBps: Long = 0L,
    val downSpeedBps: Long = 0L,
    val peakUpBps: Long = 0L,
    val peakDownBps: Long = 0L,
    val totalUpBytes: Long = 0L,
    val totalDownBytes: Long = 0L,
    val connectedSinceMillis: Long? = null,
    val lastSuccessfulConnectionMillis: Long? = null,
    val latencyMs: Long? = null,
    val speedHistory: List<Long> = emptyList(),
    val bandwidthSamples: List<TelemetrySample> = emptyList(),
    val latencySamples: List<LatencySample> = emptyList(),
    val localProxyPort: Int = 10809,
    val activeFlowCount: Int = 0,
    val reconnectCount: Int = 0,
    val highSpeedMode: Boolean = false,
    val mtu: Int = 1500,
    val serverReachable: Boolean = false,
    val authVerified: Boolean = false,
    val tunnelVerified: Boolean = false,
    val trafficFlowing: Boolean = false,
    val forwardedPackets: Long = 0L,
    val droppedPackets: Long = 0L,
    val bypassedPackets: Long = 0L,
    val errorMessage: String? = null
) {
    val qualityTier: ConnectionQualityTier
        get() = when {
            state == VpnConnectionState.DISCONNECTED -> ConnectionQualityTier.IDLE
            state == VpnConnectionState.ERROR -> ConnectionQualityTier.DEGRADED
            state == VpnConnectionState.RECONNECTING -> ConnectionQualityTier.FAIR
            latencyMs == null -> if (tunnelVerified) ConnectionQualityTier.GOOD else ConnectionQualityTier.FAIR
            latencyMs < 60 -> ConnectionQualityTier.EXCELLENT
            latencyMs < 150 -> ConnectionQualityTier.GOOD
            latencyMs < 300 -> ConnectionQualityTier.FAIR
            else -> ConnectionQualityTier.DEGRADED
        }
}

data class PeerSession(
    val id: String,
    val deviceId: String? = null,
    val remoteIp: String,
    val disguiseDomain: String,
    val connectedAtMillis: Long,
    val bytesUp: Long,
    val bytesDown: Long,
    val authenticated: Boolean = true
)

enum class PairingStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED
}

data class PairingRequest(
    val requestId: String,
    val clientDeviceName: String,
    val remoteIp: String,
    val verificationCode: String,
    val requestedAtMillis: Long,
    val expiresAtMillis: Long,
    val status: PairingStatus = PairingStatus.PENDING
)

data class PairedDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceSecret: String,
    val pairedAtMillis: Long
)

data class ServerRuntimeState(
    val isRunning: Boolean = false,
    val isBinding: Boolean = false,
    val listenPort: Int = 10808,
    val uuid: String = "",
    val encryption: EncryptionCipher = EncryptionCipher.AES_256_GCM,
    val disguisePresetId: String = "youtube",
    val customDisguiseDomain: String = "",
    val wsPath: String = "/youtubei/v1/player",
    val lanDiscoveryEnabled: Boolean = true,
    val highSpeedMode: Boolean = false,
    val activeNetworkType: String = "Unknown",
    val localIps: List<NetworkInterfaceInfo> = emptyList(),
    val activePeers: List<PeerSession> = emptyList(),
    val pendingPairings: List<PairingRequest> = emptyList(),
    val pairedDevices: List<PairedDevice> = emptyList(),
    val authGateActive: Boolean = false,
    val authenticatedHandshakes: Long = 0L,
    val rejectedHandshakes: Long = 0L,
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

/**
 * Non-sensitive LAN server announcement returned over UDP discovery.
 * Never carries authentication secrets or private keys.
 */
data class DiscoveredLanServer(
    val serverFingerprint: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val disguisePresetId: String,
    val disguiseDomain: String,
    val encryption: EncryptionCipher,
    val wsPath: String,
    val pairingRequired: Boolean = true,
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
    val checkedAtMillis: Long = System.currentTimeMillis(),
    val errorMessage: String?
)

enum class SecurityCheckState(val label: String) {
    VERIFIED("Verified"),
    DISABLED("Disabled"),
    DEGRADED("Degraded"),
    UNKNOWN("Unknown"),
    NOT_APPLICABLE("Standby")
}

data class SecurityDiagnosticsReport(
    val vaultState: SecurityCheckState,
    val vaultDetail: String,
    val replayGuardState: SecurityCheckState,
    val replayGuardDetail: String,
    val proxyAuthDefenseState: SecurityCheckState,
    val proxyAuthDefenseDetail: String,
    val lanPairingState: SecurityCheckState,
    val lanPairingDetail: String,
    val transportSecurityState: SecurityCheckState,
    val transportSecurityDetail: String,
    val keystoreBacked: Boolean,
    val sensitiveProtectionEnabled: Boolean,
    val allowInsecureProfilesCount: Int,
    val unencryptedProfilesCount: Int,
    val checkedAtMillis: Long = System.currentTimeMillis()
)
