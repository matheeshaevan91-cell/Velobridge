package com.example.ui.screens

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.AppPreferences
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.DisguiseProbeResult
import com.example.domain.model.LogEntry
import com.example.domain.model.LogLevel
import com.example.domain.model.RoutingMode
import com.example.domain.model.V2RayProfile
import com.example.ui.components.FirewallInspectionCard
import com.example.ui.components.disguiseIcon
import com.example.ui.components.formatLogTime
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.DangerCoral
import com.example.ui.theme.MonoDataStyle
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.SignalCyan

@Composable
fun DisguiseAndSettingsScreen(
    activeProfile: V2RayProfile?,
    preferences: AppPreferences,
    isProbing: Boolean,
    probeResult: DisguiseProbeResult?,
    logs: List<LogEntry>,
    onApplyDisguise: (presetId: String, customDomain: String) -> Unit,
    onRunProbe: (String) -> Unit,
    onSetRoutingMode: (RoutingMode) -> Unit,
    onSetDns: (String) -> Unit,
    onSetBypassLan: (Boolean) -> Unit,
    onSetDarkTheme: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPresetId by remember(activeProfile) {
        mutableStateOf(activeProfile?.disguisePresetId ?: "youtube")
    }
    var customDomainInput by remember(activeProfile) {
        mutableStateOf(activeProfile?.customDisguiseDomain?.ifBlank { "cdn.example.com" } ?: "www.youtube.com")
    }
    var probeDomainInput by remember(activeProfile) {
        mutableStateOf(activeProfile?.effectiveDisguiseDomain ?: "www.youtube.com")
    }
    var dnsInput by remember(preferences.dnsServer) { mutableStateOf(preferences.dnsServer) }

    val activePreset = DisguiseCatalog.findById(selectedPresetId)
    val previewDomain = if (selectedPresetId == "custom") {
        customDomainInput.ifBlank { "custom.example.com" }
    } else {
        activePreset.domain
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .testTag("disguise_lazy_column"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "Disguise & Routing Lab",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "Camouflage encrypted traffic as trusted websites and verify reachability",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Domain Camouflage Selector
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
                        Text("Traffic Camouflage Profile", style = MaterialTheme.typography.titleSmall)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DisguiseCatalog.presets.forEach { preset ->
                                FilterChip(
                                    selected = selectedPresetId == preset.id,
                                    onClick = {
                                        selectedPresetId = preset.id
                                        if (preset.id != "custom") {
                                            probeDomainInput = preset.domain
                                            onApplyDisguise(preset.id, "")
                                        } else {
                                            probeDomainInput = customDomainInput
                                        }
                                    },
                                    label = { Text(preset.title) },
                                    leadingIcon = {
                                        Icon(disguiseIcon(preset.id), contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                )
                            }
                        }

                        Text(
                            text = activePreset.beginnerSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (selectedPresetId == "custom") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = customDomainInput,
                                    onValueChange = {
                                        customDomainInput = it
                                        probeDomainInput = it
                                    },
                                    label = { Text("Custom Host / SNI") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        if (customDomainInput.isNotBlank()) {
                                            onApplyDisguise("custom", customDomainInput.trim())
                                        }
                                    },
                                    modifier = Modifier.testTag("apply_custom_disguise_button")
                                ) {
                                    Text("Apply")
                                }
                            }
                        }
                    }
                }
            }

            // Live Firewall Wire Preview
            item {
                FirewallInspectionCard(
                    disguiseDomain = previewDomain,
                    wsPath = activeProfile?.wsPath ?: activePreset.wsPath,
                    cipherLabel = activeProfile?.encryption?.label ?: "AES-256-GCM",
                    destinationSummary = activeProfile?.let { "${it.address}:${it.port}" } ?: "Phone Bridge"
                )
            }

            // Real Disguise TLS/SNI Probe
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("probe_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Live SNI & Reachability Probe", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Tests whether your current network allows TLS connections to the disguise domain.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = probeDomainInput,
                                onValueChange = { probeDomainInput = it },
                                label = { Text("Domain to probe") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("probe_domain_input")
                            )
                            Button(
                                onClick = { onRunProbe(probeDomainInput) },
                                enabled = !isProbing && probeDomainInput.isNotBlank(),
                                modifier = Modifier.testTag("run_probe_button")
                            ) {
                                if (isProbing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Probe")
                            }
                        }

                        probeResult?.let { res ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (res.tlsHandshakeSuccess) {
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Icon(
                                        imageVector = if (res.tlsHandshakeSuccess) {
                                            Icons.Filled.CheckCircle
                                        } else {
                                            Icons.Filled.WarningAmber
                                        },
                                        contentDescription = null,
                                        tint = if (res.tlsHandshakeSuccess) ShieldEmerald else DangerCoral
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = if (res.tlsHandshakeSuccess) {
                                                "${res.domain} reachable (${res.tcpRttMs} ms)"
                                            } else {
                                                "Probe failed for ${res.domain}"
                                            },
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = if (res.tlsHandshakeSuccess) {
                                                "IP: ${res.resolvedIp} • ${res.tlsProtocol}"
                                            } else {
                                                res.errorMessage ?: "Connection blocked or timed out"
                                            },
                                            style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Routing & App Settings
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
                        Text("Routing & Network Settings", style = MaterialTheme.typography.titleSmall)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RoutingMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = preferences.routingMode == mode,
                                    onClick = { onSetRoutingMode(mode) },
                                    label = { Text(mode.label) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Bypass Local LAN IPs", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "Keep 192.168.x.x & 10.x.x.x devices directly reachable",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = preferences.bypassLan,
                                onCheckedChange = onSetBypassLan
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = dnsInput,
                                onValueChange = { dnsInput = it },
                                label = { Text("Tunnel DNS Server") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(onClick = { if (dnsInput.isNotBlank()) onSetDns(dnsInput) }) {
                                Text("Save")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Obsidian Dark Theme", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "High-contrast telemetry palette",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = preferences.darkTheme,
                                onCheckedChange = onSetDarkTheme
                            )
                        }
                    }
                }
            }

            // Diagnostic Logs
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
                            Text("Diagnostic Event Log", style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = onClearLogs) {
                                Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear logs")
                            }
                        }
                        if (logs.isEmpty()) {
                            Text(
                                text = "No events recorded yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            logs.take(25).forEach { log ->
                                val color = when (log.level) {
                                    LogLevel.SUCCESS -> ShieldEmerald
                                    LogLevel.WARNING -> AlertAmber
                                    LogLevel.ERROR -> DangerCoral
                                    LogLevel.INFO -> SignalCyan
                                }
                                Text(
                                    text = "[${formatLogTime(log.timestamp)}] [${log.source}] ${log.message}",
                                    style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                    color = color,
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
