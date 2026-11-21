package com.example.service

import android.net.VpnService
import android.util.Base64
import com.example.core.crypto.BridgeCrypto
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.LogLevel
import com.example.domain.model.ProtocolType
import com.example.domain.model.V2RayProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey
import kotlin.random.Random

/**
 * Pure-Kotlin IPv4 TUN packet bridge handling UDP DNS (port 53) and TCP flow forwarding
 * through an encrypted Domain-Disguised VeloBridge tunnel (or protected sockets for bypass/external).
 */
class TunPacketBridge(
    private val vpnService: VpnService,
    private val tunInput: FileInputStream,
    private val tunOutput: FileOutputStream,
    private val profile: V2RayProfile,
    private val bypassLan: Boolean,
    private val scope: CoroutineScope,
    private val onTrafficBytes: (upBytes: Long, downBytes: Long, activeFlows: Int) -> Unit
) {
    private val running = AtomicBoolean(true)
    private val tcpFlows = ConcurrentHashMap<FlowKey, TcpFlowSession>()
    private val writeLock = Any()
    private val ipIdCounter = AtomicLong(1000L)
    private val secretKey: SecretKey = BridgeCrypto.deriveKey(profile.uuidOrPassword)

    data class FlowKey(
        val srcIp: Int,
        val srcPort: Int,
        val dstIp: Int,
        val dstPort: Int
    )

    fun start() {
        scope.launch(Dispatchers.IO) {
            val packetBuf = ByteArray(32767)
            while (running.get() && isActive) {
                val length = try {
                    tunInput.read(packetBuf)
                } catch (_: Throwable) {
                    break
                }
                if (length <= 0) continue
                if (length < 20) continue
                val version = (packetBuf[0].toInt() ushr 4) and 0x0F
                if (version != 4) continue
                val ihl = (packetBuf[0].toInt() and 0x0F) * 4
                if (length < ihl) continue

                val protocol = packetBuf[9].toInt() and 0xFF
                val packetCopy = packetBuf.copyOf(length)
                onTrafficBytes(length.toLong(), 0L, tcpFlows.size)

                when (protocol) {
                    17 -> handleUdpPacket(packetCopy, ihl)
                    6 -> handleTcpPacket(packetCopy, ihl)
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        tcpFlows.values.forEach { it.close() }
        tcpFlows.clear()
    }

    private fun handleUdpPacket(packet: ByteArray, ihl: Int) {
        if (packet.size < ihl + 8) return
        val srcIp = readInt(packet, 12)
        val dstIp = readInt(packet, 16)
        val srcPort = readUShort(packet, ihl)
        val dstPort = readUShort(packet, ihl + 2)
        val udpLen = readUShort(packet, ihl + 4)
        val payloadOffset = ihl + 8
        val payloadLen = (udpLen - 8).coerceAtMost(packet.size - payloadOffset)
        if (payloadLen <= 0) return
        val payload = packet.copyOfRange(payloadOffset, payloadOffset + payloadLen)

        if (dstPort == 53) {
            scope.launch(Dispatchers.IO) {
                val dnsHost = intToIp(dstIp)
                val reply = forwardDnsQuery(dnsHost, 53, payload)
                if (reply != null && reply.isNotEmpty()) {
                    val responsePacket = buildUdpIpv4Packet(
                        srcIp = dstIp,
                        dstIp = srcIp,
                        srcPort = dstPort,
                        dstPort = srcPort,
                        payload = reply
                    )
                    writeTunPacket(responsePacket)
                    onTrafficBytes(0L, responsePacket.size.toLong(), tcpFlows.size)
                }
            }
        }
    }

    private fun forwardDnsQuery(dnsHost: String, dnsPort: Int, query: ByteArray): ByteArray? {
        if (profile.protocol == ProtocolType.VELO_BRIDGE) {
            runCatching {
                val tunnelSocket = Socket()
                vpnService.protect(tunnelSocket)
                tunnelSocket.soTimeout = 4500
                tunnelSocket.connect(InetSocketAddress(profile.address, profile.port), 3500)
                val bufferedIn = BufferedInputStream(tunnelSocket.getInputStream())
                performDisguiseHandshake(tunnelSocket, bufferedIn)
                val out = DataOutputStream(tunnelSocket.getOutputStream())
                val inp = DataInputStream(bufferedIn)
                val b64 = Base64.encodeToString(query, Base64.NO_WRAP)
                BridgeCrypto.writeEncryptedFrame(
                    out,
                    "UDP_DNS:$dnsHost:$dnsPort:$b64".toByteArray(Charsets.UTF_8),
                    secretKey,
                    profile.encryption
                )
                val reply = BridgeCrypto.readEncryptedFrame(inp, secretKey, profile.encryption)
                tunnelSocket.close()
                if (reply != null && reply.isNotEmpty()) return reply
            }
        }

        return runCatching {
            DatagramSocket().use { ds ->
                vpnService.protect(ds)
                ds.soTimeout = 3500
                ds.send(DatagramPacket(query, query.size, InetAddress.getByName(dnsHost), dnsPort))
                val buf = ByteArray(1500)
                val resp = DatagramPacket(buf, buf.size)
                ds.receive(resp)
                resp.data.copyOf(resp.length)
            }
        }.getOrNull()
    }

    private fun handleTcpPacket(packet: ByteArray, ihl: Int) {
        if (packet.size < ihl + 20) return
        val srcIp = readInt(packet, 12)
        val dstIp = readInt(packet, 16)
        val srcPort = readUShort(packet, ihl)
        val dstPort = readUShort(packet, ihl + 2)
        val seq = readUInt(packet, ihl + 4)
        val ack = readUInt(packet, ihl + 8)
        val dataOffset = ((packet[ihl + 12].toInt() ushr 4) and 0x0F) * 4
        val flags = packet[ihl + 13].toInt() and 0x3F
        val payloadOffset = ihl + dataOffset
        val payload = if (payloadOffset < packet.size) {
            packet.copyOfRange(payloadOffset, packet.size)
        } else {
            ByteArray(0)
        }

        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        val key = FlowKey(srcIp, srcPort, dstIp, dstPort)

        if (isRst) {
            tcpFlows.remove(key)?.close()
            return
        }

        if (isSyn && !isAck) {
            tcpFlows.remove(key)?.close()
            val session = TcpFlowSession(key, seq)
            tcpFlows[key] = session
            session.startConnect()
            return
        }

        val session = tcpFlows[key] ?: return
        if (payload.isNotEmpty()) {
            session.onClientData(seq, payload)
        }
        if (isFin) {
            session.onClientFin(seq + payload.size.toLong())
        }
    }

    private fun performDisguiseHandshake(socket: Socket, bufferedIn: BufferedInputStream) {
        val disguiseDomain = profile.effectiveDisguiseDomain
        val preset = DisguiseCatalog.findById(profile.disguisePresetId)
        val wsPath = profile.wsPath.ifBlank { preset.wsPath }
        val window = (System.currentTimeMillis() / 1000L) / 60L
        val auth = BridgeCrypto.computeAuthToken(profile.uuidOrPassword, disguiseDomain, window)
        val wsKey = Base64.encodeToString(Random.nextBytes(16), Base64.NO_WRAP)

        val request = buildString {
            append("GET ").append(wsPath).append(" HTTP/1.1\r\n")
            append("Host: ").append(disguiseDomain).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("User-Agent: ").append(preset.userAgent).append("\r\n")
            append("X-Velo-Auth: ").append(auth).append("\r\n")
            append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
        socket.getOutputStream().flush()

        // Consume HTTP 101 response headers
        val sb = StringBuilder()
        while (true) {
            val b = bufferedIn.read()
            if (b == -1) throw IllegalStateException("Unexpected EOF during disguise handshake")
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) break
            if (sb.length > 4096) throw IllegalStateException("Handshake header too large")
        }
        if (!sb.startsWith("HTTP/1.1 101")) {
            throw IllegalStateException("Bridge server rejected handshake")
        }
    }

    private fun writeTunPacket(packet: ByteArray) {
        synchronized(writeLock) {
            runCatching {
                tunOutput.write(packet)
            }
        }
    }

    inner class TcpFlowSession(
        private val key: FlowKey,
        clientInitialSeq: Long
    ) {
        private var mySeq: Long = Random.nextInt(10000, 9999999).toLong()
        private var clientNextSeq: Long = (clientInitialSeq + 1L) and 0xFFFFFFFFL
        private var remoteSocket: Socket? = null
        private var encryptedOut: DataOutputStream? = null
        private var encryptedIn: DataInputStream? = null
        private var isEncryptedBridge = false
        private val closed = AtomicBoolean(false)

        fun startConnect() {
            scope.launch(Dispatchers.IO) {
                try {
                    val dstHost = intToIp(key.dstIp)
                    val dstPort = key.dstPort
                    val sock = Socket()
                    vpnService.protect(sock)
                    sock.tcpNoDelay = true
                    sock.soTimeout = 30000

                    val useEncryptedBridge = profile.protocol == ProtocolType.VELO_BRIDGE &&
                        !(bypassLan && isPrivateIpv4(key.dstIp))

                    if (useEncryptedBridge) {
                        sock.connect(InetSocketAddress(profile.address, profile.port), 6000)
                        val bufIn = BufferedInputStream(sock.getInputStream())
                        performDisguiseHandshake(sock, bufIn)
                        val dOut = DataOutputStream(sock.getOutputStream())
                        val dIn = DataInputStream(bufIn)
                        BridgeCrypto.writeEncryptedFrame(
                            dOut,
                            "TCP:$dstHost:$dstPort".toByteArray(Charsets.UTF_8),
                            secretKey,
                            profile.encryption
                        )
                        val ackFrame = BridgeCrypto.readEncryptedFrame(dIn, secretKey, profile.encryption)
                        if (ackFrame == null || String(ackFrame) != "OK") {
                            throw IllegalStateException("Bridge target connect failed")
                        }
                        isEncryptedBridge = true
                        encryptedOut = dOut
                        encryptedIn = dIn
                    } else {
                        sock.connect(InetSocketAddress(dstHost, dstPort), 6000)
                    }

                    remoteSocket = sock
                    // Send SYN-ACK back to TUN
                    sendTcpControl(flags = 0x12) // SYN + ACK
                    mySeq = (mySeq + 1L) and 0xFFFFFFFFL

                    // Read from remote and write to TUN
                    if (isEncryptedBridge) {
                        val dIn = encryptedIn!!
                        while (!closed.get() && running.get()) {
                            val chunk = BridgeCrypto.readEncryptedFrame(dIn, secretKey, profile.encryption) ?: break
                            emitDataToTun(chunk)
                        }
                    } else {
                        val rawIn = sock.getInputStream()
                        val buf = ByteArray(1400)
                        while (!closed.get() && running.get()) {
                            val n = rawIn.read(buf)
                            if (n <= 0) break
                            emitDataToTun(buf.copyOf(n))
                        }
                    }

                    if (!closed.get()) {
                        sendTcpControl(flags = 0x11) // FIN + ACK
                    }
                } catch (_: Throwable) {
                    if (!closed.get()) {
                        sendTcpControl(flags = 0x14) // RST + ACK
                    }
                } finally {
                    close()
                    tcpFlows.remove(key)
                }
            }
        }

        private fun emitDataToTun(data: ByteArray) {
            var offset = 0
            while (offset < data.size && !closed.get()) {
                val chunkLen = (data.size - offset).coerceAtMost(1360)
                val slice = data.copyOfRange(offset, offset + chunkLen)
                val pkt = buildTcpIpv4Packet(
                    srcIp = key.dstIp,
                    dstIp = key.srcIp,
                    srcPort = key.dstPort,
                    dstPort = key.srcPort,
                    seq = mySeq,
                    ack = clientNextSeq,
                    flags = 0x18, // PSH + ACK
                    payload = slice
                )
                writeTunPacket(pkt)
                mySeq = (mySeq + chunkLen.toLong()) and 0xFFFFFFFFL
                onTrafficBytes(0L, slice.size.toLong(), tcpFlows.size)
                offset += chunkLen
            }
        }

        fun onClientData(seq: Long, payload: ByteArray) {
            if (closed.get()) return
            clientNextSeq = (seq + payload.size.toLong()) and 0xFFFFFFFFL
            sendTcpControl(flags = 0x10) // ACK
            scope.launch(Dispatchers.IO) {
                try {
                    if (isEncryptedBridge) {
                        encryptedOut?.let {
                            BridgeCrypto.writeEncryptedFrame(it, payload, secretKey, profile.encryption)
                        }
                    } else {
                        remoteSocket?.getOutputStream()?.apply {
                            write(payload)
                            flush()
                        }
                    }
                } catch (_: Throwable) {
                    close()
                }
            }
        }

        fun onClientFin(finEndSeq: Long) {
            clientNextSeq = (finEndSeq + 1L) and 0xFFFFFFFFL
            sendTcpControl(flags = 0x11) // FIN + ACK
            close()
            tcpFlows.remove(key)
        }

        private fun sendTcpControl(flags: Int) {
            val pkt = buildTcpIpv4Packet(
                srcIp = key.dstIp,
                dstIp = key.srcIp,
                srcPort = key.dstPort,
                dstPort = key.srcPort,
                seq = mySeq,
                ack = clientNextSeq,
                flags = flags,
                payload = ByteArray(0)
            )
            writeTunPacket(pkt)
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { remoteSocket?.close() }
            }
        }
    }

    private fun buildUdpIpv4Packet(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLen = 20 + 8 + payload.size
        val pkt = ByteArray(totalLen)
        writeIpv4Header(pkt, totalLen, 17, srcIp, dstIp)
        writeUShort(pkt, 20, srcPort)
        writeUShort(pkt, 22, dstPort)
        writeUShort(pkt, 24, 8 + payload.size)
        writeUShort(pkt, 26, 0)
        System.arraycopy(payload, 0, pkt, 28, payload.size)
        val udpChecksum = computeTransportChecksum(pkt, 20, 8 + payload.size, 17, srcIp, dstIp)
        writeUShort(pkt, 26, udpChecksum)
        return pkt
    }

    private fun buildTcpIpv4Packet(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLen = 20 + 20 + payload.size
        val pkt = ByteArray(totalLen)
        writeIpv4Header(pkt, totalLen, 6, srcIp, dstIp)
        writeUShort(pkt, 20, srcPort)
        writeUShort(pkt, 22, dstPort)
        writeUInt(pkt, 24, seq)
        writeUInt(pkt, 28, ack)
        pkt[32] = 0x50.toByte() // 20 bytes TCP header
        pkt[33] = flags.toByte()
        writeUShort(pkt, 34, 65535) // window
        writeUShort(pkt, 36, 0) // checksum placeholder
        writeUShort(pkt, 38, 0) // urgent pointer
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, pkt, 40, payload.size)
        }
        val tcpChecksum = computeTransportChecksum(pkt, 20, 20 + payload.size, 6, srcIp, dstIp)
        writeUShort(pkt, 36, tcpChecksum)
        return pkt
    }

    private fun writeIpv4Header(pkt: ByteArray, totalLen: Int, proto: Int, srcIp: Int, dstIp: Int) {
        pkt[0] = 0x45.toByte()
        pkt[1] = 0
        writeUShort(pkt, 2, totalLen)
        writeUShort(pkt, 4, (ipIdCounter.incrementAndGet() and 0xFFFFL).toInt())
        writeUShort(pkt, 6, 0x4000) // Don't Fragment
        pkt[8] = 64.toByte() // TTL
        pkt[9] = proto.toByte()
        writeUShort(pkt, 10, 0)
        writeInt(pkt, 12, srcIp)
        writeInt(pkt, 16, dstIp)
        val ipChecksum = computeChecksum(pkt, 0, 20)
        writeUShort(pkt, 10, ipChecksum)
    }

    private fun computeTransportChecksum(
        pkt: ByteArray,
        offset: Int,
        len: Int,
        proto: Int,
        srcIp: Int,
        dstIp: Int
    ): Int {
        var sum = 0L
        sum += (srcIp ushr 16) and 0xFFFF
        sum += srcIp and 0xFFFF
        sum += (dstIp ushr 16) and 0xFFFF
        sum += dstIp and 0xFFFF
        sum += proto and 0xFF
        sum += len and 0xFFFF
        var i = offset
        val end = offset + len
        while (i + 1 < end) {
            val word = ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (pkt[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun computeChecksum(buf: ByteArray, offset: Int, len: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + len) {
            val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun isPrivateIpv4(ip: Int): Boolean {
        val b0 = (ip ushr 24) and 0xFF
        val b1 = (ip ushr 16) and 0xFF
        return b0 == 10 || b0 == 127 || (b0 == 192 && b1 == 168) || (b0 == 172 && b1 in 16..31)
    }

    private fun intToIp(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    private fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun writeInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }

    private fun readUInt(b: ByteArray, o: Int): Long = readInt(b, o).toLong() and 0xFFFFFFFFL
    private fun writeUInt(b: ByteArray, o: Int, v: Long) = writeInt(b, o, v.toInt())

    private fun readUShort(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun writeUShort(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte()
        b[o + 1] = v.toByte()
    }
}
