package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.VeloBridgeApp
import com.example.core.network.NetworkDiagnostics
import com.example.core.parser.ParseImportResult
import com.example.core.parser.V2RayConfigParser
import com.example.data.local.AppPreferences
import com.example.data.repository.ProfileRepository
import com.example.domain.model.DiscoveredLanServer
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.DisguiseProbeResult
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.LogEntry
import com.example.domain.model.LogLevel
import com.example.domain.model.ProtocolType
import com.example.domain.model.RoutingMode
import com.example.domain.model.ServerRuntimeState
import com.example.domain.model.TelemetrySnapshot
import com.example.domain.model.TransportType
import com.example.domain.model.V2RayProfile
import com.example.domain.model.VpnConnectionState
import com.example.service.BridgeServerService
import com.example.service.BridgeVpnService
import com.example.service.TunnelRuntimeBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class VeloViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ProfileRepository = (application as VeloBridgeApp).repository

    val profiles: StateFlow<List<V2RayProfile>> = repository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val preferences: StateFlow<AppPreferences> = repository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())

    val telemetry: StateFlow<TelemetrySnapshot> = TunnelRuntimeBus.telemetry
    val serverState: StateFlow<ServerRuntimeState> = TunnelRuntimeBus.serverState
    val logs: StateFlow<List<LogEntry>> = TunnelRuntimeBus.logs

    val activeProfile: StateFlow<V2RayProfile?> = combine(profiles, preferences) { list, prefs ->
        list.find { it.id == prefs.activeProfileId } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isScanningLan = MutableStateFlow(false)
    val isScanningLan: StateFlow<Boolean> = _isScanningLan.asStateFlow()

    private val _lanServers = MutableStateFlow<List<DiscoveredLanServer>>(emptyList())
    val lanServers: StateFlow<List<DiscoveredLanServer>> = _lanServers.asStateFlow()

    private val _isProbingDisguise = MutableStateFlow(false)
    val isProbingDisguise: StateFlow<Boolean> = _isProbingDisguise.asStateFlow()

    private val _disguiseProbeResult = MutableStateFlow<DisguiseProbeResult?>(null)
    val disguiseProbeResult: StateFlow<DisguiseProbeResult?> = _disguiseProbeResult.asStateFlow()

    private val _testingAllLatencies = MutableStateFlow(false)
    val testingAllLatencies: StateFlow<Boolean> = _testingAllLatencies.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    init {
        viewModelScope.launch {
            val uuid = repository.ensureServerUuid()
            val prefs = repository.preferences.first()
            TunnelRuntimeBus.updateServerState { current ->
                if (current.isRunning) current
                else current.copy(
                    listenPort = prefs.serverPort,
                    uuid = prefs.serverUuid.ifBlank { uuid },
                    disguisePresetId = prefs.serverDisguiseId,
                    customDisguiseDomain = prefs.serverCustomDomain,
                    encryption = prefs.serverEncryption,
                    wsPath = prefs.serverWsPath,
                    lanDiscoveryEnabled = prefs.serverLanDiscovery,
                    localIps = NetworkDiagnostics.getLocalIpv4Addresses()
                )
            }
        }
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun showBanner(message: String) {
        _statusBanner.value = message
    }

    fun selectProfile(profileId: String) {
        viewModelScope.launch {
            repository.setActiveProfileId(profileId)
        }
    }

    fun applyQuickDisguise(presetId: String, customDomain: String = "") {
        viewModelScope.launch {
            val current = activeProfile.value
            if (current != null) {
                repository.applyDisguiseToProfile(current.id, presetId, customDomain)
                val effective = DisguiseCatalog.resolveEffectiveDomain(presetId, customDomain)
                TunnelRuntimeBus.appendLog(
                    "Disguise",
                    "Switched camouflage domain to $effective for '${current.name}'",
                    LogLevel.INFO
                )
                showBanner("Traffic disguise set to $effective")
            } else {
                showBanner("Add or import a connection profile first to apply this disguise.")
            }
        }
    }

    fun importConfigText(rawText: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            when (val result = V2RayConfigParser.parseInput(rawText)) {
                is ParseImportResult.Error -> onDone(false, result.reason)
                is ParseImportResult.Success -> {
                    repository.saveProfiles(result.profiles)
                    repository.setActiveProfileId(result.profiles.first().id)
                    TunnelRuntimeBus.appendLog(
                        "Import",
                        "Imported ${result.profiles.size} profile(s): ${result.profiles.joinToString { it.name }}",
                        LogLevel.SUCCESS
                    )
                    onDone(true, "Imported ${result.profiles.size} configuration(s)")
                }
            }
        }
    }

    fun importConfigFromUri(context: Context, uri: Uri, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (content.isNullOrBlank()) {
                onDone(false, "Could not read selected configuration file.")
                return@launch
            }
            when (val result = V2RayConfigParser.parseInput(content)) {
                is ParseImportResult.Error -> onDone(false, result.reason)
                is ParseImportResult.Success -> {
                    repository.saveProfiles(result.profiles)
                    repository.setActiveProfileId(result.profiles.first().id)
                    TunnelRuntimeBus.appendLog(
                        "Import",
                        "Imported ${result.profiles.size} profile(s) from file",
                        LogLevel.SUCCESS
                    )
                    onDone(true, "Imported ${result.profiles.size} configuration(s) from file")
                }
            }
        }
    }

    fun saveProfile(profile: V2RayProfile) {
        viewModelScope.launch {
            repository.saveProfile(profile)
            repository.setActiveProfileId(profile.id)
            TunnelRuntimeBus.appendLog("Profile", "Saved profile '${profile.name}'", LogLevel.INFO)
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            repository.deleteProfile(id)
            if (preferences.value.activeProfileId == id) {
                val remaining = profiles.value.firstOrNull { it.id != id }?.id
                repository.setActiveProfileId(remaining)
            }
        }
    }

    fun testProfileLatency(profile: V2RayProfile) {
        viewModelScope.launch {
            val rtt = NetworkDiagnostics.measureProfileLatency(profile)
            repository.updateLatency(profile.id, rtt)
            val msg = if (rtt != null) "${profile.name}: ${rtt}ms" else "${profile.name}: unreachable"
            TunnelRuntimeBus.appendLog("Ping", msg, if (rtt != null) LogLevel.SUCCESS else LogLevel.WARNING)
        }
    }

    fun testAllLatencies() {
        viewModelScope.launch {
            _testingAllLatencies.value = true
            val list = profiles.value
            for (p in list) {
                val rtt = NetworkDiagnostics.measureProfileLatency(p, timeoutMs = 2500)
                repository.updateLatency(p.id, rtt)
            }
            _testingAllLatencies.value = false
        }
    }

    fun scanLanBridgeServers() {
        viewModelScope.launch {
            _isScanningLan.value = true
            val discovered = NetworkDiagnostics.discoverLanBridgeServers()
            _lanServers.value = discovered
            _isScanningLan.value = false
            TunnelRuntimeBus.appendLog(
                "LAN",
                "Scanned local Wi-Fi/Hotspot: found ${discovered.size} VeloBridge server(s)",
                if (discovered.isNotEmpty()) LogLevel.SUCCESS else LogLevel.INFO
            )
        }
    }

    fun saveDiscoveredServerAsProfile(server: DiscoveredLanServer) {
        viewModelScope.launch {
            val preset = DisguiseCatalog.findById(server.disguisePresetId)
            val profile = V2RayProfile(
                name = "${server.deviceName} Bridge",
                protocol = ProtocolType.VELO_BRIDGE,
                address = server.ipAddress,
                port = server.port,
                uuidOrPassword = server.uuid,
                encryption = server.encryption,
                transport = TransportType.WEBSOCKET,
                disguisePresetId = server.disguisePresetId,
                customDisguiseDomain = if (server.disguisePresetId == "custom") server.disguiseDomain else "",
                sni = server.disguiseDomain,
                hostHeader = server.disguiseDomain,
                wsPath = server.wsPath.ifBlank { preset.wsPath },
                tlsEnabled = true,
                isPhoneBridge = true,
                latencyMs = server.rttMs,
                lastTestedAt = System.currentTimeMillis()
            )
            repository.saveProfile(profile)
            repository.setActiveProfileId(profile.id)
            showBanner("Paired with ${server.deviceName} (${server.ipAddress}:${server.port})")
        }
    }

    fun runDisguiseProbe(domain: String) {
        viewModelScope.launch {
            _isProbingDisguise.value = true
            val res = NetworkDiagnostics.runDisguiseProbe(domain)
            _disguiseProbeResult.value = res
            _isProbingDisguise.value = false
            val summary = if (res.tlsHandshakeSuccess) {
                "Probe OK for ${res.domain}: ${res.tcpRttMs}ms (${res.tlsProtocol})"
            } else {
                "Probe failed for ${res.domain}: ${res.errorMessage}"
            }
            TunnelRuntimeBus.appendLog(
                "Probe",
                summary,
                if (res.tlsHandshakeSuccess) LogLevel.SUCCESS else LogLevel.WARNING
            )
        }
    }

    fun startVpnConnection(context: Context) {
        val profile = activeProfile.value
        if (profile == null) {
            showBanner("Please import a config or pair a Phone Server first.")
            return
        }
        val prefs = preferences.value
        val intent = BridgeVpnService.createStartIntent(
            context = context,
            profile = profile,
            dnsServer = prefs.dnsServer,
            bypassLan = prefs.bypassLan
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopVpnConnection(context: Context) {
        val intent = Intent(context, BridgeVpnService::class.java).apply {
            action = BridgeVpnService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun updateServerConfigDraft(
        port: Int,
        disguiseId: String,
        customDomain: String,
        encryption: EncryptionCipher,
        wsPath: String,
        lanDiscovery: Boolean
    ) {
        viewModelScope.launch {
            val uuid = serverState.value.uuid.ifBlank { repository.ensureServerUuid() }
            repository.updateServerConfig(
                port = port,
                uuid = uuid,
                disguiseId = disguiseId,
                customDomain = customDomain,
                encryption = encryption,
                wsPath = wsPath,
                lanDiscovery = lanDiscovery
            )
            TunnelRuntimeBus.updateServerState {
                it.copy(
                    listenPort = port,
                    uuid = uuid,
                    disguisePresetId = disguiseId,
                    customDisguiseDomain = customDomain,
                    encryption = encryption,
                    wsPath = wsPath,
                    lanDiscoveryEnabled = lanDiscovery,
                    localIps = NetworkDiagnostics.getLocalIpv4Addresses()
                )
            }
        }
    }

    fun regenerateServerUuid() {
        viewModelScope.launch {
            val s = serverState.value
            val newUuid = UUID.randomUUID().toString()
            repository.updateServerConfig(
                port = s.listenPort,
                uuid = newUuid,
                disguiseId = s.disguisePresetId,
                customDomain = s.customDisguiseDomain,
                encryption = s.encryption,
                wsPath = s.wsPath,
                lanDiscovery = s.lanDiscoveryEnabled
            )
            TunnelRuntimeBus.updateServerState { it.copy(uuid = newUuid) }
            showBanner("Generated fresh Bridge Secret UUID")
        }
    }

    fun refreshLocalIps() {
        TunnelRuntimeBus.refreshLocalIps()
    }

    fun toggleBridgeServer(context: Context) {
        val current = serverState.value
        if (current.isRunning) {
            val intent = Intent(context, BridgeServerService::class.java).apply {
                action = BridgeServerService.ACTION_STOP
            }
            context.startService(intent)
        } else {
            val uuid = current.uuid.ifBlank { UUID.randomUUID().toString() }
            val intent = Intent(context, BridgeServerService::class.java).apply {
                action = BridgeServerService.ACTION_START
                putExtra(BridgeServerService.EXTRA_PORT, current.listenPort)
                putExtra(BridgeServerService.EXTRA_UUID, uuid)
                putExtra(BridgeServerService.EXTRA_DISGUISE_ID, current.disguisePresetId)
                putExtra(BridgeServerService.EXTRA_CUSTOM_DOMAIN, current.customDisguiseDomain)
                putExtra(BridgeServerService.EXTRA_WS_PATH, current.wsPath)
                putExtra(BridgeServerService.EXTRA_CIPHER, current.encryption.name)
                putExtra(BridgeServerService.EXTRA_LAN_DISCOVERY, current.lanDiscoveryEnabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    fun setRoutingMode(mode: RoutingMode) {
        viewModelScope.launch { repository.setRoutingMode(mode) }
    }

    fun setDnsServer(dns: String) {
        viewModelScope.launch { repository.setDnsServer(dns) }
    }

    fun setBypassLan(enabled: Boolean) {
        viewModelScope.launch { repository.setBypassLan(enabled) }
    }

    fun setDarkTheme(dark: Boolean) {
        viewModelScope.launch { repository.setDarkTheme(dark) }
    }

    fun clearLogs() = TunnelRuntimeBus.clearLogs()

    fun isVpnConnected(): Boolean = telemetry.value.state == VpnConnectionState.CONNECTED
}
