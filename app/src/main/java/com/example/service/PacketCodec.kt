package com.example.service

import java.util.concurrent.atomic.AtomicLong

/**
 * Pure Kotlin IPv4 / IPv6 / TCP / UDP / ICMPv6 packet parser, validator, and serializer
 * used by [TunPacketBridge] and verified by unit tests.
 */
object PacketCodec {
    private val ipIdCounter = AtomicLong(1000L)

    fun verifyIpv4HeaderChecksum(pkt: ByteArray, ihl: Int): Boolean {
        if (ihl < 20 || pkt.size < ihl) return false
        return computeOneComplement(pkt, 0, ihl) == 0
    }

    fun verifyTransportChecksum(
        pkt: ByteArray,
        ihl: Int,
        transportLen: Int,
        proto: Int,
        srcIp: Int,
        dstIp: Int
    ): Boolean {
        if (transportLen < 8 || pkt.size < ihl + transportLen) return false
        // RFC 768: IPv4 UDP checksum == 0 means checksum omitted by sender
        if (proto == 17 && readUShort(pkt, ihl + 6) == 0) return true
        return computeIpv4TransportChecksum(pkt, ihl, transportLen, proto, srcIp, dstIp) == 0
    }

    fun buildUdpIpv4Packet(
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
        val udpChecksum = computeIpv4TransportChecksum(pkt, 20, 8 + payload.size, 17, srcIp, dstIp)
        writeUShort(pkt, 26, if (udpChecksum == 0) 0xFFFF else udpChecksum)
        return pkt
    }

