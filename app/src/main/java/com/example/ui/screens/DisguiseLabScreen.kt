package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.DisguiseProbeResult
import com.example.domain.model.V2RayProfile
import com.example.ui.components.FirewallInspectionCard
import com.example.ui.components.VeloCard
import com.example.ui.components.disguiseIcon
import com.example.ui.theme.LocalVeloColors
import com.example.ui.theme.MonoDataStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisguiseLabScreen(
    activeProfile: V2RayProfile?,
    isProbing: Boolean,
    probeResult: DisguiseProbeResult?,
    onApplyDisguise: (presetId: String, customDomain: String) -> Unit,
    onRunProbe: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val velo = LocalVeloColors.current
    var selectedPresetId by remember(activeProfile) {
        mutableStateOf(activeProfile?.disguisePresetId ?: "youtube")
    }
    var customDomainInput by remember(activeProfile) {
        mutableStateOf(activeProfile?.customDisguiseDomain?.ifBlank { "cdn.example.com" } ?: "www.youtube.com")
    }
    var probeDomainInput by remember(activeProfile) {
        mutableStateOf(activeProfile?.effectiveDisguiseDomain ?: "www.youtube.com")
    }

    val activePreset = DisguiseCatalog.findById(selectedPresetId)
    val previewDomain = if (selectedPresetId == "custom") {
        customDomainInput.ifBlank { "custom.example.com" }
    } else {
        activePreset.domain
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Disguise & SNI Lab", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("disguise_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
                    .testTag("disguise_lazy_column"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    VeloCard {
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
                                    ) { Text("Apply") }
                                }
                            }
                        }
                    }
                }

                item {
                    FirewallInspectionCard(
                        disguiseDomain = previewDomain,
                        wsPath = activeProfile?.wsPath ?: activePreset.wsPath,
                        cipherLabel = activeProfile?.encryption?.label ?: "AES-256-GCM",
                        destinationSummary = activeProfile?.let { "${it.address}:${it.port}" } ?: "Phone Bridge"
                    )
                }

                item {
                    VeloCard(modifier = Modifier.testTag("probe_card")) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Live SNI & Reachability Probe", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Tests whether your current network completes a TCP + TLS SNI handshake to the target domain.",
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
                                    color = if (res.tlsHandshakeSuccess) velo.successContainer else velo.dangerContainer,
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
                                            tint = if (res.tlsHandshakeSuccess) velo.success else velo.danger
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
            }
        }
    }
}
