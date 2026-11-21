package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.VeloBridgeApp
import com.example.core.network.LanPairingChallengeResult
import com.example.core.network.NetworkDiagnostics
import com.example.core.parser.QrCodeGenerator
import com.example.core.parser.V2RayConfigParser
import com.example.data.local.AppPreferences
import com.example.data.repository.ProfileRepository
import com.example.domain.model.AppearanceMode
import com.example.domain.model.DiscoveredLanServer
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.DisguiseProbeResult
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.LogEntry
import com.example.domain.model.LogLevel
import com.example.domain.model.PairingStatus
import com.example.domain.model.PerformanceMode
import com.example.domain.model.ProfileSortOption
import com.example.domain.model.ProtocolType
import com.example.domain.model.RoutingMode
import com.example.domain.model.SecurityCheckState
import com.example.domain.model.SecurityDiagnosticsReport
import com.example.domain.model.ServerRuntimeState
import com.example.domain.model.TelemetrySnapshot
import com.example.domain.model.TransportType
import com.example.domain.model.V2RayProfile
import com.example.domain.model.VpnConnectionState
import com.example.service.BridgeServerService
import com.example.service.BridgeVpnService
import com.example.service.TunnelRuntimeBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ProfileFilterState(
    val searchQuery: String = "",
    val protocolFilter: ProtocolType? = null,
    val favoritesOnly: Boolean = false,
    val sortOption: ProfileSortOption = ProfileSortOption.RECENT
)

data class ClientPairingSession(
    val server: DiscoveredLanServer,
    val verificationCode: String = "",
    val status: PairingStatus = PairingStatus.PENDING,
    val isSubmitting: Boolean = true,
    val expiresAtMillis: Long = 0L,
    val errorMessage: String? = null
)

class VeloViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ProfileRepository = (application as VeloBridgeApp).repository

    val profiles: StateFlow<List<V2RayProfile>> = repository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val preferences: StateFlow<AppPreferences> = repository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())

    val telemetry: StateFlow<TelemetrySnapshot> = TunnelRuntimeBus.telemetry
    val serverState: StateFlow<ServerRuntimeState> = TunnelRuntimeBus.serverState
    val logs: StateFlow<List<LogEntry>> = TunnelRuntimeBus.logs
    val disguiseProbeResult: StateFlow<DisguiseProbeResult?> = TunnelRuntimeBus.latestProbeResult

    val activeProfile: StateFlow<V2RayProfile?> = combine(profiles, preferences) { list, prefs ->
        list.find { it.id == prefs.activeProfileId } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _profileFilter = MutableStateFlow(ProfileFilterState())
    val profileFilter: StateFlow<ProfileFilterState> = _profileFilter.asStateFlow()

    val filteredProfiles: StateFlow<List<V2RayProfile>> = combine(profiles, _profileFilter) { list, filter ->
        val filtered = list.filter { profile ->
            val q = filter.searchQuery.trim()
            val matchesQuery = q.isEmpty() ||
                profile.name.contains(q, ignoreCase = true) ||
                profile.address.contains(q, ignoreCase = true) ||
                profile.effectiveDisguiseDomain.contains(q, ignoreCase = true) ||
                profile.protocol.label.contains(q, ignoreCase = true)
            val matchesProto = filter.protocolFilter == null || profile.protocol == filter.protocolFilter
            val matchesFav = !filter.favoritesOnly || profile.isFavorite
            matchesQuery && matchesProto && matchesFav
        }
        when (filter.sortOption) {
            ProfileSortOption.RECENT -> filtered.sortedWith(
                compareByDescending<V2RayProfile> { it.isFavorite }
                    .thenByDescending { it.lastConnectedAt ?: 0L }
                    .thenByDescending { it.createdAt }
            )
            ProfileSortOption.LATENCY -> filtered.sortedWith(
                compareBy<V2RayProfile> { it.latencyMs ?: Long.MAX_VALUE }
                    .thenByDescending { it.isFavorite }
                    .thenBy { it.name.lowercase() }
            )
            ProfileSortOption.NAME -> filtered.sortedWith(
                compareBy<V2RayProfile> { it.name.lowercase() }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val securityReport: StateFlow<SecurityDiagnosticsReport> = combine(
        profiles,
        preferences,
        serverState
    ) { list, prefs, sState ->
        val unencrypted = withContext(Dispatchers.IO) { repository.countUnencryptedStoredEntities() }
        repository.buildSecurityDiagnostics(list, prefs, sState, unencrypted)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SecurityDiagnosticsReport(
            vaultState = SecurityCheckState.UNKNOWN,
            vaultDetail = "Inspecting vault health…",
            replayGuardState = SecurityCheckState.UNKNOWN,
            replayGuardDetail = "Inspecting replay cache…",
            proxyAuthDefenseState = SecurityCheckState.NOT_APPLICABLE,
            proxyAuthDefenseDetail = "Bridge Server standby",
            lanPairingState = SecurityCheckState.VERIFIED,
            lanPairingDetail = "Zero-secret discovery + ECDH approval",
            transportSecurityState = SecurityCheckState.VERIFIED,
            transportSecurityDetail = "P-256 ECDH + HKDF AEAD framing",
            keystoreBacked = false,
            sensitiveProtectionEnabled = true,
            allowInsecureProfilesCount = 0,
            unencryptedProfilesCount = 0
        )
    )

    private val _isScanningLan = MutableStateFlow(false)
    val isScanningLan: StateFlow<Boolean> = _isScanningLan.asStateFlow()

    private val _lanServers = MutableStateFlow<List<DiscoveredLanServer>>(emptyList())
    val lanServers: StateFlow<List<DiscoveredLanServer>> = _lanServers.asStateFlow()

    private val _clientPairingSession = MutableStateFlow<ClientPairingSession?>(null)
    val clientPairingSession: StateFlow<ClientPairingSession?> = _clientPairingSession.asStateFlow()

    private var pairingPollJob: Job? = null

    private val _isProbingDisguise = MutableStateFlow(false)
    val isProbingDisguise: StateFlow<Boolean> = _isProbingDisguise.asStateFlow()

    private val _testingAllLatencies = MutableStateFlow(false)
    val testingAllLatencies: StateFlow<Boolean> = _testingAllLatencies.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private var lastConnectTriggerMs: Long = 0L

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.migrateLegacySecretsIfNeeded()
            }
            val uuid = repository.ensureServerUuid()
            val prefs = repository.preferences.first()
            val mode = if (prefs.highSpeedMode) PerformanceMode.HIGH_SPEED else PerformanceMode.BALANCED
            val netType = NetworkDiagnostics.getActiveNetworkType(getApplication())

            TunnelRuntimeBus.setPairedDevices(prefs.pairedDevices)
            TunnelRuntimeBus.updateTelemetry { current ->
                if (current.state == VpnConnectionState.CONNECTED) current
                else current.copy(
                    highSpeedMode = prefs.highSpeedMode,
                    mtu = mode.mtu,
                    networkType = netType,
                    lastSuccessfulConnectionMillis = current.lastSuccessfulConnectionMillis
                        ?: prefs.lastSuccessfulConnectionMillis
                )
            }
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
                    highSpeedMode = prefs.highSpeedMode,
                    activeNetworkType = netType,
                    pairedDevices = prefs.pairedDevices,
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

    fun setProfileSearchQuery(query: String) {
        _profileFilter.update { it.copy(searchQuery = query) }
    }

    fun setProfileProtocolFilter(protocol: ProtocolType?) {
        _profileFilter.update { it.copy(protocolFilter = protocol) }
    }

    fun toggleFavoritesFilter() {
        _profileFilter.update { it.copy(favoritesOnly = !it.favoritesOnly) }
    }

    fun setProfileSortOption(option: ProfileSortOption) {
        _profileFilter.update { it.copy(sortOption = option) }
    }

    fun selectProfile(profileId: String) {
        viewModelScope.launch {
            repository.setActiveProfileId(profileId)
        }
    }

    fun toggleFavorite(profileId: String, currentlyFavorite: Boolean) {
        viewModelScope.launch {
            repository.setFavorite(profileId, !currentlyFavorite)
        }
    }

    fun duplicateProfile(profile: V2RayProfile) {
        viewModelScope.launch {
            val copy = profile.copy(
                id = UUID.randomUUID().toString(),
                name = "${profile.name} (Copy)",
                isFavorite = false,
                createdAt = System.currentTimeMillis(),
                lastConnectedAt = null
            )
            if (repository.saveProfile(copy)) {
                TunnelRuntimeBus.appendLog("Profile", "Duplicated '${profile.name}'", LogLevel.INFO)
                showBanner("Duplicated '${profile.name}'")
            } else {
                showBanner("Vault encryption unavailable; duplicate not saved")
            }
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
                    "Switched camouflage Host/Path to $effective for '${current.name}'",
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
            val bulk = V2RayConfigParser.parseBulkInput(rawText)
            if (bulk.profiles.isEmpty()) {
                val reason = bulk.failedEntries.firstOrNull()
                    ?: "No valid configurations found."
                onDone(false, reason)
                return@launch
            }

            val existing = profiles.value
            val dupCheck = V2RayConfigParser.findDuplicates(bulk.profiles, existing)
            if (dupCheck.unique.isEmpty()) {
                onDone(false, "Duplicate configuration detected: all ${dupCheck.duplicates.size} profile(s) already exist.")
                return@launch
            }

            if (!repository.saveProfiles(dupCheck.unique)) {
                onDone(false, "Secure Vault failed to encrypt configuration secrets.")
                return@launch
            }
            repository.setActiveProfileId(dupCheck.unique.first().id)

            val summary = buildString {
                append("Imported ${dupCheck.unique.size} profile(s)")
                if (dupCheck.duplicates.isNotEmpty()) {
                    append(" • skipped ${dupCheck.duplicates.size} duplicate(s)")
                }
                if (bulk.failedEntries.isNotEmpty()) {
                    append(" • ${bulk.failedEntries.size} invalid line(s) skipped")
                }
            }
            TunnelRuntimeBus.appendLog(
                "Import",
                "$summary: ${dupCheck.unique.joinToString { it.name }}",
                LogLevel.SUCCESS
            )
            onDone(true, summary)
        }
    }

    fun importConfigFromUri(context: Context, uri: Uri, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (content.isNullOrBlank()) {
                    onDone(false, "Could not read selected configuration file.")
                } else {
                    importConfigText(content, onDone)
                }
            }
        }
    }

    fun importQrFromImageUri(context: Context, uri: Uri, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val decodedBitmap = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.isMutableRequired = false
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
            }.getOrNull()

            val qrText = decodedBitmap?.let { QrCodeGenerator.decodeBitmap(it) }
            withContext(Dispatchers.Main) {
                if (qrText.isNullOrBlank()) {
                    onDone(false, "No readable V2Ray or VeloBridge QR code found in the selected image.")
                } else {
                    importConfigText(qrText, onDone)
                }
            }
        }
    }

    fun saveProfile(profile: V2RayProfile) {
        viewModelScope.launch {
            if (repository.saveProfile(profile)) {
                repository.setActiveProfileId(profile.id)
                TunnelRuntimeBus.appendLog("Profile", "Saved profile '${profile.name}'", LogLevel.INFO)
            } else {
                showBanner("Vault encryption failed; profile could not be saved")
            }
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
            if (rtt != null) {
                TunnelRuntimeBus.recordLatencySample(
                    rttMs = rtt,
                    targetLabel = profile.name,
                    updateCurrentLatency = profile.id == activeProfile.value?.id
                )
            }
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
                if (rtt != null) {
                    TunnelRuntimeBus.recordLatencySample(
                        rttMs = rtt,
                        targetLabel = p.name,
                        updateCurrentLatency = false
                    )
                }
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

    fun startLanPairing(server: DiscoveredLanServer, onPairedSuccess: () -> Unit = {}) {
        pairingPollJob?.cancel()
        pairingPollJob = viewModelScope.launch {
            _clientPairingSession.value = ClientPairingSession(
                server = server,
                status = PairingStatus.PENDING,
                isSubmitting = true
            )
            val challenge: LanPairingChallengeResult = NetworkDiagnostics.initiateLanPairing(
                ip = server.ipAddress,
                port = server.port,
                clientDeviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            )
            if (!challenge.success) {
                _clientPairingSession.value = ClientPairingSession(
                    server = server,
                    status = PairingStatus.EXPIRED,
                    isSubmitting = false,
                    errorMessage = challenge.errorMessage ?: "Failed to contact Bridge Server"
                )
                return@launch
            }

            _clientPairingSession.value = ClientPairingSession(
                server = server,
                verificationCode = challenge.verificationCode,
                status = PairingStatus.PENDING,
                isSubmitting = false,
                expiresAtMillis = challenge.expiresAtMillis
            )

            while (isActive && System.currentTimeMillis() < challenge.expiresAtMillis) {
                delay(1200L)
                val poll = NetworkDiagnostics.pollLanPairingApproval(server.ipAddress, server.port, challenge)
                when (poll.status) {
                    PairingStatus.PENDING -> Unit
                    PairingStatus.APPROVED -> {
                        val preset = DisguiseCatalog.findById(poll.disguiseId)
                        val profile = V2RayProfile(
                            name = "${server.deviceName} Bridge",
                            protocol = ProtocolType.VELO_BRIDGE,
                            address = server.ipAddress,
                            port = poll.port,
                            uuidOrPassword = poll.deviceSecret,
                            encryption = poll.cipher,
                            transport = TransportType.WEBSOCKET,
                            disguisePresetId = poll.disguiseId,
                            customDisguiseDomain = if (poll.disguiseId == "custom") poll.disguiseDomain else "",
                            sni = poll.disguiseDomain,
                            hostHeader = poll.disguiseDomain,
                            wsPath = poll.wsPath.ifBlank { preset.wsPath },
                            tlsEnabled = false,
                            isPhoneBridge = true,
                            latencyMs = server.rttMs,
                            lastTestedAt = System.currentTimeMillis()
                        )
                        val saved = repository.saveProfile(profile)
                        if (saved) {
                            repository.setActiveProfileId(profile.id)
                            _clientPairingSession.value = null
                            showBanner("Paired securely with ${server.deviceName} (Code ${challenge.verificationCode})")
                            TunnelRuntimeBus.appendLog("LAN", "Completed ECDH pairing with ${server.deviceName}", LogLevel.SUCCESS)
                            onPairedSuccess()
                        } else {
                            _clientPairingSession.value = ClientPairingSession(
                                server = server,
                                verificationCode = challenge.verificationCode,
                                status = PairingStatus.DENIED,
                                isSubmitting = false,
                                errorMessage = "Secure Vault failed to encrypt paired credential"
                            )
                        }
                        return@launch
                    }
                    PairingStatus.DENIED, PairingStatus.EXPIRED -> {
                        _clientPairingSession.value = ClientPairingSession(
                            server = server,
                            verificationCode = challenge.verificationCode,
                            status = poll.status,
                            isSubmitting = false,
                            errorMessage = poll.errorMessage ?: "Pairing ${poll.status.name.lowercase()}"
                        )
                        return@launch
                    }
                }
            }
            _clientPairingSession.value = ClientPairingSession(
                server = server,
                verificationCode = challenge.verificationCode,
                status = PairingStatus.EXPIRED,
                isSubmitting = false,
                errorMessage = "Pairing confirmation timed out"
            )
        }
    }

    fun cancelLanPairing() {
        pairingPollJob?.cancel()
        pairingPollJob = null
        _clientPairingSession.value = null
    }

    fun approvePairingRequest(context: Context, requestId: String) {
        val intent = Intent(context, BridgeServerService::class.java).apply {
            action = BridgeServerService.ACTION_APPROVE_PAIRING
            putExtra(BridgeServerService.EXTRA_REQUEST_ID, requestId)
        }
        context.startService(intent)
    }

    fun denyPairingRequest(context: Context, requestId: String) {
        val intent = Intent(context, BridgeServerService::class.java).apply {
            action = BridgeServerService.ACTION_DENY_PAIRING
            putExtra(BridgeServerService.EXTRA_REQUEST_ID, requestId)
        }
        context.startService(intent)
    }

    fun revokePairedDevice(context: Context, deviceId: String) {
        viewModelScope.launch {
            repository.revokePairedDevice(deviceId)
            val updated = repository.preferences.first().pairedDevices
            TunnelRuntimeBus.setPairedDevices(updated)
            if (serverState.value.isRunning) {
                val intent = Intent(context, BridgeServerService::class.java).apply {
                    action = BridgeServerService.ACTION_REVOKE_DEVICE
                    putExtra(BridgeServerService.EXTRA_DEVICE_ID, deviceId)
                }
                context.startService(intent)
            }
            showBanner("Revoked device access")
        }
    }

    fun runDisguiseProbe(domain: String) {
        viewModelScope.launch {
            _isProbingDisguise.value = true
            val res = NetworkDiagnostics.runDisguiseProbe(domain)
            TunnelRuntimeBus.recordProbeResult(res)
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
        val now = System.currentTimeMillis()
        if (now - lastConnectTriggerMs < 900L) return
        val currentState = telemetry.value.state
        if (currentState == VpnConnectionState.CONNECTING ||
            currentState == VpnConnectionState.CONNECTED ||
            currentState == VpnConnectionState.RECONNECTING
        ) {
            return
        }

        val profile = activeProfile.value
        if (profile == null) {
            showBanner("Please import a config or pair a Phone Server first.")
            return
        }

        if (!profile.protocol.supportLevel.isNativeTunnelSupported) {
            val msg = "${profile.protocol.label} supports import, edit, disguise & SNI probing only. Select a Phone Bridge (VELO_BRIDGE) profile to start the built-in VPN tunnel."
            TunnelRuntimeBus.updateTelemetry {
                it.copy(
                    state = VpnConnectionState.ERROR,
                    activeProfileId = profile.id,
                    activeProfileName = profile.name,
                    activeProtocol = profile.protocol,
                    errorMessage = msg
                )
            }
            showBanner(msg)
            return
        }

        if (profile.uuidOrPassword.isBlank()) {
            val msg = "Profile secret could not be decrypted or is empty. Re-import or re-pair this Phone Bridge."
            TunnelRuntimeBus.updateTelemetry { it.copy(state = VpnConnectionState.ERROR, errorMessage = msg) }
            showBanner(msg)
            return
        }

        lastConnectTriggerMs = now
        val prefs = preferences.value
        val intent = BridgeVpnService.createStartIntent(
            context = context,
            profile = profile,
            dnsServer = prefs.dnsServer,
            bypassLan = prefs.bypassLan,
            highSpeedMode = telemetry.value.highSpeedMode,
            autoReconnect = prefs.autoReconnect
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
            showBanner("Rotated Bridge Secret UUID")
        }
    }

    fun refreshLocalIps() {
        val netType = NetworkDiagnostics.getActiveNetworkType(getApplication())
        TunnelRuntimeBus.refreshLocalIps(netType)
    }

    fun toggleBridgeServer(context: Context) {
        val current = serverState.value
        if (current.isBinding) return
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
                putExtra(BridgeServerService.EXTRA_HIGH_SPEED, current.highSpeedMode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    fun setHighSpeedMode(context: Context, enabled: Boolean) {
        val mode = if (enabled) PerformanceMode.HIGH_SPEED else PerformanceMode.BALANCED
        TunnelRuntimeBus.updateTelemetry {
            it.copy(highSpeedMode = enabled, mtu = mode.mtu)
        }
        TunnelRuntimeBus.updateServerState {
            it.copy(highSpeedMode = enabled)
        }
        TunnelRuntimeBus.appendLog(
            "Speed",
            "Performance mode switched to ${mode.label} (MTU ${mode.mtu}, ${mode.socketBufferBytes / 1024}KB buffers, ${mode.relayChunkBytes / 1024}KB frames)",
            LogLevel.INFO
        )
        viewModelScope.launch {
            repository.setHighSpeedMode(enabled)

            val vpnActive = telemetry.value.state == VpnConnectionState.CONNECTED ||
                telemetry.value.state == VpnConnectionState.CONNECTING
            val profile = activeProfile.value
            if (vpnActive && profile != null) {
                val prefs = preferences.value
                val vpnIntent = BridgeVpnService.createStartIntent(
                    context = context,
                    profile = profile,
                    dnsServer = prefs.dnsServer,
                    bypassLan = prefs.bypassLan,
                    highSpeedMode = enabled,
                    autoReconnect = prefs.autoReconnect
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(vpnIntent)
                } else {
                    context.startService(vpnIntent)
                }
            }

            val currentServer = serverState.value
            if (currentServer.isRunning) {
                val uuid = currentServer.uuid.ifBlank { repository.ensureServerUuid() }
                val serverIntent = Intent(context, BridgeServerService::class.java).apply {
                    action = BridgeServerService.ACTION_START
                    putExtra(BridgeServerService.EXTRA_PORT, currentServer.listenPort)
                    putExtra(BridgeServerService.EXTRA_UUID, uuid)
                    putExtra(BridgeServerService.EXTRA_DISGUISE_ID, currentServer.disguisePresetId)
                    putExtra(BridgeServerService.EXTRA_CUSTOM_DOMAIN, currentServer.customDisguiseDomain)
                    putExtra(BridgeServerService.EXTRA_WS_PATH, currentServer.wsPath)
                    putExtra(BridgeServerService.EXTRA_CIPHER, currentServer.encryption.name)
                    putExtra(BridgeServerService.EXTRA_LAN_DISCOVERY, currentServer.lanDiscoveryEnabled)
                    putExtra(BridgeServerService.EXTRA_HIGH_SPEED, enabled)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serverIntent)
                } else {
                    context.startService(serverIntent)
                }
            }

            val appliedSuffix = if (vpnActive || currentServer.isRunning) " • Tunnel reconfigured" else ""
            showBanner("${mode.label} mode ${if (enabled) "enabled" else "disabled"} (MTU ${mode.mtu})$appliedSuffix")
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

    fun setAppearanceMode(mode: AppearanceMode) {
        viewModelScope.launch { repository.setAppearanceMode(mode) }
    }

    fun setDarkTheme(dark: Boolean) {
        viewModelScope.launch { repository.setDarkTheme(dark) }
    }

    fun setHapticFeedback(enabled: Boolean) {
        viewModelScope.launch { repository.setHapticFeedback(enabled) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setNotificationsEnabled(enabled) }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoReconnect(enabled) }
    }

    fun setSensitiveConfigProtection(enabled: Boolean) {
        viewModelScope.launch {
            val ok = repository.setSensitiveConfigProtection(enabled)
            showBanner(
                when {
                    !ok -> "Some stored secrets could not be re-encrypted cleanly"
                    enabled -> "Hardware-backed secret encryption enabled"
                    else -> "Sensitive config encryption disabled"
                }
            )
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch { repository.setOnboardingCompleted(true) }
    }

    fun clearLogs() = TunnelRuntimeBus.clearLogs()
}
