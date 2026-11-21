package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.parser.QrCodeGenerator
import com.example.core.parser.V2RayConfigParser
import com.example.domain.model.ProtocolType
import com.example.domain.model.V2RayProfile
import com.example.domain.model.VpnConnectionState
import com.example.ui.theme.LocalVeloColors
import com.example.ui.theme.MonoDataStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatSpeed(bps: Long): String = "${formatBytes(bps)}/s"

fun formatDuration(sinceMillis: Long?): String {
    if (sinceMillis == null) return "00:00:00"
    val totalSec = ((System.currentTimeMillis() - sinceMillis) / 1000L).coerceAtLeast(0L)
    val hrs = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
}

fun formatRelativeTimestamp(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0L) return "Never"
    val diffSec = ((System.currentTimeMillis() - timestamp) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60 -> "Just now"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        diffSec < 86400 -> "${diffSec / 3600}h ago"
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(timestamp))
    }
}

fun formatLogTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))

fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun disguiseIcon(presetId: String): ImageVector = when (presetId) {
    "youtube" -> Icons.Filled.PlayCircle
    "zoom" -> Icons.Filled.VideoCall
    "github" -> Icons.Filled.Code
    "cloudflare" -> Icons.Filled.Cloud
    else -> Icons.Filled.Language
}

@Composable
fun VeloScreenHeader(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun VeloSectionHeader(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(Locale.US),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        trailing?.invoke()
    }
}

@Composable
fun VeloCard(
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = MaterialTheme.shapes.medium
    if (onClick != null) {
        Card(
            onClick = onClick,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = BorderStroke(1.dp, borderColor),
            shape = shape,
            modifier = modifier.fillMaxWidth(),
            content = content
        )
    } else {
        Card(
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = BorderStroke(1.dp, borderColor),
            shape = shape,
            modifier = modifier.fillMaxWidth(),
            content = content
        )
    }
}

