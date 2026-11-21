package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.VeloBridgeApp
import com.example.core.network.NetworkDiagnostics
import com.example.core.network.RouteCalculator
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.LogLevel
import com.example.domain.model.PerformanceMode
import com.example.domain.model.ProtocolType
import com.example.domain.model.TlsFingerprint
import com.example.domain.model.TransportType
import com.example.domain.model.V2RayProfile
import com.example.domain.model.VpnConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

class BridgeVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunBridge: TunPacketBridge? = null
    private var telemetryJob: Job? = null
    private var connectJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val totalUpBytes = AtomicLong(0L)
    private val totalDownBytes = AtomicLong(0L)
    private val activeFlowsCount = AtomicLong(0L)
    private val forwardedPackets = AtomicLong(0L)
    private val droppedPackets = AtomicLong(0L)
    private val bypassedPackets = AtomicLong(0L)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpnInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val profile = extractProfileFromIntent(intent)
                val dns = intent?.getStringExtra(EXTRA_DNS) ?: "1.1.1.1"
                val bypassLan = intent?.getBooleanExtra(EXTRA_BYPASS_LAN, true) ?: true
                val highSpeed = intent?.getBooleanExtra(EXTRA_HIGH_SPEED, false) ?: false
                val autoReconnect = intent?.getBooleanExtra(EXTRA_AUTO_RECONNECT, true) ?: true

                startForegroundNotification(profile, highSpeed, "Connecting…")
                startVpnInternal(profile, dns, bypassLan, highSpeed, autoReconnect)
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        TunnelRuntimeBus.appendLog("VPN", "System VPN permission revoked", LogLevel.WARNING)
        stopVpnInternal()
        stopSelf()
        super.onRevoke()
    }

    private fun startForegroundNotification(
        profile: V2RayProfile,
        highSpeed: Boolean,
        statusLabel: String
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            201,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            202,
            Intent(this, BridgeVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val modeSuffix = if (highSpeed) " • TURBO" else ""
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("VeloBridge $statusLabel$modeSuffix")
            .setContentText("${profile.name} • Disguise: ${profile.effectiveDisguiseDomain}")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startVpnInternal(
        profile: V2RayProfile,
        dnsServer: String,
        bypassLan: Boolean,
        highSpeed: Boolean,
        autoReconnect: Boolean
    ) {
        connectJob?.cancel()
        stopBridgeOnly()
        unregisterNetworkCallback()
        totalUpBytes.set(0L)
        totalDownBytes.set(0L)
        forwardedPackets.set(0L)
        droppedPackets.set(0L)
        bypassedPackets.set(0L)

        val perfMode = if (highSpeed) PerformanceMode.HIGH_SPEED else PerformanceMode.BALANCED
        val activeNetworkType = NetworkDiagnostics.getActiveNetworkType(this)

        if (!profile.protocol.supportLevel.isNativeTunnelSupported) {
            val errMsg = "${profile.protocol.label} supports profile import, editing, and SNI probing. Live encrypted VPN tunneling is built in for Phone Bridge (VELO_BRIDGE) profiles."
            TunnelRuntimeBus.appendLog("VPN", errMsg, LogLevel.WARNING)
            TunnelRuntimeBus.updateTelemetry {
                it.copy(
                    state = VpnConnectionState.ERROR,
                    activeProfileId = profile.id,
                    activeProfileName = profile.name,
                    activeProtocol = profile.protocol,
                    networkType = activeNetworkType,
                    serverReachable = false,
                    authVerified = false,
                    tunnelVerified = false,
                    trafficFlowing = false,
                    errorMessage = errMsg
                )
            }
            stopForegroundCompat()
            stopSelf()
            return
        }

        TunnelRuntimeBus.updateTelemetry {
            it.copy(
                state = VpnConnectionState.CONNECTING,
                activeProfileId = profile.id,
                activeProfileName = profile.name,
                activeProtocol = profile.protocol,
                disguiseDomain = profile.effectiveDisguiseDomain,
                cipherLabel = profile.encryption.label,
                networkType = activeNetworkType,
                upSpeedBps = 0L,
                downSpeedBps = 0L,
                totalUpBytes = 0L,
                totalDownBytes = 0L,
                forwardedPackets = 0L,
                droppedPackets = 0L,
                bypassedPackets = 0L,
                speedHistory = emptyList(),
                bandwidthSamples = emptyList(),
                highSpeedMode = highSpeed,
                mtu = perfMode.mtu,
                serverReachable = false,
                authVerified = false,
                tunnelVerified = false,
                trafficFlowing = false,
                errorMessage = null
            )
        }

        registerNetworkObserver(profile, highSpeed, autoReconnect)

        connectJob = serviceScope.launch {
            try {
                TunnelRuntimeBus.appendLog(
                    "VPN",
                    "Authenticating ${profile.address}:${profile.port} • Disguise: ${profile.effectiveDisguiseDomain} • ${perfMode.label} (MTU ${perfMode.mtu})",
                    LogLevel.INFO
                )

                val verifiedRtt = TunPacketBridge.verifyEncryptedTunnelHandshake(
                    vpnService = this@BridgeVpnService,
                    profile = profile,
                    highSpeedMode = highSpeed,
                    timeoutMs = 4500,
                    onStageUpdate = { reachable, authOk, tunnelOk ->
                        TunnelRuntimeBus.setConnectionStages(
                            serverReachable = reachable,
                            authVerified = authOk,
                            tunnelVerified = tunnelOk,
                            trafficFlowing = false
                        )
                    }
                )

                val builder = Builder()
                    .setSession("VeloBridge (${profile.effectiveDisguiseDomain})")
                    .setMtu(perfMode.mtu)
                    .addAddress("10.8.0.2", 24)
                    .addDnsServer(dnsServer.ifBlank { "1.1.1.1" })

                if (bypassLan) {
                    for (block in RouteCalculator.computePublicIpv4Routes()) {
                        builder.addRoute(block.addressString, block.prefixLen)
                    }
                } else {
                    builder.addRoute("0.0.0.0", 0)
                }

                // Capture IPv6 default route into TUN so TunPacketBridge can cleanly reject/fallback without leaking
                runCatching {
                    builder.addAddress("fd00:10:8::2", 64)
                    builder.addRoute("::", 0)
                }

                runCatching { builder.addDisallowedApplication(packageName) }

                val pfd = builder.establish()
                if (pfd == null) {
                    TunnelRuntimeBus.setVpnState(VpnConnectionState.ERROR, "System declined VPN interface creation.")
                    stopForegroundCompat()
                    stopSelf()
                    return@launch
                }
                tunInterface = pfd

                val tunIn = FileInputStream(pfd.fileDescriptor)
                val tunOut = FileOutputStream(pfd.fileDescriptor)

                val bridge = TunPacketBridge(
                    vpnService = this@BridgeVpnService,
                    tunInput = tunIn,
                    tunOutput = tunOut,
                    profile = profile,
                    bypassLan = bypassLan,
                    highSpeedMode = highSpeed,
                    scope = serviceScope,
                    onTrafficStats = { up, down, flows, fwd, drop, byp ->
                        if (up > 0L) totalUpBytes.addAndGet(up)
                        if (down > 0L) totalDownBytes.addAndGet(down)
                        activeFlowsCount.set(flows.toLong())
                        forwardedPackets.set(fwd)
                        droppedPackets.set(drop)
                        bypassedPackets.set(byp)
                    }
                )
                tunBridge = bridge
                bridge.start()

                val connectedAt = System.currentTimeMillis()
                runCatching {
                    (application as? VeloBridgeApp)?.repository?.recordSuccessfulConnection(profile.id, connectedAt)
                }

                TunnelRuntimeBus.updateTelemetry {
                    it.copy(
                        state = VpnConnectionState.CONNECTED,
                        connectedSinceMillis = connectedAt,
                        lastSuccessfulConnectionMillis = connectedAt,
                        latencyMs = verifiedRtt,
                        networkType = NetworkDiagnostics.getActiveNetworkType(this@BridgeVpnService),
                        highSpeedMode = highSpeed,
                        mtu = perfMode.mtu,
                        serverReachable = true,
                        authVerified = true,
                        tunnelVerified = true,
                        errorMessage = null
                    )
                }
                TunnelRuntimeBus.recordLatencySample(verifiedRtt, profile.name)
                startForegroundNotification(profile, highSpeed, "Protected")
                TunnelRuntimeBus.appendLog(
                    "VPN",
                    "Mutual ECDH + AEAD tunnel verified (${verifiedRtt}ms PONG) • Host: ${profile.effectiveDisguiseDomain} • MTU ${perfMode.mtu}",
                    LogLevel.SUCCESS
                )

                telemetryJob = launch {
                    var lastUp = 0L
                    var lastDown = 0L
                    while (isActive) {
                        delay(1000L)
                        val curUp = totalUpBytes.get()
                        val curDown = totalDownBytes.get()
                        val upRate = (curUp - lastUp).coerceAtLeast(0L)
                        val downRate = (curDown - lastDown).coerceAtLeast(0L)
                        lastUp = curUp
                        lastDown = curDown
                        TunnelRuntimeBus.recordBandwidthSample(
                            upBps = upRate,
                            downBps = downRate,
                            totalUpBytes = curUp,
                            totalDownBytes = curDown,
                            activeFlowCount = activeFlowsCount.get().toInt(),
                            forwardedPackets = forwardedPackets.get(),
                            droppedPackets = droppedPackets.get(),
                            bypassedPackets = bypassedPackets.get()
                        )
                    }
                }
            } catch (t: Throwable) {
                val msg = t.localizedMessage ?: "Encrypted tunnel verification failed"
                TunnelRuntimeBus.appendLog("VPN", msg, LogLevel.ERROR)
                TunnelRuntimeBus.updateTelemetry {
                    it.copy(
                        state = VpnConnectionState.ERROR,
                        tunnelVerified = false,
                        trafficFlowing = false,
                        errorMessage = msg
                    )
                }
                stopBridgeOnly()
                unregisterNetworkCallback()
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun registerNetworkObserver(
        profile: V2RayProfile,
        highSpeed: Boolean,
        autoReconnect: Boolean
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val netType = NetworkDiagnostics.getActiveNetworkType(this@BridgeVpnService)
                TunnelRuntimeBus.updateTelemetry { it.copy(networkType = netType) }
                val currentState = TunnelRuntimeBus.telemetry.value.state
                if (autoReconnect && currentState == VpnConnectionState.RECONNECTING) {
                    serviceScope.launch {
                        runCatching {
                            val rtt = TunPacketBridge.verifyEncryptedTunnelHandshake(
                                vpnService = this@BridgeVpnService,
                                profile = profile,
                                highSpeedMode = highSpeed,
                                timeoutMs = 4000,
                                onStageUpdate = { reachable, authOk, tunnelOk ->
                                    TunnelRuntimeBus.setConnectionStages(
                                        serverReachable = reachable,
                                        authVerified = authOk,
                                        tunnelVerified = tunnelOk,
                                        trafficFlowing = TunnelRuntimeBus.telemetry.value.trafficFlowing
                                    )
                                }
                            )
                            TunnelRuntimeBus.updateTelemetry {
                                it.copy(
                                    state = VpnConnectionState.CONNECTED,
                                    latencyMs = rtt,
                                    serverReachable = true,
                                    authVerified = true,
                                    tunnelVerified = true,
                                    reconnectCount = it.reconnectCount + 1,
                                    errorMessage = null
                                )
                            }
                            TunnelRuntimeBus.recordLatencySample(rtt, profile.name)
                            TunnelRuntimeBus.appendLog("VPN", "Reconnected on $netType (${rtt}ms)", LogLevel.SUCCESS)
                        }.onFailure { err ->
                            TunnelRuntimeBus.updateTelemetry {
                                it.copy(
                                    state = VpnConnectionState.ERROR,
                                    tunnelVerified = false,
                                    errorMessage = err.localizedMessage ?: "Reconnect failed"
                                )
                            }
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                val currentState = TunnelRuntimeBus.telemetry.value.state
                if (autoReconnect && currentState == VpnConnectionState.CONNECTED) {
                    TunnelRuntimeBus.updateTelemetry {
                        it.copy(
                            state = VpnConnectionState.RECONNECTING,
                            networkType = NetworkDiagnostics.getActiveNetworkType(this@BridgeVpnService)
                        )
                    }
                    TunnelRuntimeBus.appendLog("VPN", "Underlying network lost; waiting to reconnect", LogLevel.WARNING)
                }
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun extractProfileFromIntent(intent: Intent?): V2RayProfile {
        val protocol = intent?.getStringExtra(EXTRA_PROTOCOL)?.let {
            runCatching { ProtocolType.valueOf(it) }.getOrNull()
        } ?: ProtocolType.VELO_BRIDGE
        val cipher = intent?.getStringExtra(EXTRA_CIPHER)?.let {
            runCatching { EncryptionCipher.valueOf(it) }.getOrNull()
        } ?: EncryptionCipher.AES_256_GCM
        val transport = intent?.getStringExtra(EXTRA_TRANSPORT)?.let {
            runCatching { TransportType.valueOf(it) }.getOrNull()
        } ?: TransportType.WEBSOCKET
        val fp = intent?.getStringExtra(EXTRA_FINGERPRINT)?.let {
            runCatching { TlsFingerprint.valueOf(it) }.getOrNull()
        } ?: TlsFingerprint.CHROME

        return V2RayProfile(
            id = intent?.getStringExtra(EXTRA_PROFILE_ID) ?: "active",
            name = intent?.getStringExtra(EXTRA_PROFILE_NAME) ?: "Bridge Profile",
            protocol = protocol,
            address = intent?.getStringExtra(EXTRA_ADDRESS) ?: "127.0.0.1",
            port = intent?.getIntExtra(EXTRA_PORT, 10808) ?: 10808,
            uuidOrPassword = intent?.getStringExtra(EXTRA_UUID) ?: "",
            encryption = cipher,
            transport = transport,
            disguisePresetId = intent?.getStringExtra(EXTRA_DISGUISE_ID) ?: "youtube",
            customDisguiseDomain = intent?.getStringExtra(EXTRA_CUSTOM_DOMAIN) ?: "",
            sni = intent?.getStringExtra(EXTRA_SNI) ?: "www.youtube.com",
            hostHeader = intent?.getStringExtra(EXTRA_HOST_HEADER) ?: "www.youtube.com",
            wsPath = intent?.getStringExtra(EXTRA_WS_PATH) ?: "/youtubei/v1/player",
            tlsEnabled = intent?.getBooleanExtra(EXTRA_TLS, true) ?: true,
            tlsFingerprint = fp
        )
    }

    private fun stopBridgeOnly() {
        telemetryJob?.cancel()
        telemetryJob = null
        tunBridge?.stop()
        tunBridge = null
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopVpnInternal() {
        connectJob?.cancel()
        connectJob = null
        unregisterNetworkCallback()
        stopBridgeOnly()
        TunnelRuntimeBus.updateTelemetry {
            it.copy(
                state = VpnConnectionState.DISCONNECTED,
                upSpeedBps = 0L,
                downSpeedBps = 0L,
                connectedSinceMillis = null,
                activeFlowCount = 0,
                serverReachable = false,
                authVerified = false,
                tunnelVerified = false,
                trafficFlowing = false,
                errorMessage = null
            )
        }
        TunnelRuntimeBus.appendLog("VPN", "Tunnel disconnected", LogLevel.INFO)
        stopForegroundCompat()
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        stopBridgeOnly()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "velobridge_vpn_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.example.action.START_VPN"
        const val ACTION_STOP = "com.example.action.STOP_VPN"

        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_PROFILE_NAME = "extra_profile_name"
        const val EXTRA_PROTOCOL = "extra_protocol"
        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_UUID = "extra_uuid"
        const val EXTRA_CIPHER = "extra_cipher"
        const val EXTRA_TRANSPORT = "extra_transport"
        const val EXTRA_DISGUISE_ID = "extra_disguise_id"
        const val EXTRA_CUSTOM_DOMAIN = "extra_custom_domain"
        const val EXTRA_SNI = "extra_sni"
        const val EXTRA_HOST_HEADER = "extra_host_header"
        const val EXTRA_WS_PATH = "extra_ws_path"
        const val EXTRA_TLS = "extra_tls"
        const val EXTRA_FINGERPRINT = "extra_fingerprint"
        const val EXTRA_DNS = "extra_dns"
        const val EXTRA_BYPASS_LAN = "extra_bypass_lan"
        const val EXTRA_HIGH_SPEED = "extra_high_speed"
        const val EXTRA_AUTO_RECONNECT = "extra_auto_reconnect"

        fun createStartIntent(
            context: Context,
            profile: V2RayProfile,
            dnsServer: String,
            bypassLan: Boolean,
            highSpeedMode: Boolean,
            autoReconnect: Boolean = true
        ): Intent = Intent(context, BridgeVpnService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_PROFILE_ID, profile.id)
            putExtra(EXTRA_PROFILE_NAME, profile.name)
            putExtra(EXTRA_PROTOCOL, profile.protocol.name)
            putExtra(EXTRA_ADDRESS, profile.address)
            putExtra(EXTRA_PORT, profile.port)
            putExtra(EXTRA_UUID, profile.uuidOrPassword)
            putExtra(EXTRA_CIPHER, profile.encryption.name)
            putExtra(EXTRA_TRANSPORT, profile.transport.name)
            putExtra(EXTRA_DISGUISE_ID, profile.disguisePresetId)
            putExtra(EXTRA_CUSTOM_DOMAIN, profile.customDisguiseDomain)
            putExtra(EXTRA_SNI, profile.sni)
            putExtra(EXTRA_HOST_HEADER, profile.hostHeader)
            putExtra(EXTRA_WS_PATH, profile.wsPath)
            putExtra(EXTRA_TLS, profile.tlsEnabled)
            putExtra(EXTRA_FINGERPRINT, profile.tlsFingerprint.name)
            putExtra(EXTRA_DNS, dnsServer)
            putExtra(EXTRA_BYPASS_LAN, bypassLan)
            putExtra(EXTRA_HIGH_SPEED, highSpeedMode)
            putExtra(EXTRA_AUTO_RECONNECT, autoReconnect)
        }
    }
}
