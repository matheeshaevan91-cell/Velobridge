package com.example.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.example.core.crypto.BridgeCrypto
import com.example.domain.model.DiscoveredLanServer
import com.example.domain.model.DisguiseProbeResult
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.NetworkInterfaceInfo
import com.example.domain.model.PairingStatus
import com.example.domain.model.V2RayProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.PrivateKey
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class LanPairingChallengeResult(
    val success: Boolean,
    val requestId: String = "",
    val verificationCode: String = "",
    val serverEcdhPub: String = "",
    val clientNonce: String = "",
    val serverNonce: String = "",
    val sharedSecret: ByteArray = ByteArray(0),
    val expiresAtMillis: Long = 0L,
    val errorMessage: String? = null
)

data class LanPairingPollResult(
    val status: PairingStatus,
    val deviceId: String = "",
    val deviceSecret: String = "",
    val port: Int = 10808,
    val disguiseId: String = "youtube",
    val disguiseDomain: String = "www.youtube.com",
    val cipher: EncryptionCipher = EncryptionCipher.AES_256_GCM,
    val wsPath: String = "/youtubei/v1/player",
    val errorMessage: String? = null
)

object NetworkDiagnostics {
    const val LAN_DISCOVERY_PORT = 10810
    const val LAN_DISCOVERY_MAGIC = "VELOBRIDGE_DISCOVER_V2"
    const val LAN_OFFER_MAGIC = "VELOBRIDGE_OFFER_V2"

