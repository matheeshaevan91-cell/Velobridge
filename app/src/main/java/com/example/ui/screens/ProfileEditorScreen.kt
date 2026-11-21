package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.parser.ParseImportResult
import com.example.core.parser.V2RayConfigParser
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.ProtocolType
import com.example.domain.model.TlsFingerprint
import com.example.domain.model.TransportType
import com.example.domain.model.V2RayProfile
import com.example.ui.components.disguiseIcon
import com.example.ui.theme.DangerCoral
import com.example.ui.theme.MonoDataStyle
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    existingProfile: V2RayProfile?,
    onSave: (V2RayProfile) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    var editorTab by remember { mutableIntStateOf(0) }

    var name by remember(existingProfile) {
        mutableStateOf(existingProfile?.name ?: "My Custom Server")
    }
    var protocol by remember(existingProfile) {
        mutableStateOf(existingProfile?.protocol ?: ProtocolType.VELO_BRIDGE)
    }
    var address by remember(existingProfile) {
        mutableStateOf(existingProfile?.address ?: "")
    }
    var portText by remember(existingProfile) {
        mutableStateOf((existingProfile?.port ?: 10808).toString())
    }
    var uuidOrPassword by remember(existingProfile) {
        mutableStateOf(existingProfile?.uuidOrPassword ?: UUID.randomUUID().toString())
    }
    var encryption by remember(existingProfile) {
        mutableStateOf(existingProfile?.encryption ?: EncryptionCipher.AES_256_GCM)
    }
    var transport by remember(existingProfile) {
        mutableStateOf(existingProfile?.transport ?: TransportType.WEBSOCKET)
    }
    var disguiseId by remember(existingProfile) {
        mutableStateOf(existingProfile?.disguisePresetId ?: "youtube")
    }
    var customDomain by remember(existingProfile) {
        mutableStateOf(existingProfile?.customDisguiseDomain ?: "")
    }
    var sni by remember(existingProfile) {
        mutableStateOf(existingProfile?.sni ?: "www.youtube.com")
    }
    var hostHeader by remember(existingProfile) {
        mutableStateOf(existingProfile?.hostHeader ?: "www.youtube.com")
    }
    var wsPath by remember(existingProfile) {
        mutableStateOf(existingProfile?.wsPath ?: "/youtubei/v1/player")
    }
    var tlsEnabled by remember(existingProfile) {
        mutableStateOf(existingProfile?.tlsEnabled ?: true)
    }
    var allowInsecure by remember(existingProfile) {
        mutableStateOf(existingProfile?.allowInsecure ?: false)
    }
    var tlsFingerprint by remember(existingProfile) {
        mutableStateOf(existingProfile?.tlsFingerprint ?: TlsFingerprint.CHROME)
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    val draftProfile = remember(
        existingProfile, name, protocol, address, portText, uuidOrPassword,
        encryption, transport, disguiseId, customDomain, sni, hostHeader, wsPath,
        tlsEnabled, allowInsecure, tlsFingerprint
    ) {
        V2RayProfile(
            id = existingProfile?.id ?: UUID.randomUUID().toString(),
            name = name.ifBlank { "Untitled" },
            protocol = protocol,
            address = address.trim(),
            port = portText.toIntOrNull() ?: 443,
            uuidOrPassword = uuidOrPassword.trim(),
            encryption = encryption,
            transport = transport,
            disguisePresetId = disguiseId,
            customDisguiseDomain = customDomain.trim(),
            sni = sni.trim(),
            hostHeader = hostHeader.trim(),
            wsPath = wsPath.trim(),
            tlsEnabled = tlsEnabled,
            allowInsecure = allowInsecure,
            tlsFingerprint = tlsFingerprint,
            isPhoneBridge = protocol == ProtocolType.VELO_BRIDGE
        )
    }

    var rawJsonText by remember(draftProfile) {
        mutableStateOf(V2RayConfigParser.toXrayClientJson(draftProfile))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingProfile == null) "New Configuration" else "Edit Configuration") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("editor_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (editorTab == 1) {
                                when (val parsed = V2RayConfigParser.parseInput(rawJsonText)) {
                                    is ParseImportResult.Error -> validationError = parsed.reason
                                    is ParseImportResult.Success -> {
                                        val imported = parsed.profiles.first().copy(
                                            id = existingProfile?.id ?: UUID.randomUUID().toString()
                                        )
                                        onSave(imported)
                                        onBack()
                                    }
                                }
                            } else {
                                val parsedPort = portText.toIntOrNull()
                                if (address.isBlank()) {
                                    validationError = "Server IP or domain address is required."
                                } else if (parsedPort == null || parsedPort !in 1..65535) {
                                    validationError = "Port must be between 1 and 65535."
                                } else if (uuidOrPassword.isBlank()) {
                                    validationError = "UUID or password is required."
                                } else {
                                    onSave(draftProfile)
                                    onBack()
                                }
                            }
                        },
                        modifier = Modifier.testTag("editor_save_button")
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "Save configuration")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
            ) {
                TabRow(selectedTabIndex = editorTab) {
                    Tab(
                        selected = editorTab == 0,
                        onClick = {
                            editorTab = 0
                            validationError = null
                        },
                        text = { Text("Guided Builder") }
                    )
                    Tab(
                        selected = editorTab == 1,
                        onClick = {
                            rawJsonText = V2RayConfigParser.toXrayClientJson(draftProfile)
                            editorTab = 1
                            validationError = null
                        },
                        text = { Text("Xray JSON") }
                    )
                }

                if (validationError != null) {
                    Text(
                        text = validationError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = DangerCoral,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (editorTab == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Profile Name") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_name_input")
                        )

                        Text("Protocol", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ProtocolType.entries.forEach { p ->
                                FilterChip(
                                    selected = protocol == p,
                                    onClick = { protocol = p },
                                    label = { Text(p.label) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = address,
                                onValueChange = {
                                    address = it
                                    validationError = null
                                },
                                label = { Text("Server IP / Host") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(0.68f)
                                    .testTag("editor_address_input")
                            )
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                                label = { Text("Port") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier
                                    .weight(0.32f)
                                    .testTag("editor_port_input")
                            )
                        }

                        OutlinedTextField(
                            value = uuidOrPassword,
                            onValueChange = { uuidOrPassword = it },
                            label = {
                                Text(
                                    if (protocol == ProtocolType.TROJAN || protocol == ProtocolType.SHADOWSOCKS) {
                                        "Password"
                                    } else {
                                        "UUID / Bridge Secret"
                                    }
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { uuidOrPassword = UUID.randomUUID().toString() }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Generate UUID")
                                }
                            },
                            singleLine = true,
                            textStyle = MonoDataStyle,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("Encryption Cipher", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            EncryptionCipher.entries.forEach { c ->
                                FilterChip(
                                    selected = encryption == c,
                                    onClick = { encryption = c },
                                    label = { Text(c.label) }
                                )
                            }
                        }

                        Text("Domain Disguise Camouflage", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DisguiseCatalog.presets.forEach { preset ->
                                FilterChip(
                                    selected = disguiseId == preset.id,
                                    onClick = {
                                        disguiseId = preset.id
                                        if (preset.id != "custom") {
                                            sni = preset.sni
                                            hostHeader = preset.hostHeader
                                            wsPath = preset.wsPath
                                            transport = preset.transport
                                        }
                                    },
                                    label = { Text(preset.title) },
                                    leadingIcon = {
                                        Icon(disguiseIcon(preset.id), contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                )
                            }
                        }

                        if (disguiseId == "custom") {
                            OutlinedTextField(
                                value = customDomain,
                                onValueChange = {
                                    customDomain = it
                                    sni = it
                                    hostHeader = it
                                },
                                label = { Text("Custom Disguise Domain") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = sni,
                                onValueChange = { sni = it },
                                label = { Text("TLS SNI") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = hostHeader,
                                onValueChange = { hostHeader = it },
                                label = { Text("Host Header") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        OutlinedTextField(
                            value = wsPath,
                            onValueChange = { wsPath = it },
                            label = { Text("WebSocket / gRPC Path") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("Transport", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TransportType.entries.forEach { t ->
                                FilterChip(
                                    selected = transport == t,
                                    onClick = { transport = t },
                                    label = { Text(t.label) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Enable TLS Transport Security", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = tlsEnabled, onCheckedChange = { tlsEnabled = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Allow Self-Signed / Insecure Cert", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = allowInsecure, onCheckedChange = { allowInsecure = it })
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val parsedPort = portText.toIntOrNull()
                                if (address.isBlank()) {
                                    validationError = "Server IP or domain address is required."
                                } else if (parsedPort == null || parsedPort !in 1..65535) {
                                    validationError = "Port must be between 1 and 65535."
                                } else if (uuidOrPassword.isBlank()) {
                                    validationError = "UUID or password is required."
                                } else {
                                    onSave(draftProfile)
                                    onBack()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Configuration")
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = rawJsonText,
                            onValueChange = { rawJsonText = it },
                            label = { Text("V2Ray / Xray Client JSON") },
                            textStyle = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag("editor_raw_json_input")
                        )
                        Button(
                            onClick = {
                                when (val parsed = V2RayConfigParser.parseInput(rawJsonText)) {
                                    is ParseImportResult.Error -> validationError = parsed.reason
                                    is ParseImportResult.Success -> {
                                        val imported = parsed.profiles.first().copy(
                                            id = existingProfile?.id ?: UUID.randomUUID().toString()
                                        )
                                        onSave(imported)
                                        onBack()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text("Apply & Save JSON")
                        }
                    }
                }
            }
        }
    }
}
