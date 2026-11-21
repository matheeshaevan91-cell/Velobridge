package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.crypto.BridgeCrypto
import com.example.core.parser.QrCodeGenerator
import com.example.core.parser.V2RayConfigParser
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.LogEntry
import com.example.domain.model.ServerRuntimeState
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.VeloCard
import com.example.ui.components.VeloStatusBadge
import com.example.ui.components.copyToClipboard
import com.example.ui.components.disguiseIcon
import com.example.ui.components.formatBytes
import com.example.ui.components.formatDuration
import com.example.ui.components.formatLogTime
import com.example.ui.theme.LocalVeloColors
import com.example.ui.theme.MonoDataStyle
import kotlinx.coroutines.delay

@Composable
fun BridgeServerScreen(
    serverState: ServerRuntimeState,
    logs: List<LogEntry>,
    onToggleServer: () -> Unit,
    onUpdateDraft: (
        port: Int,
        disguiseId: String,
        customDomain: String,
        encryption: EncryptionCipher,
        wsPath: String,
        lanDiscovery: Boolean
    ) -> Unit,
    onRegenerateUuid: () -> Unit,
    onRefreshIps: () -> Unit,
    onToggleHighSpeedMode: (Boolean) -> Unit,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val velo = LocalVeloColors.current

    var portText by remember(serverState.listenPort) { mutableStateOf(serverState.listenPort.toString()) }
    var selectedDisguiseId by remember(serverState.disguisePresetId) { mutableStateOf(serverState.disguisePresetId) }
    var customDomain by remember(serverState.customDisguiseDomain) { mutableStateOf(serverState.customDisguiseDomain) }
    var selectedCipher by remember(serverState.encryption) { mutableStateOf(serverState.encryption) }
    var lanDiscovery by remember(serverState.lanDiscoveryEnabled) { mutableStateOf(serverState.lanDiscoveryEnabled) }
    var selectedIp by remember(serverState.localIps) { mutableStateOf(serverState.primaryIp) }
    var revealUuid by remember { mutableStateOf(false) }
    var showRotateUuidDialog by remember { mutableStateOf(false) }
    var serverUptime by remember { mutableStateOf("00:00:00") }

    LaunchedEffect(serverState.isRunning, serverState.startedAtMillis) {
        while (serverState.isRunning) {
            serverUptime = formatDuration(serverState.startedAtMillis)
            delay(1000L)
        }
        serverUptime = "00:00:00"
    }

    val effectiveDomain = DisguiseCatalog.resolveEffectiveDomain(selectedDisguiseId, customDomain)
    val effectiveWsPath = DisguiseCatalog.findById(selectedDisguiseId).wsPath

    val pairingUri = remember(selectedIp, serverState.listenPort, serverState.uuid, selectedDisguiseId, effectiveDomain, selectedCipher) {
        if (serverState.uuid.isBlank()) ""
        else V2RayConfigParser.toVeloBridgeUri(
            ip = selectedIp,
            port = serverState.listenPort,
            uuid = serverState.uuid,
            disguiseId = selectedDisguiseId,
            disguiseDomain = effectiveDomain,
            wsPath = effectiveWsPath,
            cipher = selectedCipher,
            name = "Phone Bridge ($selectedIp)"
        )
    }
    val qrBitmap = remember(pairingUri) { QrCodeGenerator.generateBitmap(pairingUri, 512) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .testTag("bridge_lazy_column"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Illustrated Hero Banner
            item {
                VeloCard {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(156.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_bridge_hero_1795239921412),
                            contentDescription = stringResource(R.string.cd_bridge_hero),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.25f),
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.92f)
                                        )
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Phone Bridge Server",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Host an authenticated AEAD relay on this device and pair your client phone over Wi-Fi or Hotspot.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Readiness, Auth Audit & Active Forwarding Status Card
            item {
                VeloCard(
                    borderColor = if (serverState.isRunning) velo.success.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Filled.Router,
                                    contentDescription = null,
                                    tint = if (serverState.isRunning) velo.success else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = if (serverState.isRunning) {
                                                stringResource(R.string.server_running)
                                            } else {
                                                stringResource(R.string.server_stopped)
                                            },
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (serverState.isRunning) velo.success else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (serverState.highSpeedMode) {
                                            VeloStatusBadge(
                                                label = "TURBO",
                                                accentColor = velo.info,
                                                containerColor = velo.infoContainer
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (serverState.isRunning) {
                                            "Listening $selectedIp:${serverState.listenPort} • $serverUptime • ${serverState.activeNetworkType}"
                                        } else {
                                            "Standby on $selectedIp:${serverState.listenPort} • ${serverState.activeNetworkType}"
                                        },
                                        style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = onRefreshIps) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh local IP addresses")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (!serverState.isRunning) {
                                    val parsedPort = portText.toIntOrNull()?.takeIf { it in 1024..65535 } ?: 10808
                                    onUpdateDraft(
                                        parsedPort,
                                        selectedDisguiseId,
                                        customDomain,
                                        selectedCipher,
                                        effectiveWsPath,
                                        lanDiscovery
                                    )
                                }
                                onToggleServer()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (serverState.isRunning) velo.danger else velo.success,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("server_power_button")
                        ) {
                            Icon(
                                imageVector = if (serverState.isRunning) Icons.Filled.Stop else Icons.Filled.PowerSettingsNew,
                                contentDescription = stringResource(R.string.cd_server_power)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (serverState.isRunning) "Stop Bridge Server" else "Start Bridge Server")
                        }

                        if (serverState.isRunning) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ServerMetricChip("Active Peers", "${serverState.activePeers.size}", Modifier.weight(1f))
                                ServerMetricChip("Auth OK", "${serverState.authenticatedHandshakes}", Modifier.weight(1f))
                                ServerMetricChip("Blocked", "${serverState.rejectedHandshakes}", Modifier.weight(1f))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ServerMetricChip("Relayed Up", formatBytes(serverState.totalBytesUp), Modifier.weight(1f))
                                ServerMetricChip("Relayed Down", formatBytes(serverState.totalBytesDown), Modifier.weight(1f))
                            }

                            if (serverState.activePeers.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Connected Peers",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                serverState.activePeers.take(4).forEach { peer ->
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${peer.remoteIp} (${peer.disguiseDomain})",
                                                style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = "↑${formatBytes(peer.bytesUp)} ↓${formatBytes(peer.bytesDown)}",
                                                style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                                color = velo.success
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Server Configuration Card
            item {
                VeloCard(modifier = Modifier.testTag("server_settings_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Shield, contentDescription = null, tint = velo.success, modifier = Modifier.size(18.dp))
                            Text("Server Configuration & Security", style = MaterialTheme.typography.titleSmall)
                        }

                        if (serverState.localIps.isNotEmpty()) {
                            Text(
                                text = "Local Network Interface IP",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                serverState.localIps.forEach { info ->
                                    FilterChip(
                                        selected = selectedIp == info.ipAddress,
                                        onClick = { selectedIp = info.ipAddress },
                                        label = { Text("${info.interfaceName}: ${info.ipAddress}") }
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { input ->
                                    val digits = input.filter { it.isDigit() }.take(5)
                                    portText = digits
                                    digits.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { validPort ->
                                        onUpdateDraft(validPort, selectedDisguiseId, customDomain, selectedCipher, effectiveWsPath, lanDiscovery)
                                    }
                                },
                                label = { Text("Port") },
                                enabled = !serverState.isRunning,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier
                                    .weight(0.36f)
                                    .testTag("server_port_input")
                            )

                            OutlinedTextField(
                                value = if (revealUuid) serverState.uuid else BridgeCrypto.maskSecret(serverState.uuid),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Bridge Secret UUID") },
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = { revealUuid = !revealUuid }) {
                                            Icon(
                                                imageVector = if (revealUuid) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                                contentDescription = if (revealUuid) "Hide secret" else "Reveal secret"
                                            )
                                        }
                                        IconButton(
                                            onClick = { showRotateUuidDialog = true },
                                            enabled = !serverState.isRunning
                                        ) {
                                            Icon(Icons.Filled.Refresh, contentDescription = "Rotate Bridge Secret UUID")
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.weight(0.64f)
                            )
                        }

                        Text(
                            text = "Required Traffic Disguise",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DisguiseCatalog.presets.forEach { preset ->
                                FilterChip(
                                    selected = selectedDisguiseId == preset.id,
                                    onClick = {
                                        if (!serverState.isRunning) {
                                            selectedDisguiseId = preset.id
                                            onUpdateDraft(
                                                portText.toIntOrNull() ?: 10808,
                                                preset.id,
                                                customDomain,
                                                selectedCipher,
                                                preset.wsPath,
                                                lanDiscovery
                                            )
                                        }
                                    },
                                    label = { Text(preset.title) },
                                    leadingIcon = {
                                        Icon(disguiseIcon(preset.id), contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                )
                            }
                        }

                        if (selectedDisguiseId == "custom") {
                            OutlinedTextField(
                                value = customDomain,
                                onValueChange = {
                                    customDomain = it
                                    onUpdateDraft(
                                        portText.toIntOrNull() ?: 10808,
                                        "custom",
                                        it,
                                        selectedCipher,
                                        "/ray",
                                        lanDiscovery
                                    )
                                },
                                label = { Text("Custom Disguise Domain") },
                                enabled = !serverState.isRunning,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Text(
                            text = "Tunnel Encryption Cipher",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                EncryptionCipher.AES_256_GCM,
                                EncryptionCipher.CHACHA20_POLY1305
                            ).forEach { cipher ->
                                FilterChip(
                                    selected = selectedCipher == cipher,
                                    onClick = {
                                        if (!serverState.isRunning) {
                                            selectedCipher = cipher
                                            onUpdateDraft(
                                                portText.toIntOrNull() ?: 10808,
                                                selectedDisguiseId,
                                                customDomain,
                                                cipher,
                                                effectiveWsPath,
                                                lanDiscovery
                                            )
                                        }
                                    },
                                    label = { Text(cipher.label) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(15.dp))
                                    }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("LAN Auto-Discovery Beacon", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "Broadcasts pairing offer on local Wi-Fi/Hotspot",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = lanDiscovery,
                                onCheckedChange = { enabled ->
                                    lanDiscovery = enabled
                                    onUpdateDraft(
                                        portText.toIntOrNull() ?: 10808,
                                        selectedDisguiseId,
                                        customDomain,
                                        selectedCipher,
                                        effectiveWsPath,
                                        enabled
                                    )
                                },
                                enabled = !serverState.isRunning
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Bolt,
                                    contentDescription = null,
                                    tint = if (serverState.highSpeedMode) velo.info else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("High-Speed Relay Mode", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "256 KB TCP buffers, 48 KB encrypted frames & low-delay QoS",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = serverState.highSpeedMode,
                                onCheckedChange = onToggleHighSpeedMode,
                                modifier = Modifier.testTag("server_high_speed_switch")
                            )
                        }
                    }
                }
            }

            // Instant Client Pairing QR & Export
            if (pairingUri.isNotBlank()) {
                item {
                    VeloCard {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Pair Client Phone", style = MaterialTheme.typography.titleSmall)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Use Profiles -> Pair LAN, import a screenshot via QR Image, or copy this link:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            if (qrBitmap != null) {
                                Surface(
                                    color = Color.White,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.clip(MaterialTheme.shapes.medium)
                                ) {
                                    Image(
                                        bitmap = qrBitmap.asImageBitmap(),
                                        contentDescription = stringResource(R.string.cd_qr_code),
                                        modifier = Modifier
                                            .size(180.dp)
                                            .padding(10.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        copyToClipboard(context, "VeloBridge Pairing URI", pairingUri)
                                        onNotify("Copied velobridge:// pairing URI")
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("copy_server_pairing_uri")
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Copy Link")
                                }
                                OutlinedButton(
                                    onClick = {
                                        val json = V2RayConfigParser.toXrayServerJson(serverState)
                                        copyToClipboard(context, "V2Ray Server JSON", json)
                                        onNotify("Copied Xray Server JSON")
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Server JSON")
                                }
                            }
                        }
                    }
                }
            }

            // Recent Bridge Activity
            item {
                VeloCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Recent Bridge Activity", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        val recent = logs.take(10)
                        if (recent.isEmpty()) {
                            Text(
                                text = "Start the Bridge Server to monitor authenticated handshakes and traffic relays.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            recent.forEach { entry ->
                                Text(
                                    text = "[${formatLogTime(entry.timestamp)}] ${entry.source}: ${entry.message}",
                                    style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRotateUuidDialog) {
        ConfirmActionDialog(
            title = "Rotate Bridge Secret UUID?",
            message = "Existing paired client phones will need the new pairing link or QR code to reconnect.",
            confirmLabel = "Rotate Secret",
            isDestructive = false,
            onConfirm = onRegenerateUuid,
            onDismiss = { showRotateUuidDialog = false }
        )
    }
}

@Composable
private fun ServerMetricChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, style = MonoDataStyle, fontWeight = FontWeight.Bold)
        }
    }
}
