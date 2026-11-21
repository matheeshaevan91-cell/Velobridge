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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.example.core.parser.QrCodeGenerator
import com.example.core.parser.V2RayConfigParser
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.LogEntry
import com.example.domain.model.ServerRuntimeState
import com.example.ui.components.copyToClipboard
import com.example.ui.components.disguiseIcon
import com.example.ui.components.formatBytes
import com.example.ui.components.formatDuration
import com.example.ui.components.formatLogTime
import com.example.ui.theme.MonoDataStyle
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.SignalCyan
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
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var portText by remember(serverState.listenPort) { mutableStateOf(serverState.listenPort.toString()) }
    var selectedDisguiseId by remember(serverState.disguisePresetId) { mutableStateOf(serverState.disguisePresetId) }
    var customDomain by remember(serverState.customDisguiseDomain) { mutableStateOf(serverState.customDisguiseDomain) }
    var selectedCipher by remember(serverState.encryption) { mutableStateOf(serverState.encryption) }
    var lanDiscovery by remember(serverState.lanDiscoveryEnabled) { mutableStateOf(serverState.lanDiscoveryEnabled) }
    var selectedIp by remember(serverState.localIps) { mutableStateOf(serverState.primaryIp) }
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
            name = "Old Phone Bridge ($selectedIp)"
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
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Illustrated Hero Banner
            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(172.dp)
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
                                            Color(0x220A111C),
                                            Color(0xE60A111C)
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
                                text = "Phone-to-Phone V2Ray Bridge",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            Text(
                                text = "Turn an old Android phone into an encrypted V2Ray bridge server, then pair your daily phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }
                }
            }

            // Server Power & Telemetry Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(
                        1.dp,
                        if (serverState.isRunning) ShieldEmerald.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Router,
                                    contentDescription = null,
                                    tint = if (serverState.isRunning) ShieldEmerald else SignalCyan
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = if (serverState.isRunning) {
                                            stringResource(R.string.server_running)
                                        } else {
                                            stringResource(R.string.server_stopped)
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (serverState.isRunning) ShieldEmerald else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (serverState.isRunning) {
                                            "Listening $selectedIp:${serverState.listenPort} • $serverUptime"
                                        } else {
                                            "Ready on $selectedIp:${serverState.listenPort}"
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
                                containerColor = if (serverState.isRunning) MaterialTheme.colorScheme.error else ShieldEmerald,
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
                                ServerMetricChip("Peers", "${serverState.activePeers.size}", Modifier.weight(1f))
                                ServerMetricChip("Relayed Up", formatBytes(serverState.totalBytesUp), Modifier.weight(1f))
                                ServerMetricChip("Relayed Down", formatBytes(serverState.totalBytesDown), Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // Server Configuration Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Bridge Server Settings",
                            style = MaterialTheme.typography.titleSmall
                        )

                        if (serverState.localIps.isNotEmpty()) {
                            Text(
                                text = "Local Network Interface IP (share with Client Phone)",
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
                                    .weight(0.4f)
                                    .testTag("server_port_input")
                            )

                            OutlinedTextField(
                                value = serverState.uuid.take(18) + "…",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Auth UUID") },
                                trailingIcon = {
                                    IconButton(
                                        onClick = onRegenerateUuid,
                                        enabled = !serverState.isRunning
                                    ) {
                                        Icon(Icons.Filled.Refresh, contentDescription = "Regenerate UUID")
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.weight(0.6f)
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
                                    text = "Lets your Client Phone find this server automatically on the same Wi-Fi/Hotspot",
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
                    }
                }
            }

            // Instant Client Pairing QR & Config Export
            if (pairingUri.isNotBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Devices, contentDescription = null, tint = SignalCyan)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Pair Client Phone", style = MaterialTheme.typography.titleSmall)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "On your Client Phone, open Profiles -> Pair LAN, or copy/scan this link:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(14.dp))
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

            // Live Server Logs
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Recent Bridge Activity", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        val recent = logs.take(10)
                        if (recent.isEmpty()) {
                            Text(
                                text = "Start the Bridge Server to monitor incoming handshakes and traffic relays.",
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