    fun getActiveNetworkType(context: Context): String {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "Unknown"
            val network = cm.activeNetwork ?: return "Offline"
            val caps = cm.getNetworkCapabilities(network) ?: return "Offline"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Other"
            }
        }.getOrDefault("Unknown")
    }

    fun getLocalIpv4Addresses(): List<NetworkInterfaceInfo> {
        val result = mutableListOf<NetworkInterfaceInfo>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                val isPreferred = name.startsWith("wlan") || name.startsWith("ap") ||
                    name.startsWith("swlan") || name.startsWith("eth") || name.startsWith("rndis")
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("10.8.0.")) {
                            result.add(
                                NetworkInterfaceInfo(
                                    interfaceName = iface.displayName ?: iface.name,
                                    ipAddress = host,
                                    isHotspotOrWifi = isPreferred
                                )
                            )
                        }
                    }
                }
            }
        }
        return result.sortedByDescending { it.isHotspotOrWifi }
    }

    suspend fun measureProfileLatency(profile: V2RayProfile, timeoutMs: Int = 3500): Long? =
        withContext(Dispatchers.IO) {
            runCatching {
                val startNs = System.nanoTime()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(profile.address, profile.port), timeoutMs)
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    elapsedMs.coerceAtLeast(1L)
                }
            }.getOrNull()
        }

    suspend fun runDisguiseProbe(domain: String, port: Int = 443): DisguiseProbeResult =
        withContext(Dispatchers.IO) {
            val cleanDomain = domain.trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
            if (cleanDomain.isBlank()) {
                return@withContext DisguiseProbeResult(
                    domain = domain,
                    resolvedIp = null,
                    tcpRttMs = null,
                    tlsHandshakeSuccess = false,
                    tlsProtocol = null,
                    checkedAtMillis = System.currentTimeMillis(),
                    errorMessage = "Enter a valid domain name."
                )
            }
            var resolvedIp: String? = null
            var tcpRtt: Long? = null
            var tlsProtocol: String? = null
            var tlsSuccess = false
            var errorMsg: String? = null

            try {
                val addr = InetAddress.getByName(cleanDomain)
                resolvedIp = addr.hostAddress

                val t0 = System.nanoTime()
                val rawSocket = Socket()
                rawSocket.connect(InetSocketAddress(addr, port), 4000)
                tcpRtt = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)

                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSocket = factory.createSocket(rawSocket, cleanDomain, port, true) as SSLSocket
                sslSocket.soTimeout = 4000
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val params = sslSocket.sslParameters
                    params.serverNames = listOf(SNIHostName(cleanDomain))
                    // Strictly verify certificate SAN against the probed hostname
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    sslSocket.sslParameters = params
                }
                sslSocket.startHandshake()
                tlsProtocol = sslSocket.session.protocol
                tlsSuccess = true
                sslSocket.close()
            } catch (t: Throwable) {
                errorMsg = t.localizedMessage ?: t::class.java.simpleName
            }

            DisguiseProbeResult(
                domain = cleanDomain,
                resolvedIp = resolvedIp,
                tcpRttMs = tcpRtt,
                tlsHandshakeSuccess = tlsSuccess,
                tlsProtocol = tlsProtocol,
                checkedAtMillis = System.currentTimeMillis(),
                errorMessage = errorMsg
            )
        }

    /**
     * Parses a UDP discovery offer JSON buffer into [DiscoveredLanServer] without reading any secret.
     */
    fun parseLanDiscoveryOfferJson(
        jsonStr: String,
        senderIp: String,
        rttMs: Long
    ): DiscoveredLanServer? {
        return runCatching {
            val json = JSONObject(jsonStr)
            if (json.optString("magic") != LAN_OFFER_MAGIC) return null
            val port = json.optInt("port", 10808)
            if (port !in 1..65535) return null
            val cipherStr = json.optString("cipher", "aes-256-gcm")
            val cipher = EncryptionCipher.entries.find {
                it.wireValue.equals(cipherStr, true)
            } ?: EncryptionCipher.AES_256_GCM
            DiscoveredLanServer(
                serverFingerprint = json.optString("serverFingerprint", ""),
                deviceName = json.optString("deviceName", "Phone Bridge"),
                ipAddress = senderIp,
                port = port,
                disguisePresetId = json.optString("disguiseId", "youtube"),
                disguiseDomain = json.optString("disguiseDomain", "www.youtube.com"),
                encryption = cipher,
                wsPath = json.optString("wsPath", "/youtubei/v1/player"),
                pairingRequired = json.optBoolean("pairingRequired", true),
                rttMs = rttMs.coerceAtLeast(1L)
            )
        }.getOrNull()
    }

    suspend fun discoverLanBridgeServers(timeoutMs: Long = 2200L): List<DiscoveredLanServer> =
        withContext(Dispatchers.IO) {
            val found = linkedMapOf<String, DiscoveredLanServer>()
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 450

                    val payload = LAN_DISCOVERY_MAGIC.toByteArray(Charsets.UTF_8)
                    val targets = mutableSetOf("255.255.255.255")

                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    if (interfaces != null) {
                        for (iface in interfaces) {
                            if (!iface.isUp || iface.isLoopback) continue
                            for (ifAddr in iface.interfaceAddresses) {
                                ifAddr.broadcast?.hostAddress?.let { targets.add(it) }
                            }
                        }
                    }

                    val sentAt = System.nanoTime()
                    for (targetIp in targets) {
                        runCatching {
                            val packet = DatagramPacket(
                                payload,
                                payload.size,
                                InetAddress.getByName(targetIp),
                                LAN_DISCOVERY_PORT
                            )
                            socket.send(packet)
                        }
                    }

                    val deadline = System.currentTimeMillis() + timeoutMs
                    val rxBuf = ByteArray(2048)
                    while (System.currentTimeMillis() < deadline) {
                        val rxPacket = DatagramPacket(rxBuf, rxBuf.size)
                        try {
                            socket.receive(rxPacket)
                            val jsonStr = String(rxPacket.data, 0, rxPacket.length, Charsets.UTF_8)
                            val senderIp = rxPacket.address.hostAddress ?: continue
                            val rtt = ((System.nanoTime() - sentAt) / 1_000_000L).coerceAtLeast(1L)
                            parseLanDiscoveryOfferJson(jsonStr, senderIp, rtt)?.let { server ->
                                found["$senderIp:${server.port}"] = server
                            }
                        } catch (_: Exception) {
                            // Timeout slice, continue loop until deadline
                        }
                    }
                }
            }
            found.values.toList()
        }

    suspend fun initiateLanPairing(
        ip: String,
        port: Int,
        clientDeviceName: String,
        timeoutMs: Int = 5000
    ): LanPairingChallengeResult = withContext(Dispatchers.IO) {
        runCatching {
            val kp = BridgeCrypto.generateEcdhKeyPair()
            val clientPubB64 = BridgeCrypto.encodePublicKey(kp.public)
            val clientNonce = BridgeCrypto.generateAuthNonce()

            Socket().use { sock ->
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(ip, port), timeoutMs)
                val reqJson = JSONObject().apply {
                    put("clientDeviceName", clientDeviceName.ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}".trim() })
                    put("clientEcdhPub", clientPubB64)
                    put("clientNonce", clientNonce)
                }.toString()
                val out = sock.getOutputStream()
                out.write("PAIR_REQUEST:$reqJson\n".toByteArray(Charsets.UTF_8))
                out.flush()

                val line = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8)).readLine()
                    ?: return@withContext LanPairingChallengeResult(
                        success = false,
                        errorMessage = "Empty pairing challenge response from server"
                    )
                val resp = JSONObject(line)
                if (resp.optString("status") != "PENDING") {
                    return@withContext LanPairingChallengeResult(
                        success = false,
                        errorMessage = resp.optString("error", "Server rejected pairing request")
                    )
                }
                val requestId = resp.getString("requestId")
                val serverPub = resp.getString("serverEcdhPub")
                val serverNonce = resp.getString("serverNonce")
                val expiresAt = resp.optLong("expiresAtMillis", System.currentTimeMillis() + 90_000L)
                val shared = BridgeCrypto.computeEcdhSharedSecret(kp.private, serverPub)
                val sasCode = BridgeCrypto.computeSasCode(shared, clientNonce, serverNonce)

                LanPairingChallengeResult(
                    success = true,
                    requestId = requestId,
                    verificationCode = sasCode,
                    serverEcdhPub = serverPub,
                    clientNonce = clientNonce,
                    serverNonce = serverNonce,
                    sharedSecret = shared,
                    expiresAtMillis = expiresAt
                )
            }
        }.getOrElse { err ->
            LanPairingChallengeResult(
                success = false,
                errorMessage = err.localizedMessage ?: err::class.java.simpleName
            )
        }
    }

    suspend fun pollLanPairingApproval(
        ip: String,
        port: Int,
        challenge: LanPairingChallengeResult,
        timeoutMs: Int = 5000
    ): LanPairingPollResult = withContext(Dispatchers.IO) {
        runCatching {
            val pairKey = BridgeCrypto.derivePairingKey(
                ecdhSharedSecret = challenge.sharedSecret,
                clientNonce = challenge.clientNonce,
                serverNonce = challenge.serverNonce
            )
            val proofMac = javax.crypto.Mac.getInstance("HmacSHA256").run {
                init(javax.crypto.spec.SecretKeySpec(pairKey.encoded, "HmacSHA256"))
                val sig = doFinal("PAIR_CHECK:${challenge.requestId}".toByteArray(Charsets.UTF_8))
                android.util.Base64.encodeToString(sig, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
            }

            Socket().use { sock ->
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(ip, port), timeoutMs)
                val checkJson = JSONObject().apply {
                    put("requestId", challenge.requestId)
                    put("proof", proofMac)
                }.toString()
                val out = sock.getOutputStream()
                out.write("PAIR_CHECK:$checkJson\n".toByteArray(Charsets.UTF_8))
                out.flush()

                val line = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8)).readLine()
                    ?: return@withContext LanPairingPollResult(
                        status = PairingStatus.EXPIRED,
                        errorMessage = "Empty pairing poll response"
                    )
                val resp = JSONObject(line)
                when (resp.optString("status")) {
                    "PENDING" -> LanPairingPollResult(status = PairingStatus.PENDING)
                    "DENIED" -> LanPairingPollResult(status = PairingStatus.DENIED, errorMessage = "Denied on Bridge Server phone")
                    "EXPIRED" -> LanPairingPollResult(status = PairingStatus.EXPIRED, errorMessage = "Pairing request expired")
                    "APPROVED" -> {
                        val bundleB64 = resp.getString("encryptedBundle")
                        val frameBytes = android.util.Base64.decode(bundleB64, android.util.Base64.NO_WRAP)
                        val decrypted = BridgeCrypto.decryptFrame(
                            frame = frameBytes,
                            secretKey = pairKey,
                            cipherMode = EncryptionCipher.AES_256_GCM
                        )
                        val bundle = JSONObject(String(decrypted, Charsets.UTF_8))
                        val cipherStr = bundle.optString("cipher", "aes-256-gcm")
                        val cipher = EncryptionCipher.entries.find {
                            it.wireValue.equals(cipherStr, true)
                        } ?: EncryptionCipher.AES_256_GCM
                        LanPairingPollResult(
                            status = PairingStatus.APPROVED,
                            deviceId = bundle.getString("deviceId"),
                            deviceSecret = bundle.getString("deviceSecret"),
                            port = bundle.optInt("port", port),
                            disguiseId = bundle.optString("disguiseId", "youtube"),
                            disguiseDomain = bundle.optString("disguiseDomain", "www.youtube.com"),
                            cipher = cipher,
                            wsPath = bundle.optString("wsPath", "/youtubei/v1/player")
                        )
                    }
                    else -> LanPairingPollResult(
                        status = PairingStatus.EXPIRED,
                        errorMessage = resp.optString("error", "Unknown pairing state")
                    )
                }
            }
        }.getOrElse { err ->
            LanPairingPollResult(
                status = PairingStatus.EXPIRED,
                errorMessage = err.localizedMessage ?: err::class.java.simpleName
            )
        }
    }
}
