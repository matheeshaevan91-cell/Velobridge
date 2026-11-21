package com.example.service

import com.example.core.network.NetworkDiagnostics
import com.example.domain.model.DisguiseProbeResult
import com.example.domain.model.LatencySample
import com.example.domain.model.LogEntry
import com.example.domain.model.LogLevel
import com.example.domain.model.PairedDevice
import com.example.domain.model.PairingRequest
import com.example.domain.model.PairingStatus
import com.example.domain.model.PeerSession
import com.example.domain.model.ServerRuntimeState
import com.example.domain.model.TelemetrySample
import com.example.domain.model.TelemetrySnapshot
import com.example.domain.model.VpnConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object TunnelRuntimeBus {
    const val MAX_BANDWIDTH_SAMPLES = 60
    const val MAX_LATENCY_SAMPLES = 30
    const val MAX_LOG_ENTRIES = 150

    private val _telemetry = MutableStateFlow(TelemetrySnapshot())
    val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    private val _serverState = MutableStateFlow(
        ServerRuntimeState(localIps = NetworkDiagnostics.getLocalIpv4Addresses())
    )
    val serverState: StateFlow<ServerRuntimeState> = _serverState.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _latestProbeResult = MutableStateFlow<DisguiseProbeResult?>(null)
    val latestProbeResult: StateFlow<DisguiseProbeResult?> = _latestProbeResult.asStateFlow()

    private var logSeq = 1L

    fun appendLog(source: String, message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(
            id = logSeq++,
            timestamp = System.currentTimeMillis(),
            source = source,
            message = message,
            level = level
        )
        _logs.update { current ->
            (listOf(entry) + current).take(MAX_LOG_ENTRIES)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun updateTelemetry(transform: (TelemetrySnapshot) -> TelemetrySnapshot) {
        _telemetry.update(transform)
    }

    fun setVpnState(state: VpnConnectionState, error: String? = null) {
        _telemetry.update { it.copy(state = state, errorMessage = error) }
    }

    fun setConnectionStages(
        serverReachable: Boolean,
        authVerified: Boolean,
        tunnelVerified: Boolean,
        trafficFlowing: Boolean
    ) {
        _telemetry.update {
            it.copy(
                serverReachable = serverReachable,
                authVerified = authVerified,
                tunnelVerified = tunnelVerified,
                trafficFlowing = trafficFlowing
            )
        }
    }

    fun recordBandwidthSample(
        upBps: Long,
        downBps: Long,
        totalUpBytes: Long,
        totalDownBytes: Long,
        activeFlowCount: Int,
        forwardedPackets: Long = _telemetry.value.forwardedPackets,
        droppedPackets: Long = _telemetry.value.droppedPackets,
        bypassedPackets: Long = _telemetry.value.bypassedPackets
    ) {
        val now = System.currentTimeMillis()
        _telemetry.update { prev ->
            val nextSpeedHist = (prev.speedHistory + (upBps + downBps)).takeLast(MAX_BANDWIDTH_SAMPLES)
            val nextSamples = (prev.bandwidthSamples + TelemetrySample(now, upBps, downBps))
                .takeLast(MAX_BANDWIDTH_SAMPLES)
            val hasFlowingBytes = prev.trafficFlowing || totalUpBytes > 0L || totalDownBytes > 0L
            prev.copy(
                upSpeedBps = upBps,
                downSpeedBps = downBps,
                peakUpBps = maxOf(prev.peakUpBps, upBps),
                peakDownBps = maxOf(prev.peakDownBps, downBps),
                totalUpBytes = totalUpBytes,
                totalDownBytes = totalDownBytes,
                activeFlowCount = activeFlowCount,
                forwardedPackets = forwardedPackets,
                droppedPackets = droppedPackets,
                bypassedPackets = bypassedPackets,
                trafficFlowing = hasFlowingBytes,
                speedHistory = nextSpeedHist,
                bandwidthSamples = nextSamples
            )
        }
    }

    fun recordLatencySample(rttMs: Long, targetLabel: String, updateCurrentLatency: Boolean = true) {
        val now = System.currentTimeMillis()
        _telemetry.update { prev ->
            val nextLatencySamples = (prev.latencySamples + LatencySample(now, rttMs, targetLabel))
                .takeLast(MAX_LATENCY_SAMPLES)
            prev.copy(
                latencyMs = if (updateCurrentLatency) rttMs else prev.latencyMs,
                latencySamples = nextLatencySamples
            )
        }
    }

    fun recordProbeResult(result: DisguiseProbeResult) {
        _latestProbeResult.value = result
        result.tcpRttMs?.let { rtt ->
            recordLatencySample(rtt, result.domain, updateCurrentLatency = false)
        }
    }

    fun updateServerState(transform: (ServerRuntimeState) -> ServerRuntimeState) {
        _serverState.update(transform)
    }

    fun recordServerHandshake(authenticated: Boolean) {
        _serverState.update { current ->
            if (authenticated) {
                current.copy(authenticatedHandshakes = current.authenticatedHandshakes + 1)
            } else {
                current.copy(rejectedHandshakes = current.rejectedHandshakes + 1)
            }
        }
    }

    fun refreshLocalIps(networkType: String? = null) {
        val ips = NetworkDiagnostics.getLocalIpv4Addresses()
        _serverState.update { current ->
            current.copy(
                localIps = ips,
                activeNetworkType = networkType ?: current.activeNetworkType
            )
        }
        if (networkType != null) {
            _telemetry.update { it.copy(networkType = networkType) }
        }
    }

    fun upsertPeer(session: PeerSession) {
        _serverState.update { current ->
            val filtered = current.activePeers.filterNot { it.id == session.id }
            current.copy(activePeers = listOf(session) + filtered)
        }
    }

    fun removePeer(peerId: String) {
        _serverState.update { current ->
            current.copy(activePeers = current.activePeers.filterNot { it.id == peerId })
        }
    }

    fun upsertPendingPairing(request: PairingRequest, nowMillis: Long = System.currentTimeMillis()) {
        _serverState.update { current ->
            val active = current.pendingPairings
                .filterNot { it.requestId == request.requestId }
                .filter { it.expiresAtMillis > nowMillis || it.status == PairingStatus.APPROVED }
            current.copy(pendingPairings = (listOf(request) + active).take(5))
        }
    }

    fun updatePairingStatus(requestId: String, status: PairingStatus) {
        _serverState.update { current ->
            val next = current.pendingPairings.map { req ->
                if (req.requestId == requestId) req.copy(status = status) else req
            }
            current.copy(pendingPairings = next)
        }
    }

    fun pruneExpiredPairings(nowMillis: Long = System.currentTimeMillis()) {
        _serverState.update { current ->
            val next = current.pendingPairings.filter { it.expiresAtMillis > nowMillis }
            current.copy(pendingPairings = next)
        }
    }

    fun setPairedDevices(devices: List<PairedDevice>) {
        _serverState.update { it.copy(pairedDevices = devices) }
    }
}
