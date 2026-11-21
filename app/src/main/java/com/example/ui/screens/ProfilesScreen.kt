package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.DiscoveredLanServer
import com.example.domain.model.ProtocolType
import com.example.domain.model.V2RayProfile
import com.example.ui.components.DisguiseDomainPill
import com.example.ui.components.ProfileShareDialog
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.DangerCoral
import com.example.ui.theme.MonoDataStyle
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.SignalCyan

@Composable
fun ProfilesScreen(
    profiles: List<V2RayProfile>,
    activeProfileId: String?,
    isScanningLan: Boolean,
    lanServers: List<DiscoveredLanServer>,
    isTestingAll: Boolean,
    onSelectProfile: (String) -> Unit,
    onCreateProfile: () -> Unit,
    onEditProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onPingProfile: (V2RayProfile) -> Unit,
    onTestAll: () -> Unit,
    onImportText: (String, (Boolean, String) -> Unit) -> Unit,
    onImportFileUri: (Context, Uri, (Boolean, String) -> Unit) -> Unit,
    onScanLanServers: () -> Unit,
    onAdoptLanServer: (DiscoveredLanServer) -> Unit,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedProtocolFilter by remember { mutableStateOf<ProtocolType?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showLanDialog by remember { mutableStateOf(false) }
    var importInputText by remember { mutableStateOf("") }
    var importErrorText by remember { mutableStateOf<String?>(null) }
    var shareTargetProfile by remember { mutableStateOf<V2RayProfile?>(null) }

    val openJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportFileUri(context, uri) { _, msg -> onNotify(msg) }
        }
    }

    val filteredProfiles = remember(profiles, searchQuery, selectedProtocolFilter) {
        profiles.filter { profile ->
            val matchesQuery = searchQuery.isBlank() ||
                profile.name.contains(searchQuery, ignoreCase = true) ||
                profile.address.contains(searchQuery, ignoreCase = true) ||
                profile.effectiveDisguiseDomain.contains(searchQuery, ignoreCase = true)
            val matchesProto = selectedProtocolFilter == null || profile.protocol == selectedProtocolFilter
            matchesQuery && matchesProto
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Configurations",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            text = "Import V2Ray links, JSON files, or pair a nearby phone",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (profiles.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onTestAll,
                            enabled = !isTestingAll,
                            modifier = Modifier.testTag("test_all_ping_button")
                        ) {
                            if (isTestingAll) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isTestingAll) "Testing…" else "Test All")
                        }
                    }
                }
            }

            // Import Action Bar
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            importInputText = ""
                            importErrorText = null
                            showImportDialog = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("import_paste_button")
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Paste URI", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            openJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("import_json_file_button")
                    ) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("JSON File", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = {
                            showLanDialog = true
                            onScanLanServers()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("find_phone_server_button")
                    ) {
                        Icon(Icons.Filled.WifiFind, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pair LAN", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            if (profiles.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        placeholder = { Text("Search name, host, or disguise domain") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_search_input")
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedProtocolFilter == null,
                            onClick = { selectedProtocolFilter = null },
                            label = { Text("All (${profiles.size})") }
                        )
                        ProtocolType.entries.forEach { proto ->
                            FilterChip(
                                selected = selectedProtocolFilter == proto,
                                onClick = {
                                    selectedProtocolFilter = if (selectedProtocolFilter == proto) null else proto
                                },
                                label = { Text(proto.label) }
                            )
                        }
                    }
                }
            }

            if (filteredProfiles.isEmpty()) {
                item {
                    EmptyProfilesCard(
                        hasProfiles = profiles.isNotEmpty(),
                        onPasteClick = {
                            importInputText = ""
                            importErrorText = null
                            showImportDialog = true
                        },
                        onPairLanClick = {
                            showLanDialog = true
                            onScanLanServers()
                        },
                        onCreateClick = onCreateProfile
                    )
                }
            } else {
                items(filteredProfiles, key = { it.id }) { profile ->
                    val isActive = profile.id == activeProfileId
                    ProfileCard(
                        profile = profile,
                        isActive = isActive,
                        onSelect = { onSelectProfile(profile.id) },
                        onPing = { onPingProfile(profile) },
                        onShare = { shareTargetProfile = profile },
                        onEdit = { onEditProfile(profile.id) },
                        onDelete = { onDeleteProfile(profile.id) }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = onCreateProfile,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("add_custom_profile_fab")
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Create custom profile")
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import V2Ray Configuration") },
            text = {
                Column {
                    Text(
                        text = "Paste vless://, vmess://, trojan://, ss://, velobridge:// links or full Xray JSON.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = importInputText,
                        onValueChange = {
                            importInputText = it
                            importErrorText = null
                        },
                        label = { Text("Config URI or JSON") },
                        minLines = 4,
                        maxLines = 7,
                        textStyle = MonoDataStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_config_textfield")
                    )
                    if (importErrorText != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = importErrorText.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = DangerCoral
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                            if (clip.isNotBlank()) {
                                importInputText = clip
                                importErrorText = null
                            } else {
                                importErrorText = "Clipboard is empty."
                            }
                        }
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Paste from Clipboard")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onImportText(importInputText) { ok, msg ->
                            if (ok) {
                                showImportDialog = false
                                onNotify(msg)
                            } else {
                                importErrorText = msg
                            }
                        }
                    },
                    modifier = Modifier.testTag("confirm_import_button")
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showLanDialog) {
        AlertDialog(
            onDismissRequest = { showLanDialog = false },
            title = { Text("Nearby Phone Bridge Servers") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Make sure the Old Phone has Bridge Server turned ON and both phones share the same Wi-Fi or Hotspot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    if (isScanningLan) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Scanning local network…")
                        }
                    } else if (lanServers.isEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "No phone servers responded yet.",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "You can also scan/copy the velobridge:// pairing URI shown on the Server Phone's Bridge tab and paste it here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            lanServers.forEach { server ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, SignalCyan.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.PhoneAndroid,
                                            contentDescription = null,
                                            tint = SignalCyan
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(server.deviceName, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = "${server.ipAddress}:${server.port} • ${server.disguiseDomain} (${server.rttMs}ms)",
                                                style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                onAdoptLanServer(server)
                                                showLanDialog = false
                                            }
                                        ) {
                                            Text("Pair")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onScanLanServers, enabled = !isScanningLan) {
                    Text("Rescan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLanDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    shareTargetProfile?.let { profile ->
        ProfileShareDialog(
            profile = profile,
            onDismiss = { shareTargetProfile = null },
            onCopied = { msg -> onNotify(msg) }
        )
    }
}

@Composable
private fun EmptyProfilesCard(
    hasProfiles: Boolean,
    onPasteClick: () -> Unit,
    onPairLanClick: () -> Unit,
    onCreateClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = SignalCyan,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (hasProfiles) "No Matching Profiles" else "Add Your First Connection",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (hasProfiles) {
                    "Try clearing your search or protocol filter."
                } else {
                    "Import a custom V2Ray config (VLESS, VMess, Trojan, SS, JSON) or pair directly with an old phone running VeloBridge Server."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!hasProfiles) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onPairLanClick, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.WifiFind, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Find Nearby Server Phone")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onPasteClick, modifier = Modifier.weight(1f)) {
                        Text("Paste URI / JSON")
                    }
                    OutlinedButton(onClick = onCreateClick, modifier = Modifier.weight(1f)) {
                        Text("Manual Setup")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: V2RayProfile,
    isActive: Boolean,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) SignalCyan else MaterialTheme.colorScheme.outline
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_card_${profile.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isActive) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Selected profile",
                            tint = ShieldEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${profile.address}:${profile.port}",
                            style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Protocol + Latency badge
                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = profile.protocol.badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = SignalCyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    val latText = profile.latencyMs?.let { "${it} ms" } ?: "Untested"
                    val latColor = when {
                        profile.latencyMs == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        profile.latencyMs < 120 -> ShieldEmerald
                        profile.latencyMs < 280 -> AlertAmber
                        else -> DangerCoral
                    }
                    Text(
                        text = latText,
                        style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                        color = latColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DisguiseDomainPill(
                    presetId = profile.disguisePresetId,
                    domain = profile.effectiveDisguiseDomain
                )
                Row {
                    IconButton(onClick = onPing) {
                        Icon(Icons.Filled.NetworkPing, contentDescription = "Ping server", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Filled.QrCode2, contentDescription = "Share QR or JSON", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit profile", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = "Delete profile",
                            tint = DangerCoral,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
