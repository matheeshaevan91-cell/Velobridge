package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.PerformanceMode
import com.example.domain.model.TelemetrySnapshot
import com.example.domain.model.V2RayProfile
import com.example.domain.model.VpnConnectionState
import com.example.ui.components.ConnectionHeroButton
import com.example.ui.components.DisguiseDomainPill
import com.example.ui.components.DualTrafficChart
import com.example.ui.components.ProtocolSupportBadge
import com.example.ui.components.VeloCard
import com.example.ui.components.VeloErrorBanner
import com.example.ui.components.VeloScreenHeader
import com.example.ui.components.VeloStatusBadge
import com.example.ui.components.disguiseIcon
import com.example.ui.components.formatBytes
import com.example.ui.components.formatDuration
import com.example.ui.components.formatRelativeTimestamp
import com.example.ui.components.formatSpeed
import com.example.ui.theme.LocalVeloColors
import com.example.ui.theme.MonoDataStyle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    profiles: List<V2RayProfile>,
    activeProfile: V2RayProfile?,
    telemetry: TelemetrySnapshot,
    serverIsRunning: Boolean,
    highSpeedMode: Boolean,
    onToggleConnection: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onToggleHighSpeedMode: (Boolean) -> Unit,
    onSelectDisguise: (presetId: String, customDomain: String) -> Unit,
    onPingActive: (V2RayProfile) -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToBridge: () -> Unit,
    onNavigateToDisguiseLab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val velo = LocalVeloColors.current
    var showCustomDomainDialog by remember { mutableStateOf(false) }
    var showProfileSheet by remember { mutableStateOf(false) }
    var customDomainText by remember(activeProfile) {
        mutableStateOf(activeProfile?.customDisguiseDomain ?: "")
    }
    var uptimeDisplay by remember { mutableStateOf("00:00:00") }

    LaunchedEffect(telemetry.state, telemetry.connectedSinceMillis) {
        while (telemetry.state == VpnConnectionState.CONNECTED ||
            telemetry.state == VpnConnectionState.RECONNECTING
        ) {
            uptimeDisplay = formatDuration(telemetry.connectedSinceMillis)
            delay(1000L)
        }
        uptimeDisplay = "00:00:00"
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .testTag("dashboard_lazy_column"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                VeloScreenHeader(
                    title = "VeloBridge",
                    subtitle = "Private encrypted bridge & traffic camouflage",
                    trailing = {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (serverIsRunning) velo.successContainer else MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(
                                1.dp,
                                if (serverIsRunning) velo.success.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline
                            ),
                            onClick = onNavigateToBridge,
                            modifier = Modifier.testTag("server_status_badge")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Router,
                                    contentDescription = "Bridge Server status",
                                    tint = if (serverIsRunning) velo.success else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (serverIsRunning) "Server ON" else "Server OFF",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (serverIsRunning) velo.success else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )
            }

            // Hero Connection Card
            item {
                val borderColor = when (telemetry.state) {
                    VpnConnectionState.CONNECTED -> velo.success.copy(alpha = 0.55f)
                    VpnConnectionState.CONNECTING, VpnConnectionState.RECONNECTING -> velo.warning.copy(alpha = 0.55f)
                    VpnConnectionState.ERROR -> velo.danger.copy(alpha = 0.55f)
                    VpnConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
                }
                VeloCard(borderColor = borderColor) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        ConnectionHeroButton(
                            state = telemetry.state,
                            onClick = onToggleConnection
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        val effectiveDisguise = activeProfile?.effectiveDisguiseDomain ?: telemetry.disguiseDomain
                        Text(
                            text = when (telemetry.state) {
                                VpnConnectionState.CONNECTED -> "Tunnel Verified • Disguised as $effectiveDisguise"
                                VpnConnectionState.CONNECTING -> "Authenticating & Verifying Tunnel…"
                                VpnConnectionState.RECONNECTING -> "Network changed • Reconnecting…"
                                VpnConnectionState.ERROR -> "Connection Failed"
                                VpnConnectionState.DISCONNECTED -> if (activeProfile != null) {
                                    "Ready • ${activeProfile.name}"
                                } else {
                                    "Select or import a configuration below"
                                }
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            DisguiseDomainPill(
                                presetId = activeProfile?.disguisePresetId ?: "youtube",
                                domain = effectiveDisguise
                            )
                            VeloStatusBadge(
                                label = activeProfile?.encryption?.label ?: telemetry.cipherLabel,
                                accentColor = velo.success,
                                containerColor = velo.successContainer,
                                icon = Icons.Filled.Lock
                            )
                            if (highSpeedMode) {
                                VeloStatusBadge(
                                    label = PerformanceMode.HIGH_SPEED.badge,
                                    accentColor = velo.info,
                                    containerColor = velo.infoContainer,
                                    icon = Icons.Filled.Bolt
                                )
                            }
                        }

                        AnimatedVisibility(visible = telemetry.errorMessage != null) {
                            VeloErrorBanner(
                                message = telemetry.errorMessage.orEmpty(),
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }
                }
            }

            // Active Configuration Card + Quick Switcher Sheet Trigger
            item {
                VeloCard {
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
                                    imageVector = if (activeProfile?.isPhoneBridge == true) {
                                        Icons.Filled.PhoneAndroid
                                    } else {
                                        Icons.Filled.Dns
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = activeProfile?.name ?: "No Active Configuration",
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (activeProfile != null) {
                                            "${activeProfile.protocol.badge} • ${activeProfile.address}:${activeProfile.port}"
                                        } else {
                                            "Import a link, scan QR, or pair a Phone Server"
                                        },
                                        style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (activeProfile != null) {
                                TextButton(
                                    onClick = { onPingActive(activeProfile) },
                                    modifier = Modifier.testTag("dashboard_ping_button")
                                ) {
                                    Text("Ping")
                                }
                            }
                        }
                        if (activeProfile != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            ProtocolSupportBadge(protocol = activeProfile.protocol)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (profiles.isNotEmpty()) showProfileSheet = true
                                    else onNavigateToProfiles()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("dashboard_manage_profiles_button")
                            ) {
                                Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (profiles.isNotEmpty()) "Switch (${profiles.size})" else "Profiles")
                            }
                            Button(
                                onClick = onNavigateToBridge,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("dashboard_phone_bridge_button")
                            ) {
                                Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Phone Bridge")
                            }
                        }
                    }
                }
            }

            // Live Throughput & Session Stats Card
            item {
                VeloCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Live Tunnel Telemetry", style = MaterialTheme.typography.titleSmall)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                VeloStatusBadge(
                                    label = "MTU ${if (highSpeedMode) PerformanceMode.HIGH_SPEED.mtu else telemetry.mtu}",
                                    accentColor = if (highSpeedMode) velo.info else MaterialTheme.colorScheme.onSurfaceVariant,
                                    containerColor = if (highSpeedMode) velo.infoContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                                Text(
                                    text = uptimeDisplay,
                                    style = MonoDataStyle,
                                    color = if (telemetry.state == VpnConnectionState.CONNECTED) {
                                        velo.success
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        DualTrafficChart(
                            samples = telemetry.bandwidthSamples,
                            isActive = telemetry.state == VpnConnectionState.CONNECTED
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TelemetryStatTile(
                                icon = Icons.Filled.ArrowDownward,
                                label = "Download",
                                value = formatSpeed(telemetry.downSpeedBps),
                                sub = formatBytes(telemetry.totalDownBytes),
                                accent = velo.success,
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryStatTile(
                                icon = Icons.Filled.ArrowUpward,
                                label = "Upload",
                                value = formatSpeed(telemetry.upSpeedBps),
                                sub = formatBytes(telemetry.totalUpBytes),
                                accent = velo.info,
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryStatTile(
                                icon = Icons.Filled.NetworkPing,
                                label = "Latency",
                                value = (telemetry.latencyMs ?: activeProfile?.latencyMs)?.let { "${it} ms" } ?: "-- ms",
                                sub = "${telemetry.activeFlowCount} flows",
                                accent = velo.warning,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Connection Metadata Details Card
            item {
                VeloCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Connection Information", style = MaterialTheme.typography.titleSmall)
                        InfoRow("Server", activeProfile?.let { "${it.address}:${it.port}" } ?: "Not selected")
                        InfoRow("Protocol", activeProfile?.protocol?.label ?: "--")
                        InfoRow("Encryption", activeProfile?.encryption?.label ?: telemetry.cipherLabel)
                        InfoRow("Network Type", telemetry.networkType)
                        InfoRow("Quality", telemetry.qualityTier.label)
                        InfoRow("Last Connected", formatRelativeTimestamp(telemetry.lastSuccessfulConnectionMillis))
                    }
                }
            }

            // High-Speed Data Mode Toggle Card
            item {
                val activeMode = if (highSpeedMode) PerformanceMode.HIGH_SPEED else PerformanceMode.BALANCED
                VeloCard(
                    borderColor = if (highSpeedMode) velo.info.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.testTag("high_speed_mode_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (highSpeedMode) velo.infoContainer else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Bolt,
                                    contentDescription = null,
                                    tint = if (highSpeedMode) velo.info else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.padding(end = 12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("High-Speed Data Mode", style = MaterialTheme.typography.titleSmall)
                                    VeloStatusBadge(
                                        label = activeMode.badge,
                                        accentColor = if (highSpeedMode) velo.info else MaterialTheme.colorScheme.onSurfaceVariant,
                                        containerColor = if (highSpeedMode) velo.infoContainer else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = activeMode.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = highSpeedMode,
                            onCheckedChange = onToggleHighSpeedMode,
                            modifier = Modifier.testTag("high_speed_mode_switch")
                        )
                    }
                }
            }

            // 1-Tap Domain Disguise Card
            item {
                VeloCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("1-Tap Domain Disguise", style = MaterialTheme.typography.titleSmall)
                            TextButton(
                                onClick = onNavigateToDisguiseLab,
                                modifier = Modifier.testTag("dashboard_open_disguise_lab")
                            ) {
                                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("SNI Lab")
                            }
                        }
                        Text(
                            text = "Camouflage handshake metadata as trusted domain traffic.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DisguiseCatalog.presets.forEach { preset ->
                                val selected = activeProfile?.disguisePresetId == preset.id
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        if (preset.id == "custom") {
                                            showCustomDomainDialog = true
                                        } else {
                                            onSelectDisguise(preset.id, "")
                                        }
                                    },
                                    label = { Text(preset.title) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = disguiseIcon(preset.id),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    modifier = Modifier.testTag("disguise_chip_${preset.id}")
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showProfileSheet) {
        ModalBottomSheet(onDismissRequest = { showProfileSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Switch Configuration", style = MaterialTheme.typography.titleLarge)
                    TextButton(
                        onClick = {
                            showProfileSheet = false
                            onNavigateToProfiles()
                        }
                    ) {
                        Text("Manage All")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        val isSelected = profile.id == activeProfile?.id
                        VeloCard(
                            borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            onClick = {
                                onSelectProfile(profile.id)
                                showProfileSheet = false
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(profile.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${profile.protocol.badge} • ${profile.address}:${profile.port} • ${profile.effectiveDisguiseDomain}",
                                        style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = "Active",
                                        tint = velo.success
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showCustomDomainDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDomainDialog = false },
            title = { Text("Custom Disguise Domain") },
            text = {
                Column {
                    Text(
                        text = "Enter a trusted domain (SNI & Host header) for tunnel camouflage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customDomainText,
                        onValueChange = { customDomainText = it },
                        label = { Text("Domain (e.g. cdn.example.com)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_disguise_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customDomainText.isNotBlank()) {
                            onSelectDisguise("custom", customDomainText.trim())
                            showCustomDomainDialog = false
                        }
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDomainDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MonoDataStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun TelemetryStatTile(
    icon: ImageVector,
    label: String,
    value: String,
    sub: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MonoDataStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
