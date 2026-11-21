package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.crypto.BridgeCrypto
import com.example.core.network.NetworkDiagnostics
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.LogLevel
import com.example.domain.model.PeerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

class BridgeServerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null
    private var discoveryJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null

    private val totalUp = AtomicLong(0L)
    private val totalDown = AtomicLong(0L)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServerInternal()
                stopSelf()
                return START_NOT_STICKY
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

                startForegroundWithNotification(port, DisguiseCatalog.resolveEffectiveDomain(disguiseId, customDomain))
                startServerInternal(
                    port = port,
                    uuid = uuid,
                    disguiseId = disguiseId,
                    customDomain = customDomain,
                    wsPath = wsPath,
                    cipher = cipher,
                    lanDiscovery = lanDiscovery
                )
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification(port: Int, disguiseDomain: String) {
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

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("VeloBridge Server Active (:$port)")
            .setContentText("Disguised as $disguiseDomain • Ready for client phone")
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
        lanDiscovery: Boolean
    ) {
        stopSocketsOnly()
        totalUp.set(0L)
        totalDown.set(0L)
        val ips = NetworkDiagnostics.getLocalIpv4Addresses()
        val effectiveDomain = DisguiseCatalog.resolveEffectiveDomain(disguiseId, customDomain)
        val secretKey = BridgeCrypto.deriveKey(uuid)

        TunnelRuntimeBus.updateServerState {
            it.copy(
                isRunning = true,
                listenPort = port,
                uuid = uuid,
                encryption = cipher,
                disguisePresetId = disguiseId,
                customDisguiseDomain = customDomain,
                wsPath = wsPath,
                lanDiscoveryEnabled = lanDiscovery,
                localIps = ips,
                activePeers = emptyList(),
                totalBytesUp = 0L,
                totalBytesDown = 0L,
                startedAtMillis = System.currentTimeMillis(),
                errorMessage = null
            )
        }

        acceptJob = serviceScope.launch {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
                serverSocket = ss
                TunnelRuntimeBus.appendLog(
                    "Server",
                    "Listening on 0.0.0.0:$port (Disguise: $effectiveDomain, Cipher: ${cipher.label})",
                    LogLevel.SUCCESS
                )

                while (isActive && !ss.isClosed) {
                    val client = ss.accept()
                    launch {
                        handleClientConnection(client, uuid, secretKey, cipher, effectiveDomain)
                    }
                }
            } catch (t: Throwable) {
                if (isActive) {
                    val msg = t.localizedMessage ?: "Server socket error"
                    TunnelRuntimeBus.appendLog("Server", "Listener stopped: $msg", LogLevel.ERROR)
                    TunnelRuntimeBus.updateServerState {
                        it.copy(isRunning = false, errorMessage = msg)
                    }
                }
            }
        }

        if (lanDiscovery) {
            discoveryJob = serviceScope.launch {
                runLanDiscoveryResponder(port, uuid, disguiseId, effectiveDomain, cipher, wsPath)
            }
        }
    }

    private suspend fun runLanDiscoveryResponder(
        port: Int,
        uuid: String,
        disguiseId: String,
        effectiveDomain: String,
        cipher: EncryptionCipher,
        wsPath: String
    ) {
        try {
            val ds = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(NetworkDiagnostics.LAN_DISCOVERY_PORT))
            }
            discoverySocket = ds
            TunnelRuntimeBus.appendLog(
                "LAN",
                "Auto-discovery beacon active on UDP :${NetworkDiagnostics.LAN_DISCOVERY_PORT}",
                LogLevel.INFO
            )
            val buf = ByteArray(512)
            while (serviceScope.isActive && !ds.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                ds.receive(pkt)
                val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                if (msg == NetworkDiagnostics.LAN_DISCOVERY_MAGIC) {
                    val offer = JSONObject().apply {
                        put("magic", "VELOBRIDGE_OFFER_V1")
                        put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                        put("port", port)
                        put("uuid", uuid)
                        put("disguiseId", disguiseId)
                        put("disguiseDomain", effectiveDomain)
                        put("cipher", cipher.wireValue)
                        put("wsPath", wsPath)
                    }.toString().toByteArray(Charsets.UTF_8)

                    val reply = DatagramPacket(offer, offer.size, pkt.address, pkt.port)
                    ds.send(reply)
                    TunnelRuntimeBus.appendLog(
                        "LAN",
                        "Sent pairing offer to ${pkt.address.hostAddress}",
                        LogLevel.INFO
                    )
                }
            }
        } catch (_: Throwable) {
            // Socket closed cleanly on stop
        }
    }

    private fun handleClientConnection(
        client: Socket,
        uuid: String,
        secretKey: SecretKey,
        cipher: EncryptionCipher,
        defaultDisguiseDomain: String
    ) {
        val peerId = UUID.randomUUID().toString().take(8)
        val remoteIp = client.inetAddress?.hostAddress ?: "unknown"
        try {
            client.soTimeout = 30000
            client.tcpNoDelay = true
            val bufferedIn = BufferedInputStream(client.getInputStream())
            bufferedIn.mark(8)
            val firstByte = bufferedIn.read()
            if (firstByte == -1) {
                client.close()
                return
            }
            bufferedIn.reset()

            if (firstByte == 0x05) {
                handleSocks5Client(client, bufferedIn, peerId, remoteIp, defaultDisguiseDomain)
                return
            }

            // Read HTTP WebSocket Disguise Upgrade Request
            val headers = readHttpHeaders(bufferedIn)
            if (headers.isEmpty()) {
                client.close()
                return
            }
            val requestLine = headers.first()
            val headerMap = headers.drop(1).mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx > 0) line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim() else null
            }.toMap()

            val hostHeader = headerMap["host"] ?: defaultDisguiseDomain
            val authToken = headerMap["x-velo-auth"]

            if (authToken != null) {
                if (!BridgeCrypto.verifyAuthToken(authToken, uuid, hostHeader)) {
                    client.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray())
                    client.close()
                    TunnelRuntimeBus.appendLog("Server", "Rejected unauthorized peer $remoteIp", LogLevel.WARNING)
                    return
                }

                // Valid VeloBridge Encrypted WebSocket Tunnel
                val upgradeReply = (
                    "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "X-Velo-Cipher: ${cipher.wireValue}\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
                client.getOutputStream().write(upgradeReply)
                client.getOutputStream().flush()

                val dataIn = DataInputStream(bufferedIn)
                val dataOut = DataOutputStream(client.getOutputStream())
                val firstFrame = BridgeCrypto.readEncryptedFrame(dataIn, secretKey, cipher)
                    ?: run {
                        client.close()
                        return
                    }
                val command = String(firstFrame, Charsets.UTF_8)
                when {
                    command.startsWith("TCP:") -> {
                        val parts = command.removePrefix("TCP:").split(":")
                        if (parts.size < 2) {
                            client.close()
                            return
                        }
                        val targetPort = parts.last().toIntOrNull() ?: return
                        val targetHost = parts.dropLast(1).joinToString(":")

                        val session = PeerSession(
                            id = peerId,
                            remoteIp = remoteIp,
                            disguiseDomain = hostHeader,
                            connectedAtMillis = System.currentTimeMillis(),
                            bytesUp = 0L,
                            bytesDown = 0L
                        )
                        TunnelRuntimeBus.upsertPeer(session)
                        TunnelRuntimeBus.appendLog(
                            "Bridge",
                            "Peer $remoteIp -> $targetHost:$targetPort (Host: $hostHeader)",
                            LogLevel.INFO
                        )

                        val targetSocket = Socket().apply {
                            tcpNoDelay = true
                            connect(InetSocketAddress(targetHost, targetPort), 8000)
                        }
                        BridgeCrypto.writeEncryptedFrame(dataOut, "OK".toByteArray(), secretKey, cipher)

                        relayEncryptedBidirectional(
                            peerId = peerId,
                            session = session,
                            clientIn = dataIn,
                            clientOut = dataOut,
                            targetSocket = targetSocket,
                            secretKey = secretKey,
                            cipher = cipher
                        )
                    }
                    command.startsWith("UDP_DNS:") -> {
                        val parts = command.removePrefix("UDP_DNS:").split(":", limit = 3)
                        if (parts.size == 3) {
                            val dnsHost = parts[0]
                            val dnsPort = parts[1].toIntOrNull() ?: 53
                            val queryBytes = Base64.decode(parts[2], Base64.DEFAULT)
                            val replyBytes = forwardUdpDns(dnsHost, dnsPort, queryBytes)
                            BridgeCrypto.writeEncryptedFrame(dataOut, replyBytes, secretKey, cipher)
                            recordTraffic(queryBytes.size.toLong(), replyBytes.size.toLong())
                        }
                        client.close()
                    }
                    else -> client.close()
                }
            } else if (requestLine.startsWith("CONNECT ", ignoreCase = true)) {
                val target = requestLine.split(" ").getOrNull(1) ?: ""
                val host = target.substringBefore(":")
                val port = target.substringAfter(":", "443").toIntOrNull() ?: 443
                val targetSocket = Socket().apply {
                    connect(InetSocketAddress(host, port), 8000)
                }
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                relayPlainBidirectional(client, bufferedIn, targetSocket)
            } else {
                client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nOK".toByteArray())
                client.close()
            }
        } catch (_: Throwable) {
            runCatching { client.close() }
        } finally {
            TunnelRuntimeBus.removePeer(peerId)
        }
    }

    private fun forwardUdpDns(dnsHost: String, dnsPort: Int, query: ByteArray): ByteArray {
        return runCatching {
            DatagramSocket().use { ds ->
                ds.soTimeout = 3500
                val addr = InetAddress.getByName(dnsHost)
                ds.send(DatagramPacket(query, query.size, addr, dnsPort))
                val buf = ByteArray(1500)
                val resp = DatagramPacket(buf, buf.size)
                ds.receive(resp)
                resp.data.copyOf(resp.length)
            }
        }.getOrDefault(ByteArray(0))
    }

    private fun relayEncryptedBidirectional(
        peerId: String,
        session: PeerSession,
        clientIn: DataInputStream,
        clientOut: DataOutputStream,
        targetSocket: Socket,
        secretKey: SecretKey,
        cipher: EncryptionCipher
    ) {
        val targetIn = targetSocket.getInputStream()
        val targetOut = targetSocket.getOutputStream()
        var peerUp = 0L
        var peerDown = 0L

        val upThread = Thread {
            try {
                while (!targetSocket.isClosed) {
                    val plain = BridgeCrypto.readEncryptedFrame(clientIn, secretKey, cipher) ?: break
                    targetOut.write(plain)
                    targetOut.flush()
                    peerUp += plain.size
                    recordTraffic(plain.size.toLong(), 0L)
                    TunnelRuntimeBus.upsertPeer(session.copy(bytesUp = peerUp, bytesDown = peerDown))
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { targetSocket.close() }
            }
        }
        upThread.start()

        try {
            val buf = ByteArray(16384)
            while (!targetSocket.isClosed) {
                val read = targetIn.read(buf)
                if (read <= 0) break
                val slice = buf.copyOf(read)
                BridgeCrypto.writeEncryptedFrame(clientOut, slice, secretKey, cipher)
                peerDown += read
                recordTraffic(0L, read.toLong())
                TunnelRuntimeBus.upsertPeer(session.copy(bytesUp = peerUp, bytesDown = peerDown))
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { targetSocket.close() }
            runCatching { upThread.join(300) }
            TunnelRuntimeBus.removePeer(peerId)
        }
    }

    private fun handleSocks5Client(
        client: Socket,
        bufferedIn: BufferedInputStream,
        peerId: String,
        remoteIp: String,
        disguiseDomain: String
    ) {
        val dataIn = DataInputStream(bufferedIn)
        val out = client.getOutputStream()
        val ver = dataIn.readUnsignedByte()
        if (ver != 0x05) {
            client.close()
            return
        }
        val nMethods = dataIn.readUnsignedByte()
        val methods = ByteArray(nMethods)
        dataIn.readFully(methods)
        out.write(byteArrayOf(0x05, 0x00))
        out.flush()

        val reqVer = dataIn.readUnsignedByte()
        val cmd = dataIn.readUnsignedByte()
        dataIn.readUnsignedByte() // reserved
        val atyp = dataIn.readUnsignedByte()
        if (reqVer != 0x05 || cmd != 0x01) {
            out.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            client.close()
            return
        }
        val dstHost = when (atyp) {
            0x01 -> {
                val ip = ByteArray(4)
                dataIn.readFully(ip)
                InetAddress.getByAddress(ip).hostAddress ?: ""
            }
            0x03 -> {
                val len = dataIn.readUnsignedByte()
                val domain = ByteArray(len)
                dataIn.readFully(domain)
                String(domain, Charsets.UTF_8)
            }
            0x04 -> {
                val ip = ByteArray(16)
                dataIn.readFully(ip)
                InetAddress.getByAddress(ip).hostAddress ?: ""
            }
            else -> {
                client.close()
                return
            }
        }
        val dstPort = dataIn.readUnsignedShort()
        val target = Socket().apply { connect(InetSocketAddress(dstHost, dstPort), 8000) }
        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()

        val session = PeerSession(peerId, remoteIp, disguiseDomain, System.currentTimeMillis(), 0L, 0L)
        TunnelRuntimeBus.upsertPeer(session)
        relayPlainBidirectional(client, dataIn, target)
        TunnelRuntimeBus.removePeer(peerId)
    }

    private fun relayPlainBidirectional(
        client: Socket,
        clientIn: java.io.InputStream,
        target: Socket
    ) {
        val tOut = target.getOutputStream()
        val cOut = client.getOutputStream()
        val up = Thread {
            try {
                val buf = ByteArray(16384)
                while (true) {
                    val n = clientIn.read(buf)
                    if (n <= 0) break
                    tOut.write(buf, 0, n)
                    tOut.flush()
                    recordTraffic(n.toLong(), 0L)
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { target.close() }
            }
        }
        up.start()
        try {
            val tIn = target.getInputStream()
            val buf = ByteArray(16384)
            while (true) {
                val n = tIn.read(buf)
                if (n <= 0) break
                cOut.write(buf, 0, n)
                cOut.flush()
                recordTraffic(0L, n.toLong())
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { client.close() }
            runCatching { target.close() }
        }
    }

    private fun recordTraffic(upDelta: Long, downDelta: Long) {
        val newUp = totalUp.addAndGet(upDelta)
        val newDown = totalDown.addAndGet(downDelta)
        TunnelRuntimeBus.updateServerState {
            it.copy(totalBytesUp = newUp, totalBytesDown = newDown)
        }
    }

    private fun readHttpHeaders(input: BufferedInputStream): List<String> {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        var prevWasCr = false
        while (lines.size < 40) {
            val b = input.read()
            if (b == -1) break
            val ch = b.toChar()
            if (ch == '\n') {
                val line = sb.toString().trimEnd('\r')
                sb.clear()
                if (line.isEmpty()) break
                lines.add(line)
            } else {
                sb.append(ch)
                if (sb.length > 4096) break
            }
            prevWasCr = ch == '\r'
        }
        return lines
    }

    private fun stopSocketsOnly() {
        acceptJob?.cancel()
        discoveryJob?.cancel()
        runCatching { serverSocket?.close() }
        runCatching { discoverySocket?.close() }
        serverSocket = null
        discoverySocket = null
    }

    private fun stopServerInternal() {
        stopSocketsOnly()
        TunnelRuntimeBus.updateServerState {
            it.copy(
                isRunning = false,
                activePeers = emptyList(),
                startedAtMillis = null
            )
        }
        TunnelRuntimeBus.appendLog("Server", "Bridge server stopped", LogLevel.INFO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopSocketsOnly()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "velobridge_server_channel"
        const val NOTIFICATION_ID = 2002
        const val ACTION_START = "com.example.action.START_BRIDGE_SERVER"
        const val ACTION_STOP = "com.example.action.STOP_BRIDGE_SERVER"

        const val EXTRA_PORT = "extra_port"
        const val EXTRA_UUID = "extra_uuid"
        const val EXTRA_DISGUISE_ID = "extra_disguise_id"
        const val EXTRA_CUSTOM_DOMAIN = "extra_custom_domain"
        const val EXTRA_WS_PATH = "extra_ws_path"
        const val EXTRA_CIPHER = "extra_cipher"
        const val EXTRA_LAN_DISCOVERY = "extra_lan_discovery"
    }
}