    fun buildTcpIpv4Packet(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray,
        windowSize: Int = 65535,
        mssOption: Int? = null
    ): ByteArray {
        val optLen = if (mssOption != null) 4 else 0
        val tcpHeaderLen = 20 + optLen
        val totalLen = 20 + tcpHeaderLen + payload.size
        val pkt = ByteArray(totalLen)
        writeIpv4Header(pkt, totalLen, 6, srcIp, dstIp)
        writeUShort(pkt, 20, srcPort)
        writeUShort(pkt, 22, dstPort)
        writeUInt(pkt, 24, seq)
        writeUInt(pkt, 28, ack)
        pkt[32] = (((tcpHeaderLen / 4) shl 4) and 0xF0).toByte()
        pkt[33] = flags.toByte()
        writeUShort(pkt, 34, windowSize.coerceIn(0, 65535))
        writeUShort(pkt, 36, 0)
        writeUShort(pkt, 38, 0)
        if (mssOption != null) {
            val clampedMss = mssOption.coerceIn(536, 8960)
            pkt[40] = 0x02
            pkt[41] = 0x04
            writeUShort(pkt, 42, clampedMss)
        }
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, pkt, 20 + tcpHeaderLen, payload.size)
        }
        val tcpChecksum = computeIpv4TransportChecksum(pkt, 20, tcpHeaderLen + payload.size, 6, srcIp, dstIp)
        writeUShort(pkt, 36, tcpChecksum)
        return pkt
    }

    /**
     * Constructs a valid IPv6 TCP RST+ACK packet mirroring an inbound IPv6 TCP segment so
     * dual-stack apps immediately fail-over to IPv4 without leaking IPv6 traffic.
     */
    fun buildIpv6TcpRstPacket(requestPkt: ByteArray): ByteArray? {
        if (requestPkt.size < 60) return null
        val version = (requestPkt[0].toInt() ushr 4) and 0x0F
        if (version != 6) return null
        if ((requestPkt[6].toInt() and 0xFF) != 6) return null
        val inFlags = requestPkt[53].toInt() and 0x3F
        if ((inFlags and 0x04) != 0) return null // Never RST a RST

        val dataOff = ((requestPkt[52].toInt() ushr 4) and 0x0F) * 4
        if (dataOff < 20 || requestPkt.size < 40 + dataOff) return null
        val payloadLen = (requestPkt.size - 40 - dataOff).coerceAtLeast(0)
        val isSyn = (inFlags and 0x02) != 0
        val isFin = (inFlags and 0x01) != 0

        val srcPort = readUShort(requestPkt, 40)
        val dstPort = readUShort(requestPkt, 42)
        val inSeq = readUInt(requestPkt, 44)
        val inAck = readUInt(requestPkt, 48)
        val ackAdvance = payloadLen.toLong() + (if (isSyn) 1L else 0L) + (if (isFin) 1L else 0L)
        val outAck = (inSeq + ackAdvance.coerceAtLeast(1L)) and 0xFFFFFFFFL

        val pkt = ByteArray(60)
        pkt[0] = 0x60.toByte()
        writeUShort(pkt, 4, 20) // Payload length = 20 bytes TCP header
        pkt[6] = 6 // Next Header = TCP
        pkt[7] = 64 // Hop limit
        // Swap IPv6 src (8..23) and dst (24..39)
        System.arraycopy(requestPkt, 24, pkt, 8, 16)
        System.arraycopy(requestPkt, 8, pkt, 24, 16)

        writeUShort(pkt, 40, dstPort)
        writeUShort(pkt, 42, srcPort)
        writeUInt(pkt, 44, inAck)
        writeUInt(pkt, 48, outAck)
        pkt[52] = 0x50.toByte()
        pkt[53] = 0x14.toByte() // RST + ACK
        writeUShort(pkt, 54, 0)
        writeUShort(pkt, 56, 0)
        writeUShort(pkt, 58, 0)

        val csum = computeIpv6UpperChecksum(pkt, 40, 20, 6)
        writeUShort(pkt, 56, csum)
        return pkt
    }

    /**
     * Constructs an ICMPv6 Type 1 Code 1 (Destination Unreachable - Communication with destination
     * administratively prohibited) response to quickly abort non-TCP IPv6 flows (e.g. QUIC/UDP).
     */
    fun buildIpv6AdminProhibitedPacket(requestPkt: ByteArray): ByteArray? {
        if (requestPkt.size < 40) return null
        val version = (requestPkt[0].toInt() ushr 4) and 0x0F
        if (version != 6) return null
        val quotedLen = requestPkt.size.coerceAtMost(512)
        val icmpLen = 8 + quotedLen
        val pkt = ByteArray(40 + icmpLen)
        pkt[0] = 0x60.toByte()
        writeUShort(pkt, 4, icmpLen)
        pkt[6] = 58 // Next Header = ICMPv6
        pkt[7] = 64
        System.arraycopy(requestPkt, 24, pkt, 8, 16)
        System.arraycopy(requestPkt, 8, pkt, 24, 16)

        pkt[40] = 1 // Type 1: Destination Unreachable
        pkt[41] = 1 // Code 1: Communication with destination administratively prohibited
        writeUShort(pkt, 42, 0)
        writeUInt(pkt, 44, 0L)
        System.arraycopy(requestPkt, 0, pkt, 48, quotedLen)

        val csum = computeIpv6UpperChecksum(pkt, 40, icmpLen, 58)
        writeUShort(pkt, 42, csum)
        return pkt
    }

    private fun writeIpv4Header(pkt: ByteArray, totalLen: Int, proto: Int, srcIp: Int, dstIp: Int) {
        pkt[0] = 0x45.toByte()
        pkt[1] = 0
        writeUShort(pkt, 2, totalLen)
        writeUShort(pkt, 4, (ipIdCounter.incrementAndGet() and 0xFFFFL).toInt())
        writeUShort(pkt, 6, 0x4000)
        pkt[8] = 64.toByte()
        pkt[9] = proto.toByte()
        writeUShort(pkt, 10, 0)
        writeInt(pkt, 12, srcIp)
        writeInt(pkt, 16, dstIp)
        val ipChecksum = computeOneComplement(pkt, 0, 20)
        writeUShort(pkt, 10, ipChecksum)
    }

    fun computeIpv4TransportChecksum(
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
        sum += sumWords(pkt, offset, len)
        return finalizeOneComplement(sum)
    }

    fun computeIpv6UpperChecksum(
        pkt: ByteArray,
        upperOffset: Int,
        upperLen: Int,
        nextHeader: Int
    ): Int {
        var sum = 0L
        // srcIp6 (8..23) + dstIp6 (24..39)
        sum += sumWords(pkt, 8, 32)
        sum += (upperLen ushr 16) and 0xFFFF
        sum += upperLen and 0xFFFF
        sum += nextHeader and 0xFF
        sum += sumWords(pkt, upperOffset, upperLen)
        return finalizeOneComplement(sum)
    }

    fun computeOneComplement(buf: ByteArray, offset: Int, len: Int): Int {
        return finalizeOneComplement(sumWords(buf, offset, len))
    }

    private fun sumWords(buf: ByteArray, offset: Int, len: Int): Long {
        var sum = 0L
        var i = offset
        val end = offset + len
        while (i + 1 < end) {
            val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        return sum
    }

    private fun finalizeOneComplement(initialSum: Long): Int {
        var sum = initialSum
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    fun intToIp(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    fun writeInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }

    fun readUInt(b: ByteArray, o: Int): Long = readInt(b, o).toLong() and 0xFFFFFFFFL
    fun writeUInt(b: ByteArray, o: Int, v: Long) = writeInt(b, o, v.toInt())

    fun readUShort(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    fun writeUShort(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte()
        b[o + 1] = v.toByte()
    }

    /** Signed 32-bit modular comparison: negative if a < b, 0 if a == b, positive if a > b. */
    fun seqCompare(a: Long, b: Long): Int = (a.toInt() - b.toInt())
}
