package com.example.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.domain.model.AppearanceMode
import com.example.domain.model.LogEntry
import com.example.domain.model.LogLevel
import com.example.domain.model.PerformanceMode
import com.example.domain.model.RoutingMode
import com.example.domain.model.SecurityDiagnosticsReport
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.VeloCard
import com.example.ui.components.VeloScreenHeader
import com.example.ui.components.VeloSectionHeader
import com.example.ui.components.VeloStatusBadge
import com.example.ui.components.formatLogTime
import com.example.ui.theme.LocalVeloColors
import com.example.ui.theme.MonoDataStyle

@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    highSpeedMode: Boolean,
    securityReport: SecurityDiagnosticsReport,
    logs: List<LogEntry>,
    onSetAppearanceMode: (AppearanceMode) -> Unit,
    onSetHapticFeedback: (Boolean) -> Unit,
    onSetNotificationsEnabled: (Boolean) -> Unit,
    onSetRoutingMode: (RoutingMode) -> Unit,
    onSetDns: (String) -> Unit,
    onSetBypassLan: (Boolean) -> Unit,
    onSetAutoReconnect: (Boolean) -> Unit,
    onSetHighSpeedMode: (Boolean) -> Unit,
    onSetSensitiveProtection: (Boolean) -> Unit,
    onOpenDisguiseLab: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val velo = LocalVeloColors.current
    var dnsInput by remember(preferences.dnsServer) { mutableStateOf(preferences.dnsServer) }
    var showClearLogsConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .testTag("settings_lazy_column"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                VeloScreenHeader(
                    title = "Settings",
                    subtitle = "Appearance, network routing, security hardening, and diagnostics"
                )
            }

            // 1. GENERAL
            item { VeloSectionHeader(title = "General") }
            item {
                VeloCard(modifier = Modifier.testTag("settings_general_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Appearance", style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppearanceMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = preferences.appearanceMode == mode,
                                    onClick = { onSetAppearanceMode(mode) },
                                    label = { Text(mode.label) },
                                    modifier = Modifier.testTag("appearance_chip_${mode.name.lowercase()}")
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        SettingToggleRow(
                            title = "Haptic Feedback",
                            subtitle = "Tactile confirmation on connect, disconnect, and toggles",
                            checked = preferences.hapticFeedback,
                            onCheckedChange = onSetHapticFeedback,
                            testTag = "haptic_feedback_switch"
                        )

                        SettingToggleRow(
                            title = "Service Notifications",
                            subtitle = "Persistent status bar notification while VPN or Server runs",
                            checked = preferences.notificationsEnabled,
                            onCheckedChange = onSetNotificationsEnabled,
                            testTag = "notifications_enabled_switch"
                        )
                    }
                }
            }

            // 2. NETWORK
            item { VeloSectionHeader(title = "Network") }
            item {
                VeloCard(modifier = Modifier.testTag("settings_network_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenDisguiseLab,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_open_disguise_lab")
                        ) {
                            Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Traffic Disguise & SNI Lab", modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }

                        Text("Routing Mode", style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
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

                        SettingToggleRow(
                            title = "Bypass Local LAN IPs",
                            subtitle = "Keep 192.168.x.x & 10.x.x.x devices directly reachable",
                            checked = preferences.bypassLan,
                            onCheckedChange = onSetBypassLan
                        )

                        SettingToggleRow(
                            title = "Auto-Reconnect on Network Switch",
                            subtitle = "Automatically re-verify bridge tunnel when switching Wi-Fi/Cellular",
                            checked = preferences.autoReconnect,
                            onCheckedChange = onSetAutoReconnect,
                            testTag = "auto_reconnect_switch"
                        )

                        SettingToggleRow(
                            title = "High-Speed Data Mode",
                            subtitle = if (highSpeedMode) {
                                "Active: ${PerformanceMode.HIGH_SPEED.summary}"
                            } else {
                                "Off: ${PerformanceMode.BALANCED.summary}"
                            },
                            checked = highSpeedMode,
                            onCheckedChange = onSetHighSpeedMode,
                            testTag = "settings_high_speed_switch"
                        )

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
                    }
                }
            }

            // 3. SECURITY
            item { VeloSectionHeader(title = "Security") }
            item {
                VeloCard(modifier = Modifier.testTag("settings_security_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Security, contentDescription = null, tint = velo.success, modifier = Modifier.size(18.dp))
                            Text("Zero-Trust Bridge Protection", style = MaterialTheme.typography.titleSmall)
                        }

                        SettingToggleRow(
                            title = "Encrypt Stored Secrets",
                            subtitle = "Protect saved UUIDs, passwords, and bridge secret via Android Keystore",
                            checked = preferences.sensitiveConfigProtection,
                            onCheckedChange = onSetSensitiveProtection,
                            testTag = "sensitive_protection_switch"
                        )

                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SecurityStatusRow(
                                    label = "Vault Storage",
                                    status = if (securityReport.keystoreBacked) "Android Keystore AES-256-GCM" else "App Vault AES-256-GCM",
                                    ok = securityReport.sensitiveProtectionEnabled
                                )
                                SecurityStatusRow(
                                    label = "Handshake Replay Guard",
                                    status = if (securityReport.replayProtectionActive) "Active (Nonce + Window)" else "Disabled",
                                    ok = securityReport.replayProtectionActive
                                )
                                SecurityStatusRow(
                                    label = "Open Proxy Defense",
                                    status = if (securityReport.unauthenticatedProxyBlocked) "Blocked (403 / Auth Required)" else "Warn",
                                    ok = securityReport.unauthenticatedProxyBlocked
                                )
                                if (securityReport.allowInsecureProfilesCount > 0) {
                                    SecurityStatusRow(
                                        label = "Allow-Insecure TLS Profiles",
                                        status = "${securityReport.allowInsecureProfilesCount} profile(s) allow self-signed certs",
                                        ok = false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. ABOUT & DIAGNOSTIC LOGS
            item { VeloSectionHeader(title = "About & Diagnostics") }
            item {
                VeloCard(modifier = Modifier.testTag("settings_about_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("VeloBridge", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "Version 2.0 • HKDF-SHA256 / AEAD Phone Bridge",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            VeloStatusBadge(
                                label = "v2.0",
                                accentColor = MaterialTheme.colorScheme.primary,
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        }

                        OutlinedButton(
                            onClick = onReplayOnboarding,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_replay_onboarding")
                        ) {
                            Text("View Quick Setup Guide")
                        }
                    }
                }
            }

            // Diagnostic Event Log Card
            item {
                VeloCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Diagnostic Event Log", style = MaterialTheme.typography.titleSmall)
                            IconButton(
                                onClick = { showClearLogsConfirm = true },
                                enabled = logs.isNotEmpty()
                            ) {
                                Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear diagnostic logs")
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
                                    LogLevel.SUCCESS -> velo.success
                                    LogLevel.WARNING -> velo.warning
                                    LogLevel.ERROR -> velo.danger
                                    LogLevel.INFO -> velo.info
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

    if (showClearLogsConfirm) {
        ConfirmActionDialog(
            title = "Clear Diagnostic Logs?",
            message = "This removes recent connection and security audit entries from memory.",
            confirmLabel = "Clear Logs",
            onConfirm = onClearLogs,
            onDismiss = { showClearLogsConfirm = false }
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        )
    }
}

@Composable
private fun SecurityStatusRow(
    label: String,
    status: String,
    ok: Boolean
) {
    val velo = LocalVeloColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = if (ok) velo.success else velo.warning,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = status,
                style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
