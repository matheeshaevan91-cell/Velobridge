package com.example.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.network.NetworkDiagnostics
import com.example.domain.model.ConnectionQualityTier
import com.example.domain.model.DisguiseProbeResult
import com.example.domain.model.TelemetrySnapshot
import com.example.domain.model.V2RayProfile
import com.example.domain.model.VpnConnectionState
import com.example.ui.components.DualTrafficChart
import com.example.ui.components.LatencyHistoryChart
import com.example.ui.components.VeloCard
import com.example.ui.components.VeloScreenHeader
import com.example.ui.components.VeloStatusBadge
import com.example.ui.components.formatBytes
import com.example.ui.components.formatDuration
import com.example.ui.components.formatRelativeTimestamp
import com.example.ui.components.formatSpeed
import com.example.ui.theme.LocalVeloColors
import com.example.ui.theme.MonoDataStyle
import com.example.ui.theme.MonoMetricStyle
import kotlinx.coroutines.delay

@Composable
fun AnalyticsScreen(
    telemetry: TelemetrySnapshot,
    activeProfile: V2RayProfile?,
    dnsServer: String,
    probeResult: DisguiseProbeResult?,
    isProbing: Boolean,
    onRunProbe: (String) -> Unit,
    onRefreshNetwork: () -> Unit,
    modifier: Modifier = Modifier
) {
    val velo = LocalVeloColors.current
    var uptimeDisplay by remember { mutableStateOf("00:00:00") }
    val interfaces = remember(telemetry.networkType) { NetworkDiagnostics.getLocalIpv4Addresses() }

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
                .testTag("analytics_lazy_column"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                VeloScreenHeader(
                    title = "Network Analytics",
                    subtitle = "Real-time throughput, session uptime, RTT history, and diagnostics",
                    trailing = {
                        IconButton(onClick = onRefreshNetwork) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh network interfaces")
                        }
                    }
                )
            }

            // Quality & Uptime Overview Card
            item {
                val tier = telemetry.qualityTier
                val (badgeColor, badgeContainer) = when (tier) {
                    ConnectionQualityTier.EXCELLENT, ConnectionQualityTier.GOOD ->
                        velo.success to velo.successContainer
                    ConnectionQualityTier.FAIR -> velo.warning to velo.warningContainer
                    ConnectionQualityTier.DEGRADED -> velo.danger to velo.dangerContainer
                    ConnectionQualityTier.IDLE ->
                        MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
                }
                VeloCard(modifier = Modifier.testTag("analytics_quality_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Analytics,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Connection Quality", style = MaterialTheme.typography.titleMedium)
                            }
                            VeloStatusBadge(
                                label = tier.label,
                                accentColor = badgeColor,
                                containerColor = badgeContainer
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AnalyticsMetricTile(
                                label = "Session Uptime",
                                value = uptimeDisplay,
                                modifier = Modifier.weight(1f)
                            )
                            AnalyticsMetricTile(
                                label = "Current RTT",
                                value = telemetry.latencyMs?.let { "${it} ms" } ?: "-- ms",
                                modifier = Modifier.weight(1f)
                            )
                            AnalyticsMetricTile(
                                label = "Reconnects",
                                value = telemetry.reconnectCount.toString(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Bandwidth History & Session Usage Card
            item {
                VeloCard(modifier = Modifier.testTag("analytics_bandwidth_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Live Bandwidth & Session Usage", style = MaterialTheme.typography.titleMedium)
                        DualTrafficChart(
                            samples = telemetry.bandwidthSamples,
                            isActive = telemetry.state == VpnConnectionState.CONNECTED
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AnalyticsMetricTile(
                                label = "Peak Download",
                                value = formatSpeed(telemetry.peakDownBps),
                                modifier = Modifier.weight(1f)
                            )
                            AnalyticsMetricTile(
                                label = "Peak Upload",
                                value = formatSpeed(telemetry.peakUpBps),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AnalyticsMetricTile(
                                label = "Session Down",
                                value = formatBytes(telemetry.totalDownBytes),
                                modifier = Modifier.weight(1f)
                            )
                            AnalyticsMetricTile(
                                label = "Session Up",
                                value = formatBytes(telemetry.totalUpBytes),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Latency History Card
            item {
                VeloCard(modifier = Modifier.testTag("analytics_latency_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Latency History", style = MaterialTheme.typography.titleMedium)
                        LatencyHistoryChart(samples = telemetry.latencySamples)
                    }
                }
            }

            // Network Interface Info Card
            item {
                VeloCard(modifier = Modifier.testTag("analytics_interfaces_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.NetworkCheck,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Network Interfaces", style = MaterialTheme.typography.titleMedium)
                        }
                        AnalyticsInfoLine("Active Transport", telemetry.networkType)
                        AnalyticsInfoLine("MTU", "${telemetry.mtu} bytes")
                        AnalyticsInfoLine("Tunnel DNS", dnsServer)
                        AnalyticsInfoLine(
                            "Tunnel Verified",
                            if (telemetry.tunnelVerified) "Encrypted PONG OK" else "Not verified"
                        )
                        if (interfaces.isEmpty()) {
                            Text(
                                text = "No external IPv4 interfaces detected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            interfaces.forEach { info ->
                                AnalyticsInfoLine(
                                    label = if (info.isHotspotOrWifi) "${info.interfaceName} (LAN)" else info.interfaceName,
                                    value = info.ipAddress
                                )
                            }
                        }
                    }
                }
            }

            // Latest Disguise / TLS Diagnostic Result
            item {
                val targetDomain = activeProfile?.effectiveDisguiseDomain ?: telemetry.disguiseDomain
                VeloCard(modifier = Modifier.testTag("analytics_diagnostics_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Latest SNI / TLS Diagnostic", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Target: $targetDomain",
                                    style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { onRunProbe(targetDomain) },
                                enabled = !isProbing,
                                modifier = Modifier.testTag("analytics_run_probe_button")
                            ) {
                                if (isProbing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Probe")
                            }
                        }

                        if (probeResult == null) {
                            Text(
                                text = "No reachability probe executed this session.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (probeResult.tlsHandshakeSuccess) velo.successContainer else velo.dangerContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Icon(
                                        imageVector = if (probeResult.tlsHandshakeSuccess) {
                                            Icons.Filled.CheckCircle
                                        } else {
                                            Icons.Filled.WarningAmber
                                        },
                                        contentDescription = null,
                                        tint = if (probeResult.tlsHandshakeSuccess) velo.success else velo.danger
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = if (probeResult.tlsHandshakeSuccess) {
                                                "${probeResult.domain} reachable (${probeResult.tcpRttMs} ms)"
                                            } else {
                                                "${probeResult.domain} failed"
                                            },
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = if (probeResult.tlsHandshakeSuccess) {
                                                "IP ${probeResult.resolvedIp} • ${probeResult.tlsProtocol} • ${formatRelativeTimestamp(probeResult.checkedAtMillis)}"
                                            } else {
                                                probeResult.errorMessage ?: "Connection failed"
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
        }
    }
}

@Composable
private fun AnalyticsMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MonoMetricStyle,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun AnalyticsInfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MonoDataStyle,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}
