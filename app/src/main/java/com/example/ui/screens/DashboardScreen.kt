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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.TelemetrySnapshot
import com.example.domain.model.V2RayProfile
import com.example.domain.model.VpnConnectionState
import com.example.ui.components.ConnectionPowerRing
import com.example.ui.components.DisguiseDomainPill
import com.example.ui.components.TrafficSparklineChart
import com.example.ui.components.disguiseIcon
import com.example.ui.components.formatBytes
import com.example.ui.components.formatDuration
import com.example.ui.components.formatSpeed
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.DangerCoral
import com.example.ui.theme.MonoDataStyle
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.SignalCyan
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    activeProfile: V2RayProfile?,
    telemetry: TelemetrySnapshot,
    serverIsRunning: Boolean,
    onToggleConnection: () -> Unit,
    onSelectDisguise: (presetId: String, customDomain: String) -> Unit,
    onPingActive: (V2RayProfile) -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToBridge: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCustomDomainDialog by remember { mutableStateOf(false) }
    var customDomainText by remember(activeProfile) {
        mutableStateOf(activeProfile?.customDisguiseDomain ?: "")
    }
    var uptimeDisplay by remember { mutableStateOf("00:00:00") }

    LaunchedEffect(telemetry.state, telemetry.connectedSinceMillis) {
        while (telemetry.state == VpnConnectionState.CONNECTED) {
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
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "VeloBridge",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Encrypted V2Ray & Private Phone Bridge",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (serverIsRunning) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
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
                                tint = if (serverIsRunning) ShieldEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (serverIsRunning) "Server ON" else "Server OFF",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (serverIsRunning) ShieldEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Hero Connection Control Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(
                        1.dp,
                        when (telemetry.state) {
                            VpnConnectionState.CONNECTED -> ShieldEmerald.copy(alpha = 0.55f)
                            VpnConnectionState.ERROR -> DangerCoral.copy(alpha = 0.55f)
                            else -> MaterialTheme.colorScheme.outline
                        }
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        ConnectionPowerRing(
                            state = telemetry.state,
                            onClick = onToggleConnection
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        val effectiveDisguise = activeProfile?.effectiveDisguiseDomain ?: telemetry.disguiseDomain
                        Text(
                            text = when (telemetry.state) {
                                VpnConnectionState.CONNECTED -> "Protected • Disguised as $effectiveDisguise"
                                VpnConnectionState.CONNECTING -> "Building encrypted tunnel…"
                                VpnConnectionState.ERROR -> "Connection Interrupted"
                                VpnConnectionState.DISCONNECTED -> if (activeProfile != null) {
                                    "Ready to bridge via ${activeProfile.name}"
                                } else {
                                    "Import a config or pair a Phone Server to start"
                                }
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DisguiseDomainPill(
                                presetId = activeProfile?.disguisePresetId ?: "youtube",
                                domain = effectiveDisguise
                            )
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = ShieldEmerald,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = activeProfile?.encryption?.label ?: "AES-256-GCM",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = telemetry.errorMessage != null) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = telemetry.errorMessage.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Live Telemetry & Sparkline Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Live Tunnel Telemetry",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = uptimeDisplay,
                                style = MonoDataStyle,
                                color = if (telemetry.state == VpnConnectionState.CONNECTED) {
                                    ShieldEmerald
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        TrafficSparklineChart(
                            samples = telemetry.speedHistory,
                            accentColor = if (telemetry.state == VpnConnectionState.CONNECTED) {
                                SignalCyan
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TelemetryStatTile(
                                icon = Icons.Filled.ArrowDownward,
                                label = "Download",
                                value = formatSpeed(telemetry.downSpeedBps),
                                sub = formatBytes(telemetry.totalDownBytes),
                                accent = ShieldEmerald,
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryStatTile(
                                icon = Icons.Filled.ArrowUpward,
                                label = "Upload",
                                value = formatSpeed(telemetry.upSpeedBps),
                                sub = formatBytes(telemetry.totalUpBytes),
                                accent = SignalCyan,
                                modifier = Modifier.weight(1f)
                            )
                            TelemetryStatTile(
                                icon = Icons.Filled.NetworkPing,
                                label = "Latency",
                                value = (telemetry.latencyMs ?: activeProfile?.latencyMs)?.let { "${it} ms" } ?: "-- ms",
                                sub = "${telemetry.activeFlowCount} flows",
                                accent = AlertAmber,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Quick Domain Disguise Camouflage Selector
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "1-Tap Domain Disguise",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Make your browsing look like everyday traffic to bypass restrictive networks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
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
                                            modifier = Modifier.size(18.dp)
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

            // Active Server / Quick Bridge Actions
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
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
                                    imageVector = if (activeProfile?.isPhoneBridge == true) {
                                        Icons.Filled.PhoneAndroid
                                    } else {
                                        Icons.Filled.Dns
                                    },
                                    contentDescription = null,
                                    tint = SignalCyan,
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
                                            "Tap Manage Profiles or Pair Phone Bridge"
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
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onNavigateToProfiles,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("dashboard_manage_profiles_button")
                            ) {
                                Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Profiles")
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
        }
    }

    if (showCustomDomainDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDomainDialog = false },
            title = { Text("Custom Disguise Domain") },
            text = {
                Column {
                    Text(
                        text = "Enter a trusted domain (SNI & Host header) to disguise your encrypted tunnel traffic.",
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
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDomainDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TelemetryStatTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    sub: String,
    accent: androidx.compose.ui.graphics.Color,
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
