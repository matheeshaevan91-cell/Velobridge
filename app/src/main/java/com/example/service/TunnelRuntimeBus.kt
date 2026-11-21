package com.example.service

import com.example.core.network.NetworkDiagnostics
import com.example.domain.model.LogEntry
import com.example.domain.model.LogLevel
import com.example.domain.model.PeerSession
import com.example.domain.model.ServerRuntimeState
import com.example.domain.model.TelemetrySnapshot
import com.example.domain.model.VpnConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object TunnelRuntimeBus {
    private val _telemetry = MutableStateFlow(TelemetrySnapshot())
    val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    private val _serverState = MutableStateFlow(
        ServerRuntimeState(localIps = NetworkDiagnostics.getLocalIpv4Addresses())
    )
    val serverState: StateFlow<ServerRuntimeState> = _serverState.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

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
            (listOf(entry) + current).take(120)
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

    fun updateServerState(transform: (ServerRuntimeState) -> ServerRuntimeState) {
        _serverState.update(transform)
    }

    fun refreshLocalIps() {
        val ips = NetworkDiagnostics.getLocalIpv4Addresses()
        _serverState.update { it.copy(localIps = ips) }
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
}
