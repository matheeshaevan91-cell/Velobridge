package com.example.core.network

/**
 * Pure Kotlin IPv4 CIDR calculator used to exclude private/LAN subnets when Bypass LAN
 * is enabled on all Android API levels (API 24+).
 */
object RouteCalculator {

    data class CidrBlock(
        val networkInt: Int,
        val prefixLen: Int
    ) {
        val addressString: String
            get() = "${(networkInt ushr 24) and 0xFF}.${(networkInt ushr 16) and 0xFF}.${(networkInt ushr 8) and 0xFF}.${networkInt and 0xFF}"

        fun contains(ipInt: Int): Boolean {
            if (prefixLen == 0) return true
            val mask = -1 shl (32 - prefixLen)
            return (networkInt and mask) == (ipInt and mask)
        }

        override fun toString(): String = "$addressString/$prefixLen"
    }

    val PRIVATE_LAN_EXCLUSIONS: List<CidrBlock> = listOf(
        cidr("10.0.0.0", 8),
        cidr("127.0.0.0", 8),
        cidr("169.254.0.0", 16),
        cidr("172.16.0.0", 12),
        cidr("192.168.0.0", 16)
    )

    fun cidr(ip: String, prefixLen: Int): CidrBlock {
        require(prefixLen in 0..32) { "Invalid CIDR prefix: $prefixLen" }
        val raw = ipToInt(ip)
        val mask = if (prefixLen == 0) 0 else -1 shl (32 - prefixLen)
        return CidrBlock(raw and mask, prefixLen)
    }

    fun ipToInt(ip: String): Int {
        val parts = ip.trim().split('.')
        require(parts.size == 4) { "Invalid IPv4 address: $ip" }
        var acc = 0
        for (p in parts) {
            val octet = p.toIntOrNull() ?: throw IllegalArgumentException("Invalid octet '$p' in $ip")
            require(octet in 0..255) { "Octet out of range: $octet" }
            acc = (acc shl 8) or octet
        }
        return acc
    }

    fun isPrivateOrLocalIpv4(ipInt: Int): Boolean {
        return PRIVATE_LAN_EXCLUSIONS.any { it.contains(ipInt) }
    }

    /**
     * Computes minimal sorted CIDR blocks covering the full 0.0.0.0/0 IPv4 space minus [excluded].
     */
    fun computePublicIpv4Routes(excluded: List<CidrBlock> = PRIVATE_LAN_EXCLUSIONS): List<CidrBlock> {
        val root = TrieNode()
        for (block in excluded) {
            markExcluded(root, block.networkInt, block.prefixLen, depth = 0)
        }
        val out = mutableListOf<CidrBlock>()
        collectAllowed(root, currentPrefix = 0, depth = 0, out = out)
        return out
    }

    private class TrieNode {
        var zero: TrieNode? = null
        var one: TrieNode? = null
        var fullyExcluded: Boolean = false
    }

    private fun markExcluded(node: TrieNode, networkInt: Int, prefixLen: Int, depth: Int) {
        if (node.fullyExcluded) return
        if (depth == prefixLen) {
            node.fullyExcluded = true
            node.zero = null
            node.one = null
            return
        }
        val bit = (networkInt ushr (31 - depth)) and 1
        val child = if (bit == 0) {
            node.zero ?: TrieNode().also { node.zero = it }
        } else {
            node.one ?: TrieNode().also { node.one = it }
        }
        markExcluded(child, networkInt, prefixLen, depth + 1)
        if (node.zero?.fullyExcluded == true && node.one?.fullyExcluded == true) {
            node.fullyExcluded = true
            node.zero = null
            node.one = null
        }
    }

    private fun collectAllowed(
        node: TrieNode?,
        currentPrefix: Int,
        depth: Int,
        out: MutableList<CidrBlock>
    ) {
        if (node == null) {
            out.add(CidrBlock(currentPrefix, depth))
            return
        }
        if (node.fullyExcluded) return
        collectAllowed(node.zero, currentPrefix, depth + 1, out)
        val oneBit = 1 shl (31 - depth)
        collectAllowed(node.one, currentPrefix or oneBit, depth + 1, out)
    }
}
