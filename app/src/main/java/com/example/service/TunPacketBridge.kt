package com.example.service

import android.net.VpnService
import android.util.Base64
import com.example.core.crypto.BridgeCrypto
import com.example.core.network.RouteCalculator
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.PerformanceMode
import com.example.domain.model.ProtocolType
import com.example.domain.model.V2RayProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey
import kotlin.random.Random

/**
 * Pure-Kotlin TUN packet bridge handling:
 * - Mutual-authenticated P-256 ECDH + HKDF-SHA256 AEAD bridge transport (HTTP-Upgrade header obfuscated)
 * - IPv4 header + TCP/UDP checksum validation
 * - Ordered TCP flow state machine with sequence tracking, duplicate suppression, OOO reassembly,
 *   dynamic receive window, MSS negotiation, graceful FIN drain, and idle reaping
 * - Encrypted UDP DNS (port 53) and general UDP datagram forwarding (UDP_OPEN)
 * - IPv6 leak prevention via valid TCP RST+ACK and ICMPv6 Admin Prohibited responses
 * - Verified payload byte & packet counters (no dropped/retransmitted inflation)
 */
class TunPacketBridge(
    private val tunInput: InputStream,
    private val tunOutput: OutputStream,
    private val profile: V2RayProfile,
    private val bypassLan: Boolean,
    private val highSpeedMode: Boolean,
    private val scope: CoroutineScope,
    private val protectTcpSocket: (Socket) -> Boolean,
    private val protectUdpSocket: (DatagramSocket) -> Boolean,
    private val onTrafficStats: (
        upDeltaBytes: Long,
        downDeltaBytes: Long,
        activeFlows: Int,
        forwardedPackets: Long,
        droppedPackets: Long,
        bypassedPackets: Long
    ) -> Unit
) {
    constructor(
        vpnService: VpnService,
        tunInput: InputStream,
        tunOutput: OutputStream,
        profile: V2RayProfile,
        bypassLan: Boolean,
        highSpeedMode: Boolean,
        scope: CoroutineScope,
        onTrafficStats: (Long, Long, Int, Long, Long, Long) -> Unit
    ) : this(
        tunInput = tunInput,
        tunOutput = tunOutput,
        profile = profile,
        bypassLan = bypassLan,
        highSpeedMode = highSpeedMode,
        scope = scope,
        protectTcpSocket = { vpnService.protect(it) },
        protectUdpSocket = { vpnService.protect(it) },
        onTrafficStats = onTrafficStats
    )

    private val perfMode = if (highSpeedMode) PerformanceMode.HIGH_SPEED else PerformanceMode.BALANCED
    private val running = AtomicBoolean(true)
    private val tcpFlows = ConcurrentHashMap<FlowKey, TcpFlowSession>()
    private val udpFlows = ConcurrentHashMap<FlowKey, UdpFlowSession>()
    private val writeLock = Any()

    private val forwardedCounter = AtomicLong(0L)
    private val droppedCounter = AtomicLong(0L)
    private val bypassedCounter = AtomicLong(0L)

    data class FlowKey(
        val srcIp: Int,
        val srcPort: Int,
        val dstIp: Int,
        val dstPort: Int
    )

    data class TunnelHandshakeResult(
        val sessionKey: SecretKey
    )

    fun start() {
        require(profile.protocol == ProtocolType.VELO_BRIDGE) {
            "TunPacketBridge requires a native VELO_BRIDGE profile."
        }
        scope.launch(Dispatchers.IO) {
            val packetBuf = ByteArray(65535)
            while (running.get() && isActive) {
                val length = try {
                    tunInput.read(packetBuf)
                } catch (_: Throwable) {
                    break
                }
                if (length < 20) continue
                val version = (packetBuf[0].toInt() ushr 4) and 0x0F
                val pkt = packetBuf.copyOf(length)

                when (version) {
                    6 -> handleIpv6LeakGuard(pkt)
                    4 -> handleIpv4Packet(pkt)
                    else -> recordDrop()
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            while (running.get() && isActive) {
                delay(15_000L)
                reapIdleFlows()
            }
        }
    }

    fun stop() {
        running.set(false)
        tcpFlows.values.forEach { it.close() }
        tcpFlows.clear()
        udpFlows.values.forEach { it.close() }
        udpFlows.clear()
    }

    private fun currentActiveFlowCount(): Int = tcpFlows.size + udpFlows.size

    private fun recordForward(upBytes: Long = 0L, downBytes: Long = 0L, incrementPacket: Boolean = true) {
        val fwd = if (incrementPacket) forwardedCounter.incrementAndGet() else forwardedCounter.get()
        onTrafficStats(
            upBytes,
            downBytes,
            currentActiveFlowCount(),
            fwd,
            droppedCounter.get(),
            bypassedCounter.get()
        )
    }

    private fun recordDrop() {
        val drp = droppedCounter.incrementAndGet()
        onTrafficStats(0L, 0L, currentActiveFlowCount(), forwardedCounter.get(), drp, bypassedCounter.get())
    }

    private fun recordBypass(upBytes: Long = 0L, downBytes: Long = 0L) {
        val byp = bypassedCounter.incrementAndGet()
        onTrafficStats(upBytes, downBytes, currentActiveFlowCount(), forwardedCounter.get(), droppedCounter.get(), byp)
    }

    private fun handleIpv6LeakGuard(pkt: ByteArray) {
        recordDrop()
        if (pkt.size < 40) return
        val nextHeader = pkt[6].toInt() and 0xFF
        val response = when (nextHeader) {
            6 -> PacketCodec.buildIpv6TcpRstPacket(pkt)
            else -> PacketCodec.buildIpv6AdminProhibitedPacket(pkt)
        }
        if (response != null) {
            writeTunPacket(response)
        }
    }

    private fun handleIpv4Packet(packet: ByteArray) {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || packet.size < ihl) {
            recordDrop()
            return
        }
        val ipTotalLen = PacketCodec.readUShort(packet, 2)
        if (ipTotalLen < ihl || ipTotalLen > packet.size) {
            recordDrop()
            return
        }
        val ipSlice = if (ipTotalLen == packet.size) packet else packet.copyOf(ipTotalLen)
        if (!PacketCodec.verifyIpv4HeaderChecksum(ipSlice, ihl)) {
            recordDrop()
            return
        }
        // Drop fragmented IPv4 packets safely rather than forwarding partial headers
        val fragField = PacketCodec.readUShort(ipSlice, 6)
        if ((fragField and 0x3FFF) != 0 && (fragField and 0x1FFF) != 0 || (fragField and 0x2000) != 0) {
            recordDrop()
            return
        }

        val proto = ipSlice[9].toInt() and 0xFF
        val srcIp = PacketCodec.readInt(ipSlice, 12)
        val dstIp = PacketCodec.readInt(ipSlice, 16)
        val transportLen = ipSlice.size - ihl

        if (proto == 17) {
            if (!PacketCodec.verifyTransportChecksum(ipSlice, ihl, transportLen, 17, srcIp, dstIp)) {
                recordDrop()
                return
            }
            handleUdpPacket(ipSlice, ihl, srcIp, dstIp)
        } else if (proto == 6) {
            if (transportLen < 20 || !PacketCodec.verifyTransportChecksum(ipSlice, ihl, transportLen, 6, srcIp, dstIp)) {
                recordDrop()
                return
            }
            handleTcpPacket(ipSlice, ihl, srcIp, dstIp)
        } else {
            recordDrop()
        }
    }

    private fun handleUdpPacket(packet: ByteArray, ihl: Int, srcIp: Int, dstIp: Int) {
        if (packet.size < ihl + 8) {
            recordDrop()
            return
        }
        val srcPort = PacketCodec.readUShort(packet, ihl)
        val dstPort = PacketCodec.readUShort(packet, ihl + 2)
        val udpLen = PacketCodec.readUShort(packet, ihl + 4)
        val payloadOffset = ihl + 8
        val payloadLen = (udpLen - 8).coerceAtMost(packet.size - payloadOffset)
        if (payloadLen <= 0) {
            recordDrop()
            return
        }
        val payload = packet.copyOfRange(payloadOffset, payloadOffset + payloadLen)
        val isLanBypass = bypassLan && RouteCalculator.isPrivateOrLocalIpv4(dstIp)

        if (dstPort == 53 && !isLanBypass) {
            scope.launch(Dispatchers.IO) {
                val dnsHost = PacketCodec.intToIp(dstIp)
                recordForward(upBytes = payload.size.toLong())
                val reply = forwardEncryptedDnsQuery(dnsHost, 53, payload)
                if (reply != null && reply.isNotEmpty()) {
                    val responsePacket = PacketCodec.buildUdpIpv4Packet(
                        srcIp = dstIp,
                        dstIp = srcIp,
                        srcPort = dstPort,
                        dstPort = srcPort,
                        payload = reply
                    )
                    writeTunPacket(responsePacket)
                    recordForward(downBytes = reply.size.toLong(), incrementPacket = false)
                }
            }
            return
        }

        val key = FlowKey(srcIp, srcPort, dstIp, dstPort)
        val existing = udpFlows[key]
        if (existing != null) {
            existing.sendDatagram(payload)
            return
        }

        if (udpFlows.size >= MAX_UDP_FLOWS) {
            recordDrop()
            return
        }
        val session = UdpFlowSession(key, isLanBypass)
        if (udpFlows.putIfAbsent(key, session) == null) {
            session.start()
        }
        udpFlows[key]?.sendDatagram(payload)
    }

    private fun forwardEncryptedDnsQuery(dnsHost: String, dnsPort: Int, query: ByteArray): ByteArray? {
        return runCatching {
            Socket().use { tunnelSocket ->
                if (!protectTcpSocket(tunnelSocket)) {
                    throw IllegalStateException("VpnService.protect failed for DNS socket")
                }
                tunnelSocket.soTimeout = 4500
                tunnelSocket.connect(InetSocketAddress(profile.address, profile.port), 3500)
                val bufferedIn = BufferedInputStream(tunnelSocket.getInputStream())
                val handshake = performTunnelHandshake(tunnelSocket, bufferedIn, profile, highSpeedMode)
                val out = DataOutputStream(tunnelSocket.getOutputStream())
                val inp = DataInputStream(bufferedIn)
                val b64 = Base64.encodeToString(query, Base64.NO_WRAP)
                BridgeCrypto.writeEncryptedFrame(
                    out,
                    "UDP_DNS:$dnsHost:$dnsPort:$b64".toByteArray(Charsets.UTF_8),
                    handshake.sessionKey,
                    profile.encryption
                )
                val reply = BridgeCrypto.readEncryptedFrame(inp, handshake.sessionKey, profile.encryption)
                reply?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    private fun handleTcpPacket(packet: ByteArray, ihl: Int, srcIp: Int, dstIp: Int) {
        val srcPort = PacketCodec.readUShort(packet, ihl)
        val dstPort = PacketCodec.readUShort(packet, ihl + 2)
        val seq = PacketCodec.readUInt(packet, ihl + 4)
        val dataOffset = ((packet[ihl + 12].toInt() ushr 4) and 0x0F) * 4
        if (dataOffset < 20 || packet.size < ihl + dataOffset) {
            recordDrop()
            return
        }
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
            if (tcpFlows.size >= MAX_TCP_FLOWS) {
                recordDrop()
                val rst = PacketCodec.buildTcpIpv4Packet(
                    srcIp = dstIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seq = 0L,
                    ack = (seq + 1L) and 0xFFFFFFFFL,
                    flags = 0x14,
                    payload = ByteArray(0)
                )
                writeTunPacket(rst)
                return
            }
            val session = TcpFlowSession(key, seq)
            tcpFlows[key] = session
            session.startConnect()
            return
        }

        val session = tcpFlows[key]
        if (session == null) {
            if (!isRst) {
                val rst = PacketCodec.buildTcpIpv4Packet(
                    srcIp = dstIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seq = 0L,
                    ack = (seq + payload.size.toLong()) and 0xFFFFFFFFL,
                    flags = 0x14,
                    payload = ByteArray(0)
                )
                writeTunPacket(rst)
            }
            return
        }

        session.touch()
        if (payload.isNotEmpty()) {
            session.onClientData(seq, payload)
        }
        if (isFin) {
            session.onClientFin((seq + payload.size.toLong()) and 0xFFFFFFFFL)
        }
    }

    private fun reapIdleFlows() {
        val now = System.currentTimeMillis()
        tcpFlows.entries.removeIf { (_, session) ->
            if (now - session.lastActivityMs > TCP_IDLE_TIMEOUT_MS) {
                session.close()
                true
            } else false
        }
        udpFlows.entries.removeIf { (_, session) ->
            if (now - session.lastActivityMs > UDP_IDLE_TIMEOUT_MS) {
                session.close()
                true
            } else false
        }
    }

    private fun writeTunPacket(packet: ByteArray) {
        synchronized(writeLock) {
            runCatching {
                tunOutput.write(packet)
            }
        }
    }

    inner class UdpFlowSession(
        private val key: FlowKey,
        private val isLanBypass: Boolean
    ) {
        @Volatile
        var lastActivityMs: Long = System.currentTimeMillis()
            private set
        private val closed = AtomicBoolean(false)
        private val outboundQueue = Channel<ByteArray>(capacity = 32)
        private var bridgeSocket: Socket? = null
        private var directDatagram: DatagramSocket? = null

        fun start() {
            scope.launch(Dispatchers.IO) {
                var writerJob: kotlinx.coroutines.Job? = null
                try {
                    val dstHost = PacketCodec.intToIp(key.dstIp)
                    val dstPort = key.dstPort
                    if (isLanBypass) {
                        val ds = DatagramSocket()
                        if (!protectUdpSocket(ds)) {
                            ds.close()
                            throw IllegalStateException("VpnService.protect failed for UDP bypass")
                        }
                        ds.soTimeout = UDP_IDLE_TIMEOUT_MS.toInt()
                        directDatagram = ds
                        val dstAddr = InetAddress.getByName(dstHost)

                        writerJob = launch(Dispatchers.IO) {
                            for (pkt in outboundQueue) {
                                if (closed.get()) break
                                ds.send(DatagramPacket(pkt, pkt.size, dstAddr, dstPort))
                                recordBypass(upBytes = pkt.size.toLong())
                            }
                        }

                        val rxBuf = ByteArray(4096)
                        while (!closed.get() && running.get()) {
                            val rxPkt = DatagramPacket(rxBuf, rxBuf.size)
                            ds.receive(rxPkt)
                            lastActivityMs = System.currentTimeMillis()
                            val reply = rxPkt.data.copyOf(rxPkt.length)
                            val ipPkt = PacketCodec.buildUdpIpv4Packet(
                                srcIp = key.dstIp,
                                dstIp = key.srcIp,
                                srcPort = key.dstPort,
                                dstPort = key.srcPort,
                                payload = reply
                            )
                            writeTunPacket(ipPkt)
                            recordBypass(downBytes = reply.size.toLong())
                        }
                    } else {
                        val sock = Socket()
                        if (!protectTcpSocket(sock)) {
                            sock.close()
                            throw IllegalStateException("VpnService.protect failed for UDP bridge socket")
                        }
                        sock.tcpNoDelay = true
                        sock.soTimeout = UDP_IDLE_TIMEOUT_MS.toInt()
                        sock.connect(InetSocketAddress(profile.address, profile.port), 5000)
                        bridgeSocket = sock
                        val bufIn = BufferedInputStream(sock.getInputStream(), perfMode.relayChunkBytes)
                        val handshake = performTunnelHandshake(sock, bufIn, profile, highSpeedMode)
                        val dOut = DataOutputStream(sock.getOutputStream())
                        val dIn = DataInputStream(bufIn)

                        BridgeCrypto.writeEncryptedFrame(
                            dOut,
                            "UDP_OPEN:$dstHost:$dstPort".toByteArray(Charsets.UTF_8),
                            handshake.sessionKey,
                            profile.encryption
                        )
                        val ack = BridgeCrypto.readEncryptedFrame(dIn, handshake.sessionKey, profile.encryption)
                        if (ack == null || String(ack) != "OK") {
                            throw IllegalStateException("Bridge server declined UDP_OPEN")
                        }

                        writerJob = launch(Dispatchers.IO) {
                            for (pkt in outboundQueue) {
                                if (closed.get()) break
                                BridgeCrypto.writeEncryptedFrame(dOut, pkt, handshake.sessionKey, profile.encryption)
                                recordForward(upBytes = pkt.size.toLong())
                            }
                        }

                        while (!closed.get() && running.get()) {
                            val reply = BridgeCrypto.readEncryptedFrame(dIn, handshake.sessionKey, profile.encryption)
                                ?: break
                            lastActivityMs = System.currentTimeMillis()
                            val ipPkt = PacketCodec.buildUdpIpv4Packet(
                                srcIp = key.dstIp,
                                dstIp = key.srcIp,
                                srcPort = key.dstPort,
                                dstPort = key.srcPort,
                                payload = reply
                            )
                            writeTunPacket(ipPkt)
                            recordForward(downBytes = reply.size.toLong())
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    writerJob?.cancel()
                    close()
                    udpFlows.remove(key)
                }
            }
        }

        fun sendDatagram(payload: ByteArray) {
            if (closed.get()) return
            lastActivityMs = System.currentTimeMillis()
            if (outboundQueue.trySend(payload).isFailure) {
                recordDrop()
            }
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                outboundQueue.close()
                runCatching { bridgeSocket?.close() }
                runCatching { directDatagram?.close() }
            }
        }
    }

    inner class TcpFlowSession(
        private val key: FlowKey,
        clientInitialSeq: Long
    ) {
        @Volatile
        var lastActivityMs: Long = System.currentTimeMillis()
            private set

        private val isLanBypass = bypassLan && RouteCalculator.isPrivateOrLocalIpv4(key.dstIp)
        private val stateLock = Any()
        private var mySeq: Long = Random.nextInt(10000, 9999999).toLong()
        private var clientNextSeq: Long = (clientInitialSeq + 1L) and 0xFFFFFFFFL
        private var clientFinSeq: Long? = null
        private val oooSegments = TreeMap<Long, ByteArray>(Comparator { a, b -> PacketCodec.seqCompare(a, b) })
        private var oooBufferedBytes = 0
        private val queuedOutboundBytes = AtomicLong(0L)

        private var remoteSocket: Socket? = null
        private var sessionKey: SecretKey? = null
        private var encryptedOut: DataOutputStream? = null
        private var encryptedIn: DataInputStream? = null
        private var isEncryptedBridge = false
        private val closed = AtomicBoolean(false)
        private val outboundQueue = Channel<ByteArray>(capacity = 64)

        fun touch() {
            lastActivityMs = System.currentTimeMillis()
        }

        fun startConnect() {
            scope.launch(Dispatchers.IO) {
                var writerJob: kotlinx.coroutines.Job? = null
                try {
                    val dstHost = PacketCodec.intToIp(key.dstIp)
                    val dstPort = key.dstPort

                    val sock = Socket()
                    if (!protectTcpSocket(sock)) {
                        sock.close()
                        throw IllegalStateException("VpnService.protect failed for outbound TCP socket")
                    }
                    sock.tcpNoDelay = true
                    sock.soTimeout = 30000
                    runCatching {
                        sock.sendBufferSize = perfMode.socketBufferBytes
                        sock.receiveBufferSize = perfMode.socketBufferBytes
                        if (highSpeedMode) {
                            sock.trafficClass = 0x10
                        }
                    }

                    if (!isLanBypass) {
                        sock.connect(InetSocketAddress(profile.address, profile.port), 6000)
                        val bufIn = BufferedInputStream(sock.getInputStream(), perfMode.relayChunkBytes)
                        val handshake = performTunnelHandshake(sock, bufIn, profile, highSpeedMode)
                        val keySession = handshake.sessionKey
                        val dOut = DataOutputStream(sock.getOutputStream())
                        val dIn = DataInputStream(bufIn)
                        BridgeCrypto.writeEncryptedFrame(
                            dOut,
                            "TCP:$dstHost:$dstPort".toByteArray(Charsets.UTF_8),
                            keySession,
                            profile.encryption
                        )
                        val ackFrame = BridgeCrypto.readEncryptedFrame(dIn, keySession, profile.encryption)
                        if (ackFrame == null || String(ackFrame) != "OK") {
                            throw IllegalStateException("Bridge target connect failed")
                        }
                        sessionKey = keySession
                        isEncryptedBridge = true
                        encryptedOut = dOut
                        encryptedIn = dIn
                    } else {
                        sock.connect(InetSocketAddress(dstHost, dstPort), 6000)
                    }

                    remoteSocket = sock
                    synchronized(stateLock) {
                        sendTcpControlLocked(flags = 0x12, mssOption = perfMode.tunSegmentBytes) // SYN + ACK
                        mySeq = (mySeq + 1L) and 0xFFFFFFFFL
                    }

                    // Drain queued segments in strict sequence order; on FIN (channel close), flush and shutdownOutput
                    writerJob = launch(Dispatchers.IO) {
                        try {
                            for (payload in outboundQueue) {
                                queuedOutboundBytes.addAndGet(-payload.size.toLong())
                                if (closed.get()) break
                                if (isEncryptedBridge) {
                                    val k = sessionKey ?: break
                                    encryptedOut?.let {
                                        BridgeCrypto.writeEncryptedFrame(it, payload, k, profile.encryption)
                                    }
                                    recordForward(upBytes = payload.size.toLong())
                                } else {
                                    sock.getOutputStream().apply {
                                        write(payload)
                                        flush()
                                    }
                                    recordBypass(upBytes = payload.size.toLong())
                                }
                            }
                            if (!isEncryptedBridge && !sock.isClosed) {
                                runCatching { sock.shutdownOutput() }
                            }
                        } catch (_: Throwable) {
                            close()
                        }
                    }

                    if (isEncryptedBridge) {
                        val dIn = encryptedIn!!
                        val k = sessionKey!!
                        while (!closed.get() && running.get()) {
                            val chunk = BridgeCrypto.readEncryptedFrame(dIn, k, profile.encryption) ?: break
                            touch()
                            emitDataToTun(chunk)
                        }
                    } else {
                        val rawIn = sock.getInputStream()
                        val buf = ByteArray(perfMode.tunSegmentBytes)
                        while (!closed.get() && running.get()) {
                            val n = rawIn.read(buf)
                            if (n <= 0) break
                            touch()
                            emitDataToTun(buf.copyOf(n))
                        }
                    }

                    if (!closed.get()) {
                        synchronized(stateLock) {
                            sendTcpControlLocked(flags = 0x11) // FIN + ACK
                            mySeq = (mySeq + 1L) and 0xFFFFFFFFL
                        }
                    }
                } catch (_: Throwable) {
                    if (!closed.get()) {
                        synchronized(stateLock) {
                            sendTcpControlLocked(flags = 0x14) // RST + ACK
                        }
                    }
                } finally {
                    writerJob?.join(1000)
                    writerJob?.cancel()
                    close()
                    tcpFlows.remove(key)
                }
            }
        }

        private fun emitDataToTun(data: ByteArray) {
            var offset = 0
            val maxSegment = perfMode.tunSegmentBytes
            while (offset < data.size && !closed.get()) {
                val chunkLen = (data.size - offset).coerceAtMost(maxSegment)
                val slice = data.copyOfRange(offset, offset + chunkLen)
                synchronized(stateLock) {
                    val pkt = PacketCodec.buildTcpIpv4Packet(
                        srcIp = key.dstIp,
                        dstIp = key.srcIp,
                        srcPort = key.dstPort,
                        dstPort = key.srcPort,
                        seq = mySeq,
                        ack = clientNextSeq,
                        flags = 0x18,
                        payload = slice,
                        windowSize = advertisedWindow()
                    )
                    writeTunPacket(pkt)
                    mySeq = (mySeq + chunkLen.toLong()) and 0xFFFFFFFFL
                }
                if (isLanBypass) {
                    recordBypass(downBytes = slice.size.toLong())
                } else {
                    recordForward(downBytes = slice.size.toLong(), incrementPacket = false)
                }
                offset += chunkLen
            }
        }

        fun onClientData(seq: Long, payload: ByteArray) {
            if (closed.get() || payload.isEmpty()) return
            touch()
            synchronized(stateLock) {
                val cmp = PacketCodec.seqCompare(seq, clientNextSeq)
                when {
                    cmp == 0 -> {
                        enqueueInOrderLocked(payload)
                        drainOooLocked()
                        checkPendingFinLocked()
                        sendTcpControlLocked(flags = 0x10)
                    }
                    cmp < 0 -> {
                        // Exact or partial retransmission / duplicate
                        val segEnd = (seq + payload.size.toLong()) and 0xFFFFFFFFL
                        val newBytes = PacketCodec.seqCompare(segEnd, clientNextSeq)
                        if (newBytes > 0) {
                            val trimStart = payload.size - newBytes
                            enqueueInOrderLocked(payload.copyOfRange(trimStart, payload.size))
                            drainOooLocked()
                            checkPendingFinLocked()
                        } else {
                            // Pure duplicate segment: suppress upstream write, re-ACK current clientNextSeq
                            recordDrop()
                        }
                        sendTcpControlLocked(flags = 0x10)
                    }
                    else -> {
                        // Out-of-order segment: buffer up to bounded limit, emit duplicate ACK
                        if (!oooSegments.containsKey(seq) &&
                            oooSegments.size < MAX_OOO_SEGMENTS &&
                            oooBufferedBytes + payload.size <= MAX_OOO_BYTES
                        ) {
                            oooSegments[seq] = payload
                            oooBufferedBytes += payload.size
                        } else {
                            recordDrop()
                        }
                        sendTcpControlLocked(flags = 0x10)
                    }
                }
            }
        }

        fun onClientFin(finStartSeq: Long) {
            if (closed.get()) return
            touch()
            synchronized(stateLock) {
                clientFinSeq = finStartSeq
                checkPendingFinLocked()
            }
        }

        private fun enqueueInOrderLocked(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            queuedOutboundBytes.addAndGet(bytes.size.toLong())
            if (outboundQueue.trySend(bytes).isFailure) {
                queuedOutboundBytes.addAndGet(-bytes.size.toLong())
                close()
                return
            }
            clientNextSeq = (clientNextSeq + bytes.size.toLong()) and 0xFFFFFFFFL
        }

        private fun drainOooLocked() {
            while (oooSegments.isNotEmpty()) {
                val firstEntry = oooSegments.firstEntry() ?: break
                val segSeq = firstEntry.key
                val cmp = PacketCodec.seqCompare(segSeq, clientNextSeq)
                if (cmp > 0) break
                oooSegments.remove(segSeq)
                val segData = firstEntry.value
                oooBufferedBytes = (oooBufferedBytes - segData.size).coerceAtLeast(0)
                if (cmp == 0) {
                    enqueueInOrderLocked(segData)
                } else {
                    val segEnd = (segSeq + segData.size.toLong()) and 0xFFFFFFFFL
                    val remaining = PacketCodec.seqCompare(segEnd, clientNextSeq)
                    if (remaining > 0) {
                        enqueueInOrderLocked(segData.copyOfRange(segData.size - remaining, segData.size))
                    }
                }
            }
        }

        private fun checkPendingFinLocked() {
            val finSeq = clientFinSeq ?: return
            if (PacketCodec.seqCompare(clientNextSeq, finSeq) >= 0) {
                clientFinSeq = null
                clientNextSeq = (finSeq + 1L) and 0xFFFFFFFFL
                sendTcpControlLocked(flags = 0x11) // FIN + ACK
                mySeq = (mySeq + 1L) and 0xFFFFFFFFL
                // Close outboundQueue cleanly so already queued bytes finish draining before shutdownOutput()
                outboundQueue.close()
            }
        }

        private fun advertisedWindow(): Int {
            val used = queuedOutboundBytes.get().coerceAtLeast(0L)
            return (65535L - used).coerceIn(1024L, 65535L).toInt()
        }

        private fun sendTcpControlLocked(flags: Int, mssOption: Int? = null) {
            val pkt = PacketCodec.buildTcpIpv4Packet(
                srcIp = key.dstIp,
                dstIp = key.srcIp,
                srcPort = key.dstPort,
                dstPort = key.srcPort,
                seq = mySeq,
                ack = clientNextSeq,
                flags = flags,
                payload = ByteArray(0),
                windowSize = advertisedWindow(),
                mssOption = mssOption
            )
            writeTunPacket(pkt)
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                outboundQueue.close()
                synchronized(stateLock) {
                    oooSegments.clear()
                    oooBufferedBytes = 0
                }
                runCatching { remoteSocket?.close() }
            }
        }
    }

    companion object {
        const val MAX_TCP_FLOWS = 128
        const val MAX_UDP_FLOWS = 64
        const val MAX_OOO_SEGMENTS = 16
        const val MAX_OOO_BYTES = 65536
        const val TCP_IDLE_TIMEOUT_MS = 120_000L
        const val UDP_IDLE_TIMEOUT_MS = 45_000L

        fun performTunnelHandshake(
            socket: Socket,
            bufferedIn: BufferedInputStream,
            profile: V2RayProfile,
            highSpeedMode: Boolean
        ): TunnelHandshakeResult {
            val disguiseDomain = profile.effectiveDisguiseDomain
            val preset = DisguiseCatalog.findById(profile.disguisePresetId)
            val wsPath = profile.wsPath.ifBlank { preset.wsPath }
            val window = (System.currentTimeMillis() / 1000L) / 60L
            val nonce = BridgeCrypto.generateAuthNonce()
            val ecdhKp = BridgeCrypto.generateEcdhKeyPair()
            val clientPubB64 = BridgeCrypto.encodePublicKey(ecdhKp.public)
            val auth = BridgeCrypto.computeAuthToken(
                uuidOrSecret = profile.uuidOrPassword,
                disguiseDomain = disguiseDomain,
                timestampWindow = window,
                nonce = nonce,
                clientEcdhPubB64 = clientPubB64
            )
            val wsKey = Base64.encodeToString(Random.nextBytes(16), Base64.NO_WRAP)

            val request = buildString {
                append("GET ").append(wsPath).append(" HTTP/1.1\r\n")
                append("Host: ").append(disguiseDomain).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("User-Agent: ").append(preset.userAgent).append("\r\n")
                append("X-Velo-Auth: ").append(auth).append("\r\n")
                append("X-Velo-Nonce: ").append(nonce).append("\r\n")
                append("X-Velo-Ecdh: ").append(clientPubB64).append("\r\n")
                if (highSpeedMode) {
                    append("X-Velo-Mode: turbo\r\n")
                }
                append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()

            val sb = StringBuilder()
            while (true) {
                val b = bufferedIn.read()
                if (b == -1) throw IllegalStateException("Unexpected EOF during bridge handshake")
                sb.append(b.toChar())
                if (sb.endsWith("\r\n\r\n")) break
                if (sb.length > 4096) throw IllegalStateException("Handshake header too large")
            }
            val lines = sb.toString().split("\r\n").filter { it.isNotEmpty() }
            if (lines.isEmpty() || !lines.first().startsWith("HTTP/1.1 101")) {
                throw IllegalStateException("Bridge server rejected handshake (check Bridge Secret or pairing status)")
            }
            val respHeaders = lines.drop(1).mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx > 0) line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim() else null
            }.toMap()

            val serverPubB64 = respHeaders["x-velo-ecdh"].orEmpty()
            val serverNonce = respHeaders["x-velo-server-nonce"].orEmpty()
            val serverAck = respHeaders["x-velo-server-auth"].orEmpty()
            if (serverPubB64.isBlank() || serverNonce.isBlank() || serverAck.isBlank()) {
                throw IllegalStateException("Server failed mutual ECDH authentication check")
            }
            val verifiedServer = BridgeCrypto.verifyServerAuthAck(
                serverAck = serverAck,
                uuidOrSecret = profile.uuidOrPassword,
                disguiseDomain = disguiseDomain,
                clientNonce = nonce,
                serverNonce = serverNonce,
                serverEcdhPubB64 = serverPubB64
            )
            if (!verifiedServer) {
                throw IllegalStateException("Mutual server authentication failed (possible MitM or wrong server)")
            }
            val shared = BridgeCrypto.computeEcdhSharedSecret(ecdhKp.private, serverPubB64)
            val sessionKey = BridgeCrypto.deriveEcdhSessionKey(
                uuidOrSecret = profile.uuidOrPassword,
                ecdhSharedSecret = shared,
                clientNonce = nonce,
                serverNonce = serverNonce,
                cipher = profile.encryption
            )
            return TunnelHandshakeResult(sessionKey = sessionKey)
        }

        fun verifyEncryptedTunnelHandshake(
            vpnService: VpnService?,
            profile: V2RayProfile,
            highSpeedMode: Boolean,
            timeoutMs: Int = 4500,
            onStageUpdate: (serverReachable: Boolean, authVerified: Boolean, tunnelVerified: Boolean) -> Unit = { _, _, _ -> }
        ): Long {
            val startNs = System.nanoTime()
            Socket().use { sock ->
                if (vpnService != null && !vpnService.protect(sock)) {
                    throw IllegalStateException("VpnService.protect failed for verification socket")
                }
                sock.tcpNoDelay = true
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(profile.address, profile.port), timeoutMs)
                onStageUpdate(true, false, false)

                val bufIn = BufferedInputStream(sock.getInputStream())
                val handshake = performTunnelHandshake(sock, bufIn, profile, highSpeedMode)
                onStageUpdate(true, true, false)

                val out = DataOutputStream(sock.getOutputStream())
                val inp = DataInputStream(bufIn)
                BridgeCrypto.writeEncryptedFrame(
                    out,
                    "PING".toByteArray(Charsets.UTF_8),
                    handshake.sessionKey,
                    profile.encryption
                )
                val reply = BridgeCrypto.readEncryptedFrame(inp, handshake.sessionKey, profile.encryption)
                    ?: throw IllegalStateException("No encrypted PONG received from bridge server")
                if (String(reply, Charsets.UTF_8) != "PONG") {
                    throw IllegalStateException("Unexpected bridge verification reply")
                }
                onStageUpdate(true, true, true)
                return ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            }
        }
    }
}
