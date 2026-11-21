package com.example.core.network

import android.os.Build
import com.example.domain.model.DiscoveredLanServer
import com.example.domain.model.DisguiseProbeResult
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.NetworkInterfaceInfo
import com.example.domain.model.V2RayProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object NetworkDiagnostics {
    const val LAN_DISCOVERY_PORT = 10810
    const val LAN_DISCOVERY_MAGIC = "VELOBRIDGE_DISCOVER_V1"

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
                errorMessage = errorMsg
            )
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
                            val json = JSONObject(jsonStr)
                            if (json.optString("magic") == "VELOBRIDGE_OFFER_V1") {
                                val senderIp = rxPacket.address.hostAddress ?: continue
                                val rtt = ((System.nanoTime() - sentAt) / 1_000_000L).coerceAtLeast(1L)
                                val port = json.optInt("port", 10808)
                                val cipherStr = json.optString("cipher", "aes-256-gcm")
                                val cipher = EncryptionCipher.entries.find {
                                    it.wireValue.equals(cipherStr, true)
                                } ?: EncryptionCipher.AES_256_GCM
                                val server = DiscoveredLanServer(
                                    deviceName = json.optString("deviceName", "Phone Bridge"),
                                    ipAddress = senderIp,
                                    port = port,
                                    uuid = json.optString("uuid"),
                                    disguisePresetId = json.optString("disguiseId", "youtube"),
                                    disguiseDomain = json.optString("disguiseDomain", "www.youtube.com"),
                                    encryption = cipher,
                                    wsPath = json.optString("wsPath", "/youtubei/v1/player"),
                                    rttMs = rtt
                                )
                                found["$senderIp:$port"] = server
                            }
                        } catch (_: Exception) {
                            // Timeout slice, continue loop until deadline
                        }
                    }
                }
            }
            found.values.toList()
        }
}
