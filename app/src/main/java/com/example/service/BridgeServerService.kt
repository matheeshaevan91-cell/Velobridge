package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.VeloBridgeApp
import com.example.core.network.NetworkDiagnostics
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.LogLevel
import com.example.domain.model.PerformanceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class BridgeServerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val engine: BridgeServerEngine by lazy {
        BridgeServerEngine(
            scope = serviceScope,
            onPairedDeviceIssued = { device ->
                serviceScope.launch {
                    runCatching {
                        (application as? VeloBridgeApp)?.repository?.addPairedDevice(device)
                        val updated = (application as? VeloBridgeApp)?.repository?.preferences?.first()?.pairedDevices
                        if (updated != null) {
                            TunnelRuntimeBus.setPairedDevices(updated)
                        }
                    }
                }
            },
            onTrafficTotals = { up, down ->
                TunnelRuntimeBus.updateServerState { it.copy(totalBytesUp = up, totalBytesDown = down) }
            },
            onPeerUpsert = { session -> TunnelRuntimeBus.upsertPeer(session) },
            onPeerRemoved = { peerId -> TunnelRuntimeBus.removePeer(peerId) },
            onHandshakeOutcome = { ok -> TunnelRuntimeBus.recordServerHandshake(ok) },
            onPendingPairingChanged = { req -> TunnelRuntimeBus.upsertPendingPairing(req) },
            onPairingStatusUpdated = { id, status -> TunnelRuntimeBus.updatePairingStatus(id, status) },
            onLog = { src, msg, level -> TunnelRuntimeBus.appendLog(src, msg, level) }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServerInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APPROVE_PAIRING -> {
                intent.getStringExtra(EXTRA_REQUEST_ID)?.let { engine.approvePairing(it) }
                return START_STICKY
            }
            ACTION_DENY_PAIRING -> {
                intent.getStringExtra(EXTRA_REQUEST_ID)?.let { engine.denyPairing(it) }
                return START_STICKY
            }
            ACTION_REVOKE_DEVICE -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                if (!deviceId.isNullOrBlank()) {
                    engine.revokePairedDevice(deviceId)
                    serviceScope.launch {
                        runCatching {
                            (application as? VeloBridgeApp)?.repository?.revokePairedDevice(deviceId)
                            val updated = (application as? VeloBridgeApp)?.repository?.preferences?.first()?.pairedDevices
                            if (updated != null) {
                                TunnelRuntimeBus.setPairedDevices(updated)
                            }
                        }
                    }
                    TunnelRuntimeBus.appendLog("Security", "Revoked paired device ($deviceId)", LogLevel.WARNING)
                }
                return START_STICKY
            }
            ACTION_START, null -> {
                val port = intent?.getIntExtra(EXTRA_PORT, 10808) ?: 10808
                val uuid = intent?.getStringExtra(EXTRA_UUID)?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()
                val disguiseId = intent?.getStringExtra(EXTRA_DISGUISE_ID) ?: "youtube"
                val customDomain = intent?.getStringExtra(EXTRA_CUSTOM_DOMAIN) ?: ""
                val wsPath = intent?.getStringExtra(EXTRA_WS_PATH) ?: "/youtubei/v1/player"
                val cipherName = intent?.getStringExtra(EXTRA_CIPHER) ?: EncryptionCipher.AES_256_GCM.name
                val cipher = runCatching { EncryptionCipher.valueOf(cipherName) }
                    .getOrDefault(EncryptionCipher.AES_256_GCM)
                val lanDiscovery = intent?.getBooleanExtra(EXTRA_LAN_DISCOVERY, true) ?: true
                val highSpeed = intent?.getBooleanExtra(EXTRA_HIGH_SPEED, false) ?: false

                startForegroundWithNotification(
                    port,
                    DisguiseCatalog.resolveEffectiveDomain(disguiseId, customDomain),
                    highSpeed,
                    binding = true
                )
                startServerInternal(
                    port = port,
                    uuid = uuid,
                    disguiseId = disguiseId,
                    customDomain = customDomain,
                    wsPath = wsPath,
                    cipher = cipher,
                    lanDiscovery = lanDiscovery,
                    highSpeed = highSpeed
                )
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification(
        port: Int,
        disguiseDomain: String,
        highSpeed: Boolean,
        binding: Boolean = false
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.server_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            101,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, BridgeServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val modeLabel = if (highSpeed) " • TURBO" else ""
        val title = if (binding) {
            "VeloBridge Server Starting$modeLabel (:$port)"
        } else {
            "VeloBridge Server Active$modeLabel (:$port)"
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText("Disguised as $disguiseDomain • Authenticated relay ready")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startServerInternal(
        port: Int,
        uuid: String,
        disguiseId: String,
        customDomain: String,
        wsPath: String,
        cipher: EncryptionCipher,
        lanDiscovery: Boolean,
        highSpeed: Boolean
    ) {
        engine.stop()
        unregisterNetworkCallback()

        val ips = NetworkDiagnostics.getLocalIpv4Addresses()
        val netType = NetworkDiagnostics.getActiveNetworkType(this)
        val effectiveDomain = DisguiseCatalog.resolveEffectiveDomain(disguiseId, customDomain)

        TunnelRuntimeBus.updateServerState {
            it.copy(
                isRunning = false,
                isBinding = true,
                authGateActive = false,
                listenPort = port,
                uuid = uuid,
                encryption = cipher,
                disguisePresetId = disguiseId,
                customDisguiseDomain = customDomain,
                wsPath = wsPath,
                lanDiscoveryEnabled = lanDiscovery,
                highSpeedMode = highSpeed,
                activeNetworkType = netType,
                localIps = ips,
                activePeers = emptyList(),
                pendingPairings = emptyList(),
                authenticatedHandshakes = 0L,
                rejectedHandshakes = 0L,
                totalBytesUp = 0L,
                totalBytesDown = 0L,
                startedAtMillis = null,
                errorMessage = null
            )
        }

        serviceScope.launch {
            val paired = runCatching {
                (application as? VeloBridgeApp)?.repository?.preferences?.first()?.pairedDevices
            }.getOrNull().orEmpty()
            engine.syncPairedDevices(paired)
            TunnelRuntimeBus.setPairedDevices(paired)

            val outcome = engine.start(
                port = port,
                uuid = uuid,
                disguiseId = disguiseId,
                effectiveDomain = effectiveDomain,
                wsPath = wsPath,
                cipher = cipher,
                lanDiscovery = lanDiscovery,
                highSpeed = highSpeed
            )

            if (!outcome.bound) {
                TunnelRuntimeBus.updateServerState {
                    it.copy(
                        isRunning = false,
                        isBinding = false,
                        authGateActive = false,
                        errorMessage = outcome.errorMessage ?: "Failed to bind port $port"
                    )
                }
                stopForegroundCompat()
                stopSelf()
                return@launch
            }

            registerNetworkCallback()
            TunnelRuntimeBus.updateServerState {
                it.copy(
                    isRunning = true,
                    isBinding = false,
                    authGateActive = true,
                    listenPort = outcome.boundPort,
                    startedAtMillis = System.currentTimeMillis(),
                    errorMessage = null
                )
            }
            startForegroundWithNotification(outcome.boundPort, effectiveDomain, highSpeed, binding = false)
        }
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                TunnelRuntimeBus.refreshLocalIps(NetworkDiagnostics.getActiveNetworkType(this@BridgeServerService))
            }

            override fun onLost(network: Network) {
                TunnelRuntimeBus.refreshLocalIps(NetworkDiagnostics.getActiveNetworkType(this@BridgeServerService))
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopServerInternal() {
        unregisterNetworkCallback()
        engine.stop()
        TunnelRuntimeBus.updateServerState {
            it.copy(
                isRunning = false,
                isBinding = false,
                authGateActive = false,
                activePeers = emptyList(),
                pendingPairings = emptyList(),
                startedAtMillis = null
            )
        }
        TunnelRuntimeBus.appendLog("Server", "Bridge server stopped", LogLevel.INFO)
        stopForegroundCompat()
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        engine.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "velobridge_server_channel"
        const val NOTIFICATION_ID = 2002
        const val ACTION_START = "com.example.action.START_BRIDGE_SERVER"
        const val ACTION_STOP = "com.example.action.STOP_BRIDGE_SERVER"
        const val ACTION_APPROVE_PAIRING = "com.example.action.APPROVE_PAIRING"
        const val ACTION_DENY_PAIRING = "com.example.action.DENY_PAIRING"
        const val ACTION_REVOKE_DEVICE = "com.example.action.REVOKE_DEVICE"

        const val EXTRA_PORT = "extra_port"
        const val EXTRA_UUID = "extra_uuid"
        const val EXTRA_DISGUISE_ID = "extra_disguise_id"
        const val EXTRA_CUSTOM_DOMAIN = "extra_custom_domain"
        const val EXTRA_WS_PATH = "extra_ws_path"
        const val EXTRA_CIPHER = "extra_cipher"
        const val EXTRA_LAN_DISCOVERY = "extra_lan_discovery"
        const val EXTRA_HIGH_SPEED = "extra_high_speed"
        const val EXTRA_REQUEST_ID = "extra_request_id"
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }
}
