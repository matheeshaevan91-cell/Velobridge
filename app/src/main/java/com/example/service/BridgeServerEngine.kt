package com.example.service

import android.os.Build
import android.util.Base64
import com.example.core.crypto.BridgeCrypto
import com.example.core.network.NetworkDiagnostics
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.LogLevel
import com.example.domain.model.PairedDevice
import com.example.domain.model.PairingRequest
import com.example.domain.model.PairingStatus
import com.example.domain.model.PeerSession
import com.example.domain.model.PerformanceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Pure socket server engine backing [BridgeServerService]:
 * - Binds listener socket BEFORE reporting server active
 * - Zero-secret UDP discovery beacon (VELOBRIDGE_OFFER_V2)
 * - P-256 ECDH LAN challenge-response pairing with explicit approval, TTL expiry, and per-device secrets
 * - Mutual-auth HTTP-Upgrade obfuscated AEAD relay (TCP, DNS, and general UDP) with replay protection
 * - Concurrency cap and complete socket teardown on stop
 */
class BridgeServerEngine(
    private val scope: CoroutineScope,
    private val onPairedDeviceIssued: (PairedDevice) -> Unit = {},
    private val onTrafficTotals: (upBytes: Long, downBytes: Long) -> Unit = { _, _ -> },
    private val onPeerUpsert: (PeerSession) -> Unit = {},
    private val onPeerRemoved: (String) -> Unit = {},
    private val onHandshakeOutcome: (authenticated: Boolean) -> Unit = {},
    private val onPendingPairingChanged: (PairingRequest) -> Unit = {},
    private val onPairingStatusUpdated: (requestId: String, status: PairingStatus) -> Unit = { _, _ -> },
    private val onLog: (source: String, msg: String, level: LogLevel) -> Unit = { _, _, _ -> }
) {
    data class PendingPairingEntry(
        val requestId: String,
        val clientDeviceName: String,
        val remoteIp: String,
        val verificationCode: String,
        val pairKey: SecretKey,
        val requestedAtMillis: Long,
        val expiresAtMillis: Long,
        @Volatile var status: PairingStatus = PairingStatus.PENDING,
        @Volatile var consumed: Boolean = false
    )

    data class StartOutcome(
        val bound: Boolean,
        val boundPort: Int,
        val errorMessage: String? = null
    )

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var discoverySocket: DatagramSocket? = null

    private var acceptJob: Job? = null
    private var discoveryJob: Job? = null

    private val activeClientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val pendingPairings = ConcurrentHashMap<String, PendingPairingEntry>()
    private val pairedDevices = ConcurrentHashMap<String, PairedDevice>()
    private var peerSemaphore = Semaphore(MAX_CONCURRENT_PEERS)

    private val totalUp = AtomicLong(0L)
    private val totalDown = AtomicLong(0L)
    private val lastFlushMs = AtomicLong(0L)

    @Volatile
    private var activePort: Int = 10808

    @Volatile
    private var activeDisguiseId: String = "youtube"

    @Volatile
    private var activeDisguiseDomain: String = "www.youtube.com"

    @Volatile
    private var activeWsPath: String = "/youtubei/v1/player"

    @Volatile
    private var activeCipher: EncryptionCipher = EncryptionCipher.AES_256_GCM

    @Volatile
    private var masterUuid: String = ""

    fun syncPairedDevices(devices: List<PairedDevice>) {
        pairedDevices.clear()
        for (d in devices) {
            if (d.deviceId.isNotBlank() && d.deviceSecret.isNotBlank()) {
                pairedDevices[d.deviceId] = d
            }
        }
    }

    fun revokePairedDevice(deviceId: String): Boolean {
        return pairedDevices.remove(deviceId) != null
    }

    fun approvePairing(requestId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val entry = pendingPairings[requestId] ?: return false
        if (entry.expiresAtMillis <= nowMillis) {
            entry.status = PairingStatus.EXPIRED
            pendingPairings.remove(requestId)
            onPairingStatusUpdated(requestId, PairingStatus.EXPIRED)
            return false
        }
        if (entry.status != PairingStatus.PENDING) return false
        entry.status = PairingStatus.APPROVED
        onPairingStatusUpdated(requestId, PairingStatus.APPROVED)
        onLog("Pairing", "Approved LAN pairing for ${entry.clientDeviceName} (${entry.remoteIp})", LogLevel.SUCCESS)
        return true
    }

    fun denyPairing(requestId: String): Boolean {
        val entry = pendingPairings[requestId] ?: return false
        entry.status = PairingStatus.DENIED
        onPairingStatusUpdated(requestId, PairingStatus.DENIED)
        onLog("Pairing", "Denied LAN pairing for ${entry.clientDeviceName} (${entry.remoteIp})", LogLevel.WARNING)
        return true
    }

    fun start(
        port: Int,
        uuid: String,
        disguiseId: String,
        effectiveDomain: String,
        wsPath: String,
        cipher: EncryptionCipher,
        lanDiscovery: Boolean,
        highSpeed: Boolean,
        maxConcurrentPeers: Int = MAX_CONCURRENT_PEERS,
        discoveryBindPort: Int = NetworkDiagnostics.LAN_DISCOVERY_PORT
    ): StartOutcome {
        stop()
        totalUp.set(0L)
        totalDown.set(0L)
        masterUuid = uuid
        activePort = port
        activeDisguiseId = disguiseId
        activeDisguiseDomain = effectiveDomain
        activeWsPath = wsPath
        activeCipher = cipher
        peerSemaphore = Semaphore(maxConcurrentPeers.coerceIn(1, MAX_CONCURRENT_PEERS))

        val perfMode = if (highSpeed) PerformanceMode.HIGH_SPEED else PerformanceMode.BALANCED
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                if (highSpeed) {
                    receiveBufferSize = perfMode.socketBufferBytes
                }
                bind(InetSocketAddress("0.0.0.0", port))
            }
        } catch (t: Throwable) {
            val msg = t.localizedMessage ?: "Failed to bind port $port"
            onLog("Server", "Port bind failed on :$port ($msg)", LogLevel.ERROR)
            return StartOutcome(bound = false, boundPort = port, errorMessage = msg)
        }

        serverSocket = ss
        val boundPort = ss.localPort
        activePort = boundPort

        onLog(
            "Server",
            "Listening on 0.0.0.0:$boundPort (Disguise: $effectiveDomain, Cipher: ${cipher.label}, Mode: ${perfMode.label})",
            LogLevel.SUCCESS
        )

        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !ss.isClosed) {
                    val client = ss.accept()
                    if (!peerSemaphore.tryAcquire()) {
                        runCatching { client.close() }
                        onHandshakeOutcome(false)
                        onLog("Security", "Rejected connection from ${client.inetAddress?.hostAddress}: max concurrent peers reached", LogLevel.WARNING)
                        continue
                    }
                    activeClientSockets.add(client)
                    launch {
                        try {
                            handleClientConnection(client, highSpeed)
                        } finally {
                            activeClientSockets.remove(client)
                            runCatching { client.close() }
                            peerSemaphore.release()
                        }
                    }
                }
            } catch (t: Throwable) {
                if (isActive && !ss.isClosed) {
                    onLog("Server", "Listener stopped: ${t.localizedMessage ?: "socket error"}", LogLevel.ERROR)
                }
            }
        }

        if (lanDiscovery) {
            discoveryJob = scope.launch(Dispatchers.IO) {
                runLanDiscoveryResponder(
                    discoveryBindPort = discoveryBindPort,
                    serverPort = boundPort,
                    uuid = uuid,
                    disguiseId = disguiseId,
                    effectiveDomain = effectiveDomain,
                    cipher = cipher,
                    wsPath = wsPath
                )
            }
        }

        return StartOutcome(bound = true, boundPort = boundPort)
    }

    fun stop() {
        acceptJob?.cancel()
        discoveryJob?.cancel()
        acceptJob = null
        discoveryJob = null
        runCatching { serverSocket?.close() }
        runCatching { discoverySocket?.close() }
        serverSocket = null
        discoverySocket = null
        val snapshot = activeClientSockets.toList()
        activeClientSockets.clear()
        snapshot.forEach { sock -> runCatching { sock.close() } }
        pendingPairings.clear()
    }

    private fun runLanDiscoveryResponder(
        discoveryBindPort: Int,
        serverPort: Int,
        uuid: String,
        disguiseId: String,
        effectiveDomain: String,
        cipher: EncryptionCipher,
        wsPath: String
    ) {
        try {
            val ds = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(discoveryBindPort))
            }
            discoverySocket = ds
            onLog("LAN", "Zero-secret discovery beacon active on UDP :$discoveryBindPort", LogLevel.INFO)
            val fingerprint = BridgeCrypto.sha256Fingerprint(uuid)
            val buf = ByteArray(512)
            while (scope.isActive && !ds.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                ds.receive(pkt)
                val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                if (msg == NetworkDiagnostics.LAN_DISCOVERY_MAGIC) {
                    val offer = JSONObject().apply {
                        put("magic", NetworkDiagnostics.LAN_OFFER_MAGIC)
                        put("serverFingerprint", fingerprint)
                        put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "VeloBridge Host" })
                        put("port", serverPort)
                        put("disguiseId", disguiseId)
                        put("disguiseDomain", effectiveDomain)
                        put("cipher", cipher.wireValue)
                        put("wsPath", wsPath)
                        put("pairingRequired", true)
                    }.toString().toByteArray(Charsets.UTF_8)
                    ds.send(DatagramPacket(offer, offer.size, pkt.address, pkt.port))
                    onLog("LAN", "Sent zero-secret discovery offer to ${pkt.address.hostAddress}", LogLevel.INFO)
                }
            }
        } catch (_: Throwable) {
            // Closed cleanly on shutdown
        }
    }

    private fun handleClientConnection(client: Socket, serverHighSpeed: Boolean) {
        val peerId = UUID.randomUUID().toString().take(8)
        val remoteIp = client.inetAddress?.hostAddress ?: "unknown"
        try {
            client.soTimeout = 30000
            client.tcpNoDelay = true
            val bufferedIn = BufferedInputStream(client.getInputStream(), if (serverHighSpeed) 49152 else 16384)
            bufferedIn.mark(32)
            val peekBuf = ByteArray(16)
            val peekRead = bufferedIn.read(peekBuf)
            if (peekRead <= 0) return
            bufferedIn.reset()

            // Strictly reject unauthenticated SOCKS5 probes
            if (peekBuf[0].toInt() and 0xFF == 0x05) {
                runCatching {
                    client.getOutputStream().write(byteArrayOf(0x05, 0xFF.toByte()))
                    client.getOutputStream().flush()
                }
                onHandshakeOutcome(false)
                onLog("Security", "Blocked unauthenticated SOCKS5 probe from $remoteIp", LogLevel.WARNING)
                return
            }

            val peekPrefix = String(peekBuf, 0, peekRead, Charsets.UTF_8)
            if (peekPrefix.startsWith("PAIR_REQUEST:")) {
                handlePairingRequestLine(readSingleLine(bufferedIn), remoteIp, client)
                return
            }
            if (peekPrefix.startsWith("PAIR_CHECK:")) {
                handlePairingCheckLine(readSingleLine(bufferedIn), client)
                return
            }

            // Read HTTP-Upgrade disguise header block
            val headers = readHttpHeaders(bufferedIn)
            if (headers.isEmpty()) return

            val headerMap = headers.drop(1).mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx > 0) line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim() else null
            }.toMap()

            val hostHeader = headerMap["host"] ?: activeDisguiseDomain
            val authToken = headerMap["x-velo-auth"]
            val authNonce = headerMap["x-velo-nonce"]
            val clientEcdhPub = headerMap["x-velo-ecdh"].orEmpty()
            val turboPeer = serverHighSpeed || headerMap["x-velo-mode"] == "turbo"
            val perfMode = if (turboPeer) PerformanceMode.HIGH_SPEED else PerformanceMode.BALANCED

            if (turboPeer) {
                runCatching {
                    client.sendBufferSize = perfMode.socketBufferBytes
                    client.receiveBufferSize = perfMode.socketBufferBytes
                    client.trafficClass = 0x10
                }
            }

            val authMatch = authenticatePeer(
                authToken = authToken,
                hostHeader = hostHeader,
                authNonce = authNonce,
                clientEcdhPub = clientEcdhPub
            )
            if (authMatch == null) {
                runCatching {
                    client.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray())
                    client.getOutputStream().flush()
                }
                onHandshakeOutcome(false)
                onLog("Security", "Rejected unauthorized or replayed request from $remoteIp", LogLevel.WARNING)
                return
            }
            onHandshakeOutcome(true)

            val cipher = activeCipher
            val sessionKey: SecretKey
            val upgradeReply = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("X-Velo-Mode: ").append(if (turboPeer) "turbo" else "balanced").append("\r\n")
                append("X-Velo-Cipher: ").append(cipher.wireValue).append("\r\n")
                if (clientEcdhPub.isNotBlank()) {
                    val serverKp = BridgeCrypto.generateEcdhKeyPair()
                    val serverPubB64 = BridgeCrypto.encodePublicKey(serverKp.public)
                    val serverNonce = BridgeCrypto.generateAuthNonce()
                    val shared = BridgeCrypto.computeEcdhSharedSecret(serverKp.private, clientEcdhPub)
                    sessionKey = BridgeCrypto.deriveEcdhSessionKey(
                        uuidOrSecret = authMatch.secret,
                        ecdhSharedSecret = shared,
                        clientNonce = authNonce.orEmpty(),
                        serverNonce = serverNonce,
                        cipher = cipher
                    )
                    val ack = BridgeCrypto.computeServerAuthAck(
                        uuidOrSecret = authMatch.secret,
                        disguiseDomain = hostHeader,
                        clientNonce = authNonce.orEmpty(),
                        serverNonce = serverNonce,
                        serverEcdhPubB64 = serverPubB64
                    )
                    append("X-Velo-Ecdh: ").append(serverPubB64).append("\r\n")
                    append("X-Velo-Server-Nonce: ").append(serverNonce).append("\r\n")
                    append("X-Velo-Server-Auth: ").append(ack).append("\r\n")
                } else {
                    sessionKey = BridgeCrypto.deriveKey(authMatch.secret)
                }
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)

            client.getOutputStream().write(upgradeReply)
            client.getOutputStream().flush()

            val dataIn = DataInputStream(bufferedIn)
            val dataOut = DataOutputStream(client.getOutputStream())
            val firstFrame = BridgeCrypto.readEncryptedFrame(dataIn, sessionKey, cipher) ?: return
            val command = String(firstFrame, Charsets.UTF_8)

            when {
                command == "PING" -> {
                    BridgeCrypto.writeEncryptedFrame(dataOut, "PONG".toByteArray(Charsets.UTF_8), sessionKey, cipher)
                }
                command.startsWith("TCP:") -> {
                    val parts = command.removePrefix("TCP:").split(":")
                    if (parts.size < 2) return
                    val targetPort = parts.last().toIntOrNull()?.takeIf { it in 1..65535 } ?: return
                    val targetHost = parts.dropLast(1).joinToString(":")

                    val session = PeerSession(
                        id = peerId,
                        deviceId = authMatch.deviceId,
                        remoteIp = remoteIp,
                        disguiseDomain = hostHeader,
                        connectedAtMillis = System.currentTimeMillis(),
                        bytesUp = 0L,
                        bytesDown = 0L,
                        authenticated = true
                    )
                    onPeerUpsert(session)
                    onLog(
                        "Bridge",
                        "Peer $remoteIp -> $targetHost:$targetPort (Host: $hostHeader${if (turboPeer) ", TURBO" else ""})",
                        LogLevel.INFO
                    )

                    Socket().use { targetSocket ->
                        targetSocket.tcpNoDelay = true
                        if (turboPeer) {
                            runCatching {
                                targetSocket.sendBufferSize = perfMode.socketBufferBytes
                                targetSocket.receiveBufferSize = perfMode.socketBufferBytes
                                targetSocket.trafficClass = 0x10
                            }
                        }
                        targetSocket.connect(InetSocketAddress(targetHost, targetPort), 8000)
                        BridgeCrypto.writeEncryptedFrame(dataOut, "OK".toByteArray(), sessionKey, cipher)
                        relayEncryptedTcp(
                            session = session,
                            clientIn = dataIn,
                            clientOut = dataOut,
                            targetSocket = targetSocket,
                            secretKey = sessionKey,
                            cipher = cipher,
                            relayChunkBytes = perfMode.relayChunkBytes
                        )
                    }
                }
                command.startsWith("UDP_DNS:") -> {
                    val parts = command.removePrefix("UDP_DNS:").split(":", limit = 3)
                    if (parts.size == 3) {
                        val dnsHost = parts[0]
                        val dnsPort = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: 53
                        val queryBytes = Base64.decode(parts[2], Base64.DEFAULT)
                        val replyBytes = forwardUdpDns(dnsHost, dnsPort, queryBytes)
                        BridgeCrypto.writeEncryptedFrame(dataOut, replyBytes, sessionKey, cipher)
                        recordTraffic(queryBytes.size.toLong(), replyBytes.size.toLong(), forceFlush = true)
                    }
                }
                command.startsWith("UDP_OPEN:") -> {
                    val parts = command.removePrefix("UDP_OPEN:").split(":")
                    if (parts.size < 2) return
                    val targetPort = parts.last().toIntOrNull()?.takeIf { it in 1..65535 } ?: return
                    val targetHost = parts.dropLast(1).joinToString(":")
                    relayEncryptedUdp(
                        targetHost = targetHost,
                        targetPort = targetPort,
                        clientIn = dataIn,
                        clientOut = dataOut,
                        secretKey = sessionKey,
                        cipher = cipher
                    )
                }
            }
        } catch (_: Throwable) {
            // Connection closed cleanly or aborted
        } finally {
            onPeerRemoved(peerId)
        }
    }

    private data class AuthMatch(val secret: String, val deviceId: String?)

    private fun authenticatePeer(
        authToken: String?,
        hostHeader: String,
        authNonce: String?,
        clientEcdhPub: String
    ): AuthMatch? {
        if (authToken.isNullOrBlank()) return null
        if (masterUuid.isNotBlank() &&
            BridgeCrypto.verifyAuthTokenWithReplayCheck(
                token = authToken,
                uuidOrSecret = masterUuid,
                disguiseDomain = hostHeader,
                nonce = authNonce,
                clientEcdhPubB64 = clientEcdhPub
            )
        ) {
            return AuthMatch(secret = masterUuid, deviceId = null)
        }
        for (device in pairedDevices.values) {
            if (BridgeCrypto.verifyAuthTokenWithReplayCheck(
                    token = authToken,
                    uuidOrSecret = device.deviceSecret,
                    disguiseDomain = hostHeader,
                    nonce = authNonce,
                    clientEcdhPubB64 = clientEcdhPub
                )
            ) {
                return AuthMatch(secret = device.deviceSecret, deviceId = device.deviceId)
            }
        }
        return null
    }

    private fun handlePairingRequestLine(line: String, remoteIp: String, client: Socket) {
        val now = System.currentTimeMillis()
        pendingPairings.entries.removeIf { it.value.expiresAtMillis <= now }
        if (pendingPairings.size >= MAX_PENDING_PAIRINGS) {
            val errJson = JSONObject().put("status", "ERROR").put("error", "Too many pending pairing requests").toString()
            client.getOutputStream().write("$errJson\n".toByteArray(Charsets.UTF_8))
            client.getOutputStream().flush()
            return
        }

        val rawJson = line.removePrefix("PAIR_REQUEST:").trim()
        val req = JSONObject(rawJson)
        val clientDeviceName = req.optString("clientDeviceName", "Client Phone").take(64)
        val clientEcdhPub = req.getString("clientEcdhPub")
        val clientNonce = req.getString("clientNonce")
        if (clientNonce.length < 12) {
            val errJson = JSONObject().put("status", "ERROR").put("error", "Invalid pairing nonce").toString()
            client.getOutputStream().write("$errJson\n".toByteArray(Charsets.UTF_8))
            client.getOutputStream().flush()
            return
        }

        val serverKp = BridgeCrypto.generateEcdhKeyPair()
        val serverPubB64 = BridgeCrypto.encodePublicKey(serverKp.public)
        val serverNonce = BridgeCrypto.generateAuthNonce()
        val shared = BridgeCrypto.computeEcdhSharedSecret(serverKp.private, clientEcdhPub)
        val pairKey = BridgeCrypto.derivePairingKey(shared, clientNonce, serverNonce)
        val sasCode = BridgeCrypto.computeSasCode(shared, clientNonce, serverNonce)
        val requestId = UUID.randomUUID().toString()
        val expiresAt = now + PAIRING_TTL_MS

        val entry = PendingPairingEntry(
            requestId = requestId,
            clientDeviceName = clientDeviceName,
            remoteIp = remoteIp,
            verificationCode = sasCode,
            pairKey = pairKey,
            requestedAtMillis = now,
            expiresAtMillis = expiresAt
        )
        pendingPairings[requestId] = entry
        onPendingPairingChanged(
            PairingRequest(
                requestId = requestId,
                clientDeviceName = clientDeviceName,
                remoteIp = remoteIp,
                verificationCode = sasCode,
                requestedAtMillis = now,
                expiresAtMillis = expiresAt,
                status = PairingStatus.PENDING
            )
        )
        onLog("Pairing", "Pairing challenge $sasCode requested by $clientDeviceName ($remoteIp)", LogLevel.INFO)

        val resp = JSONObject().apply {
            put("status", "PENDING")
            put("requestId", requestId)
            put("serverEcdhPub", serverPubB64)
            put("serverNonce", serverNonce)
            put("expiresAtMillis", expiresAt)
        }.toString()
        client.getOutputStream().write("$resp\n".toByteArray(Charsets.UTF_8))
        client.getOutputStream().flush()
    }

    private fun handlePairingCheckLine(line: String, client: Socket) {
        val rawJson = line.removePrefix("PAIR_CHECK:").trim()
        val req = JSONObject(rawJson)
        val requestId = req.optString("requestId")
        val proof = req.optString("proof")
        val entry = pendingPairings[requestId]
        val now = System.currentTimeMillis()

        val respJson = JSONObject()
        if (entry == null) {
            respJson.put("status", "EXPIRED").put("error", "Unknown or already consumed pairing request")
        } else {
            val expectedProof = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(entry.pairKey.encoded, "HmacSHA256"))
                val sig = doFinal("PAIR_CHECK:$requestId".toByteArray(Charsets.UTF_8))
                Base64.encodeToString(sig, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            }
            if (!MessageDigest.isEqual(expectedProof.toByteArray(Charsets.UTF_8), proof.toByteArray(Charsets.UTF_8))) {
                respJson.put("status", "DENIED").put("error", "Invalid pairing proof")
                onHandshakeOutcome(false)
            } else if (entry.expiresAtMillis <= now && entry.status == PairingStatus.PENDING) {
                entry.status = PairingStatus.EXPIRED
                pendingPairings.remove(requestId)
                onPairingStatusUpdated(requestId, PairingStatus.EXPIRED)
                respJson.put("status", "EXPIRED")
            } else {
                when (entry.status) {
                    PairingStatus.PENDING -> respJson.put("status", "PENDING")
                    PairingStatus.DENIED -> {
                        pendingPairings.remove(requestId)
                        respJson.put("status", "DENIED")
                    }
                    PairingStatus.EXPIRED -> {
                        pendingPairings.remove(requestId)
                        respJson.put("status", "EXPIRED")
                    }
                    PairingStatus.APPROVED -> {
                        if (entry.consumed) {
                            pendingPairings.remove(requestId)
                            respJson.put("status", "EXPIRED").put("error", "Pairing token already redeemed")
                        } else {
                            entry.consumed = true
                            pendingPairings.remove(requestId)
                            val deviceId = UUID.randomUUID().toString()
                            val deviceSecret = UUID.randomUUID().toString() + "-" + BridgeCrypto.generateAuthNonce()
                            val device = PairedDevice(
                                deviceId = deviceId,
                                deviceName = entry.clientDeviceName,
                                deviceSecret = deviceSecret,
                                pairedAtMillis = now
                            )
                            pairedDevices[deviceId] = device
                            onPairedDeviceIssued(device)

                            val bundleJson = JSONObject().apply {
                                put("deviceId", deviceId)
                                put("deviceSecret", deviceSecret)
                                put("port", activePort)
                                put("disguiseId", activeDisguiseId)
                                put("disguiseDomain", activeDisguiseDomain)
                                put("cipher", activeCipher.wireValue)
                                put("wsPath", activeWsPath)
                            }.toString().toByteArray(Charsets.UTF_8)

                            val encryptedBundle = BridgeCrypto.encryptFrame(
                                plaintext = bundleJson,
                                secretKey = entry.pairKey,
                                cipherMode = EncryptionCipher.AES_256_GCM
                            )
                            respJson.put("status", "APPROVED")
                            respJson.put("encryptedBundle", Base64.encodeToString(encryptedBundle, Base64.NO_WRAP))
                        }
                    }
                }
            }
        }
        client.getOutputStream().write("${respJson}\n".toByteArray(Charsets.UTF_8))
        client.getOutputStream().flush()
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

    private fun relayEncryptedUdp(
        targetHost: String,
        targetPort: Int,
        clientIn: DataInputStream,
        clientOut: DataOutputStream,
        secretKey: SecretKey,
        cipher: EncryptionCipher
    ) {
        DatagramSocket().use { ds ->
            ds.soTimeout = 45_000
            val addr = InetAddress.getByName(targetHost)
            BridgeCrypto.writeEncryptedFrame(clientOut, "OK".toByteArray(), secretKey, cipher)

            val rxThread = Thread {
                val buf = ByteArray(4096)
                try {
                    while (!ds.isClosed) {
                        val pkt = DatagramPacket(buf, buf.size)
                        ds.receive(pkt)
                        val slice = pkt.data.copyOf(pkt.length)
                        BridgeCrypto.writeEncryptedFrame(clientOut, slice, secretKey, cipher)
                        recordTraffic(0L, slice.size.toLong())
                    }
                } catch (_: Throwable) {
                    runCatching { ds.close() }
                }
            }
            rxThread.start()
            try {
                while (!ds.isClosed) {
                    val datagram = BridgeCrypto.readEncryptedFrame(clientIn, secretKey, cipher) ?: break
                    ds.send(DatagramPacket(datagram, datagram.size, addr, targetPort))
                    recordTraffic(datagram.size.toLong(), 0L)
                }
            } catch (_: Throwable) {
            } finally {
                recordTraffic(0L, 0L, forceFlush = true)
                runCatching { ds.close() }
                runCatching { rxThread.join(300) }
            }
        }
    }

    private fun relayEncryptedTcp(
        session: PeerSession,
        clientIn: DataInputStream,
        clientOut: DataOutputStream,
        targetSocket: Socket,
        secretKey: SecretKey,
        cipher: EncryptionCipher,
        relayChunkBytes: Int
    ) {
        val targetIn = targetSocket.getInputStream()
        val targetOut = targetSocket.getOutputStream()
        var peerUp = 0L
        var peerDown = 0L
        var lastUiUpdateMs = 0L

        val upThread = Thread {
            try {
                while (!targetSocket.isClosed) {
                    val plain = BridgeCrypto.readEncryptedFrame(clientIn, secretKey, cipher) ?: break
                    targetOut.write(plain)
                    targetOut.flush()
                    peerUp += plain.size
                    recordTraffic(plain.size.toLong(), 0L)
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdateMs > 250L) {
                        lastUiUpdateMs = now
                        onPeerUpsert(session.copy(bytesUp = peerUp, bytesDown = peerDown))
                    }
                }
                runCatching { targetSocket.shutdownOutput() }
            } catch (_: Throwable) {
                runCatching { targetSocket.close() }
            }
        }
        upThread.start()

        try {
            val buf = ByteArray(relayChunkBytes)
            while (!targetSocket.isClosed) {
                val read = targetIn.read(buf)
                if (read <= 0) break
                val slice = buf.copyOf(read)
                BridgeCrypto.writeEncryptedFrame(clientOut, slice, secretKey, cipher)
                peerDown += read
                recordTraffic(0L, read.toLong())
                val now = System.currentTimeMillis()
                if (now - lastUiUpdateMs > 250L) {
                    lastUiUpdateMs = now
                    onPeerUpsert(session.copy(bytesUp = peerUp, bytesDown = peerDown))
                }
            }
        } catch (_: Throwable) {
        } finally {
            recordTraffic(0L, 0L, forceFlush = true)
            runCatching { targetSocket.close() }
            runCatching { upThread.join(300) }
        }
    }

    private fun recordTraffic(upDelta: Long, downDelta: Long, forceFlush: Boolean = false) {
        val newUp = totalUp.addAndGet(upDelta)
        val newDown = totalDown.addAndGet(downDelta)
        val now = System.currentTimeMillis()
        val last = lastFlushMs.get()
        if (forceFlush || now - last >= 250L) {
            if (lastFlushMs.compareAndSet(last, now) || forceFlush) {
                onTrafficTotals(newUp, newDown)
            }
        }
    }

    private fun readSingleLine(input: BufferedInputStream): String {
        val baos = ByteArrayOutputStream()
        while (baos.size() < 4096) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
        }
        return baos.toString(Charsets.UTF_8.name())
    }

    private fun readHttpHeaders(input: BufferedInputStream): List<String> {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        var totalBytes = 0
        while (lines.size < 40 && totalBytes < 8192) {
            val b = input.read()
            if (b == -1) break
            totalBytes++
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
        }
        return lines
    }

    companion object {
        const val MAX_CONCURRENT_PEERS = 64
        const val MAX_PENDING_PAIRINGS = 5
        const val PAIRING_TTL_MS = 90_000L
    }
}