@Composable
fun VeloStatusBadge(
    label: String,
    accentColor: Color,
    containerColor: Color,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ProtocolSupportBadge(protocol: ProtocolType, modifier: Modifier = Modifier) {
    val velo = LocalVeloColors.current
    val supported = protocol.supportLevel.isNativeTunnelSupported
    VeloStatusBadge(
        label = protocol.supportLevel.label,
        accentColor = if (supported) velo.success else velo.warning,
        containerColor = if (supported) velo.successContainer else velo.warningContainer,
        modifier = modifier
    )
}

@Composable
fun SecurityStateBadge(
    state: com.example.domain.model.SecurityCheckState,
    modifier: Modifier = Modifier
) {
    val velo = LocalVeloColors.current
    val (accent, container) = when (state) {
        com.example.domain.model.SecurityCheckState.VERIFIED -> velo.success to velo.successContainer
        com.example.domain.model.SecurityCheckState.DISABLED,
        com.example.domain.model.SecurityCheckState.NOT_APPLICABLE ->
            MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
        com.example.domain.model.SecurityCheckState.UNKNOWN -> velo.warning to velo.warningContainer
        com.example.domain.model.SecurityCheckState.DEGRADED -> velo.danger to velo.dangerContainer
    }
    VeloStatusBadge(
        label = state.label,
        accentColor = accent,
        containerColor = container,
        modifier = modifier
    )
}

@Composable
fun ConnectionHeroButton(
    state: VpnConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val velo = LocalVeloColors.current
    val infiniteTransition = rememberInfiniteTransition(label = "hero_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepAngle"
    )

    val isBusy = state == VpnConnectionState.CONNECTING || state == VpnConnectionState.RECONNECTING
    val pressScale by animateFloatAsState(
        targetValue = if (isBusy) 0.96f else 1f,
        animationSpec = spring(),
        label = "pressScale"
    )

    val ringColor by animateColorAsState(
        targetValue = when (state) {
            VpnConnectionState.CONNECTED -> velo.success
            VpnConnectionState.CONNECTING, VpnConnectionState.RECONNECTING -> velo.warning
            VpnConnectionState.ERROR -> velo.danger
            VpnConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.primary
        },
        label = "ringColor"
    )

    val innerColor = when (state) {
        VpnConnectionState.CONNECTED -> velo.successContainer.copy(alpha = 0.65f)
        VpnConnectionState.CONNECTING, VpnConnectionState.RECONNECTING -> velo.warningContainer.copy(alpha = 0.55f)
        VpnConnectionState.ERROR -> velo.dangerContainer.copy(alpha = 0.55f)
        VpnConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(172.dp)
            .scale(pressScale)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outerRadius = size.minDimension / 2f - 8.dp.toPx()
            if (state == VpnConnectionState.CONNECTED || isBusy) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ringColor.copy(alpha = 0.22f), Color.Transparent)
                    ),
                    radius = (size.minDimension / 2f) * pulseScale
                )
            }
            drawCircle(
                color = ringColor.copy(alpha = 0.25f),
                radius = outerRadius,
                style = Stroke(width = 1.5.dp.toPx())
            )
            if (isBusy) {
                drawArc(
                    color = ringColor,
                    startAngle = sweepAngle,
                    sweepAngle = 100f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            } else if (state == VpnConnectionState.CONNECTED) {
                drawCircle(
                    color = ringColor.copy(alpha = 0.9f),
                    radius = outerRadius,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }

        Surface(
            shape = CircleShape,
            color = innerColor,
            border = BorderStroke(1.5.dp, ringColor.copy(alpha = 0.6f)),
            modifier = Modifier
                .size(132.dp)
                .clip(CircleShape)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.cd_power_button),
                    onClick = onClick
                )
                .testTag("vpn_power_button")
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = when (state) {
                        VpnConnectionState.CONNECTED -> Icons.Filled.Shield
                        VpnConnectionState.RECONNECTING -> Icons.Filled.Refresh
                        else -> Icons.Filled.PowerSettingsNew
                    },
                    contentDescription = stringResource(R.string.cd_power_button),
                    tint = ringColor,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (state) {
                        VpnConnectionState.CONNECTED -> "Connected"
                        VpnConnectionState.CONNECTING -> "Connecting…"
                        VpnConnectionState.RECONNECTING -> "Reconnecting…"
                        VpnConnectionState.ERROR -> "Retry"
                        VpnConnectionState.DISCONNECTED -> "Connect"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun DisguiseDomainPill(
    presetId: String,
    domain: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Icon(
                imageVector = disguiseIcon(presetId),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = domain,
                style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun VeloEmptyStateCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    primaryLabel: String? = null,
    onPrimaryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    VeloCard(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (primaryLabel != null && onPrimaryClick != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onPrimaryClick) {
                    Text(primaryLabel)
                }
            }
        }
    }
}

@Composable
fun VeloErrorBanner(
    message: String,
    modifier: Modifier = Modifier
) {
    val velo = LocalVeloColors.current
    Surface(
        shape = MaterialTheme.shapes.small,
        color = velo.dangerContainer,
        border = BorderStroke(1.dp, velo.danger.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = velo.danger,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    isDestructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val velo = LocalVeloColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = if (isDestructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = velo.danger,
                        contentColor = Color.White
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun FirewallInspectionCard(
    disguiseDomain: String,
    wsPath: String,
    cipherLabel: String,
    destinationSummary: String,
    modifier: Modifier = Modifier
) {
    val velo = LocalVeloColors.current
    VeloCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Wire Camouflage Preview",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Phone Bridge shapes HTTP-Upgrade Host/Path headers around P-256 ECDH + AEAD encrypted frames (header obfuscation; not a TLS wrapper). External Xray configs include TLS SNI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Observed Handshake Headers",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "GET $wsPath • Host: $disguiseDomain (Xray SNI: $disguiseDomain)",
                        style = MonoDataStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = velo.successContainer.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, velo.success.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = velo.success,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Encrypted Tunnel Inside",
                            style = MaterialTheme.typography.labelLarge,
                            color = velo.success
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Cipher: $cipherLabel • Endpoint: $destinationSummary",
                        style = MonoDataStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileShareDialog(
    profile: V2RayProfile,
    onDismiss: () -> Unit,
    onCopied: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val shareUri = remember(profile) { V2RayConfigParser.toShareUri(profile) }
    val xrayJson = remember(profile) { V2RayConfigParser.toXrayClientJson(profile) }
    val qrBitmap: Bitmap? = remember(shareUri) { QrCodeGenerator.generateBitmap(shareUri, 512) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share ${profile.name}", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("QR & Link") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Xray JSON") }
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                if (selectedTab == 0) {
                    if (qrBitmap != null) {
                        Surface(
                            color = Color.White,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.cd_qr_code),
                                modifier = Modifier
                                    .size(200.dp)
                                    .padding(10.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = shareUri,
                            style = MonoDataStyle,
                            modifier = Modifier.padding(10.dp),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            copyToClipboard(context, "VeloBridge Share URI", shareUri)
                            onCopied("Copied share link to clipboard")
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("copy_share_uri_button")
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy Share Link")
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = xrayJson,
                            style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            copyToClipboard(context, "V2Ray JSON", xrayJson)
                            onCopied("Copied Xray config.json to clipboard")
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("copy_xray_json_button")
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy Xray JSON")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
