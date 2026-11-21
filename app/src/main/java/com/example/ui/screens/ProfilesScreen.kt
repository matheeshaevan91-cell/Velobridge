package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.example.domain.model.ProfileSortOption
import com.example.domain.model.ProtocolType
import com.example.domain.model.V2RayProfile
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.DisguiseDomainPill
import com.example.ui.components.ProfileShareDialog
import com.example.ui.components.ProtocolSupportBadge
import com.example.ui.components.VeloCard
import com.example.ui.components.VeloEmptyStateCard
import com.example.ui.components.VeloScreenHeader
import com.example.ui.components.formatRelativeTimestamp
import com.example.ui.theme.LocalVeloColors
import com.example.ui.theme.MonoDataStyle
import com.example.ui.viewmodel.ProfileFilterState

@Composable
fun ProfilesScreen(
    profiles: List<V2RayProfile>,
    totalProfilesCount: Int,
    activeProfileId: String?,
    filterState: ProfileFilterState,
    isScanningLan: Boolean,
    lanServers: List<DiscoveredLanServer>,
    isTestingAll: Boolean,
    onSearchChange: (String) -> Unit,
    onProtocolFilterChange: (ProtocolType?) -> Unit,
    onToggleFavoritesOnly: () -> Unit,
    onSortChange: (ProfileSortOption) -> Unit,
    onSelectProfile: (String) -> Unit,
    onToggleFavorite: (V2RayProfile) -> Unit,
    onDuplicateProfile: (V2RayProfile) -> Unit,
    onCreateProfile: () -> Unit,
    onEditProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onPingProfile: (V2RayProfile) -> Unit,
    onTestAll: () -> Unit,
    onImportText: (String, (Boolean, String) -> Unit) -> Unit,
    onImportFileUri: (Context, Uri, (Boolean, String) -> Unit) -> Unit,
    onImportQrImageUri: (Context, Uri, (Boolean, String) -> Unit) -> Unit,
    onScanLanServers: () -> Unit,
    onAdoptLanServer: (DiscoveredLanServer) -> Unit,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val velo = LocalVeloColors.current

    var showImportDialog by remember { mutableStateOf(false) }
    var showLanDialog by remember { mutableStateOf(false) }
    var importInputText by remember { mutableStateOf("") }
    var importErrorText by remember { mutableStateOf<String?>(null) }
    var shareTargetProfile by remember { mutableStateOf<V2RayProfile?>(null) }
    var deleteTargetProfile by remember { mutableStateOf<V2RayProfile?>(null) }

    val openJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportFileUri(context, uri) { _, msg -> onNotify(msg) }
        }
    }

    val pickQrImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onImportQrImageUri(context, uri) { _, msg -> onNotify(msg) }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .testTag("profiles_lazy_column"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                VeloScreenHeader(
                    title = "Profiles",
                    subtitle = "Import multi-line links, Xray JSON, QR images, or pair a LAN Phone Server",
                    trailing = if (totalProfilesCount > 0) {
                        {
                            OutlinedButton(
                                onClick = onTestAll,
                                enabled = !isTestingAll,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("test_all_ping_button")
                            ) {
                                if (isTestingAll) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(15.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isTestingAll) "Testing…" else "Test All")
                            }
                        }
                    } else null
                )
            }

            // Primary Import & Pairing Bar
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_paste_button")
                        ) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Paste / Bulk", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                openJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_json_file_button")
                        ) {
                            Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("JSON File", maxLines = 1)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                pickQrImageLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_qr_image_button")
                        ) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("QR Image", maxLines = 1)
                        }
                        Button(
                            onClick = {
                                showLanDialog = true
                                onScanLanServers()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("find_phone_server_button")
                        ) {
                            Icon(Icons.Filled.WifiFind, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Pair LAN", maxLines = 1)
                        }
                    }
                }
            }

            if (totalProfilesCount > 0) {
                item {
                    OutlinedTextField(
                        value = filterState.searchQuery,
                        onValueChange = onSearchChange,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        placeholder = { Text("Search name, host, protocol, or disguise") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_search_input")
                    )
                }

                // Protocol & Favorite filters
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = filterState.favoritesOnly,
                            onClick = onToggleFavoritesOnly,
                            leadingIcon = {
                                Icon(
                                    imageVector = if (filterState.favoritesOnly) Icons.Filled.Star else Icons.Filled.StarOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text("Favorites") },
                            modifier = Modifier.testTag("filter_favorites_chip")
                        )
                        FilterChip(
                            selected = filterState.protocolFilter == null,
                            onClick = { onProtocolFilterChange(null) },
                            label = { Text("All ($totalProfilesCount)") },
                            modifier = Modifier.testTag("filter_protocol_all")
                        )
                        ProtocolType.entries.forEach { proto ->
                            FilterChip(
                                selected = filterState.protocolFilter == proto,
                                onClick = {
                                    onProtocolFilterChange(if (filterState.protocolFilter == proto) null else proto)
                                },
                                label = { Text(proto.label) },
                                modifier = Modifier.testTag("filter_protocol_${proto.name.lowercase()}")
                            )
                        }
                    }
                }

                // Sort options
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sort:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ProfileSortOption.entries.forEach { option ->
                            FilterChip(
                                selected = filterState.sortOption == option,
                                onClick = { onSortChange(option) },
                                label = { Text(option.label) },
                                modifier = Modifier.testTag("sort_${option.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            if (profiles.isEmpty()) {
                item {
                    VeloEmptyStateCard(
                        icon = Icons.Filled.PhoneAndroid,
                        title = if (totalProfilesCount > 0) "No Matching Configurations" else "No Profiles Yet",
                        subtitle = if (totalProfilesCount > 0) {
                            "Clear your search or filter chips to view all configurations."
                        } else {
                            "Import VLESS, VMess, Trojan, Shadowsocks, velobridge:// links, or discover a Phone Bridge server on Wi-Fi."
                        },
                        primaryLabel = if (totalProfilesCount > 0) null else "Find Nearby Phone Server",
                        onPrimaryClick = if (totalProfilesCount > 0) null else {
                            {
                                showLanDialog = true
                                onScanLanServers()
                            }
                        }
                    )
                }
            } else {
                items(profiles, key = { it.id }) { profile ->
                    val isActive = profile.id == activeProfileId
                    ProfileCard(
                        profile = profile,
                        isActive = isActive,
                        onSelect = { onSelectProfile(profile.id) },
                        onToggleFavorite = { onToggleFavorite(profile) },
                        onPing = { onPingProfile(profile) },
                        onShare = { shareTargetProfile = profile },
                        onDuplicate = { onDuplicateProfile(profile) },
                        onEdit = { onEditProfile(profile.id) },
                        onDelete = { deleteTargetProfile = profile }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = onCreateProfile,
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
            title = { Text("Import Configuration") },
            text = {
                Column {
                    Text(
                        text = "Paste one or multiple vless://, vmess://, trojan://, ss://, velobridge:// links, or Xray JSON.",
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
                        label = { Text("URIs or Xray JSON") },
                        minLines = 4,
                        maxLines = 8,
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
                            color = velo.danger
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
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showLanDialog) {
        AlertDialog(
            onDismissRequest = { showLanDialog = false },
            title = { Text("Nearby Phone Bridges") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Ensure the Server Phone has Bridge Server turned ON on the same Wi-Fi or Hotspot.",
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
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
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
                                Text("No phone servers found.", style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "You can also scan a pairing QR image or copy the velobridge:// link from the Server Phone.",
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
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.PhoneAndroid,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
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
                                            },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                        ) { Text("Pair") }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onScanLanServers, enabled = !isScanningLan) { Text("Rescan") }
            },
            dismissButton = {
                TextButton(onClick = { showLanDialog = false }) { Text("Close") }
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

    deleteTargetProfile?.let { target ->
        ConfirmActionDialog(
            title = "Delete Configuration?",
            message = "Remove '${target.name}' (${target.address}:${target.port}) from your saved profiles?",
            confirmLabel = "Delete",
            onConfirm = { onDeleteProfile(target.id) },
            onDismiss = { deleteTargetProfile = null }
        )
    }
}

@Composable
private fun ProfileCard(
    profile: V2RayProfile,
    isActive: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPing: () -> Unit,
    onShare: () -> Unit,
    onDuplicate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val velo = LocalVeloColors.current
    VeloCard(
        onClick = onSelect,
        containerColor = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        borderColor = if (isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
        } else {
            MaterialTheme.colorScheme.outline
        },
        modifier = Modifier.testTag("profile_card_${profile.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.testTag("favorite_profile_${profile.id}")
                    ) {
                        Icon(
                            imageVector = if (profile.isFavorite) Icons.Filled.Star else Icons.Filled.StarOutline,
                            contentDescription = if (profile.isFavorite) "Remove favorite" else "Mark favorite",
                            tint = if (profile.isFavorite) velo.warning else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isActive) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Active profile",
                            tint = velo.success,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
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
                            text = "${profile.address}:${profile.port} • ${profile.encryption.label}",
                            style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Last connected: ${formatRelativeTimestamp(profile.lastConnectedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text(
                            text = profile.protocol.badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    val latText = profile.latencyMs?.let { "$it ms" } ?: "Untested"
                    val latColor = when {
                        profile.latencyMs == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        profile.latencyMs < 120 -> velo.success
                        profile.latencyMs < 280 -> velo.warning
                        else -> velo.danger
                    }
                    Text(
                        text = latText,
                        style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                        color = latColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DisguiseDomainPill(
                    presetId = profile.disguisePresetId,
                    domain = profile.effectiveDisguiseDomain
                )
                ProtocolSupportBadge(protocol = profile.protocol)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onPing) {
                    Icon(Icons.Filled.NetworkPing, contentDescription = "Ping server", modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.QrCode2, contentDescription = "Share QR or JSON", modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = onDuplicate) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate profile", modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit profile", modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "Delete profile",
                        tint = velo.danger,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}
