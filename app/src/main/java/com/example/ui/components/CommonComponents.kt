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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.parser.QrCodeGenerator
import com.example.core.parser.V2RayConfigParser
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.V2RayProfile
import com.example.domain.model.VpnConnectionState
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.DangerCoral
import com.example.ui.theme.MonoDataStyle
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.SignalCyan
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
fun ConnectionPowerRing(
    state: VpnConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (state == VpnConnectionState.CONNECTING) 0.96f else 1f,
        animationSpec = spring(),
        label = "pressScale"
    )

    val ringColor by animateColorAsState(
        targetValue = when (state) {
            VpnConnectionState.CONNECTED -> ShieldEmerald
            VpnConnectionState.CONNECTING -> AlertAmber
            VpnConnectionState.ERROR -> DangerCoral
            VpnConnectionState.DISCONNECTED -> SignalCyan
        },
        label = "ringColor"
    )

    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(168.dp)
            .scale(pressScale)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 10.dp.toPx()
            if (state == VpnConnectionState.CONNECTED) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ringColor.copy(alpha = 0.25f), Color.Transparent)
                    ),
                    radius = (size.minDimension / 2f) * pulseScale
                )
            }
            drawCircle(
                color = ringColor.copy(alpha = 0.18f),
                radius = radius,
                style = Stroke(width = 6.dp.toPx())
            )
            if (state == VpnConnectionState.CONNECTING) {
                drawArc(
                    color = ringColor,
                    startAngle = sweepAngle,
                    sweepAngle = 110f,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
            } else if (state == VpnConnectionState.CONNECTED) {
                drawCircle(
                    color = ringColor,
                    radius = radius,
                    style = Stroke(width = 6.dp.toPx())
                )
            }
        }

        Surface(
            shape = CircleShape,
            color = if (state == VpnConnectionState.CONNECTED) {
                ringColor.copy(alpha = 0.18f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            border = BorderStroke(1.5.dp, ringColor.copy(alpha = 0.6f)),
            modifier = Modifier
                .size(124.dp)
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
                    imageVector = if (state == VpnConnectionState.CONNECTED) {
                        Icons.Filled.Shield
                    } else {
                        Icons.Filled.PowerSettingsNew
                    },
                    contentDescription = stringResource(R.string.cd_power_button),
                    tint = ringColor,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (state) {
                        VpnConnectionState.CONNECTED -> "ACTIVE"
                        VpnConnectionState.CONNECTING -> "LINKING"
                        VpnConnectionState.ERROR -> "RETRY"
                        VpnConnectionState.DISCONNECTED -> "CONNECT"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = ringColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun TrafficSparklineChart(
    samples: List<Long>,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val points = if (samples.size < 2) listOf(0L, 0L, 0L, 0L) else samples
        val maxVal = (points.maxOrNull() ?: 1L).coerceAtLeast(1024L).toFloat()
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)

        val linePath = Path()
        val fillPath = Path()

        points.forEachIndexed { idx, value ->
            val x = idx * stepX
            val normalized = (value.toFloat() / maxVal).coerceIn(0f, 1f)
            val y = size.height - (normalized * (size.height * 0.85f)) - 4.dp.toPx()
            if (idx == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                val prevX = (idx - 1) * stepX
                val prevNorm = (points[idx - 1].toFloat() / maxVal).coerceIn(0f, 1f)
                val prevY = size.height - (prevNorm * (size.height * 0.85f)) - 4.dp.toPx()
                val midX = (prevX + x) / 2f
                linePath.cubicTo(midX, prevY, midX, y, x, y)
                fillPath.cubicTo(midX, prevY, midX, y, x, y)
            }
            if (idx == points.lastIndex) {
                fillPath.lineTo(x, size.height)
                fillPath.close()
            }
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(accentColor.copy(alpha = 0.28f), Color.Transparent)
            )
        )
        drawPath(
            path = linePath,
            color = accentColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
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
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
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
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = domain,
                style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun FirewallInspectionCard(
    disguiseDomain: String,
    wsPath: String,
    cipherLabel: String,
    destinationSummary: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Live Camouflage Wire Preview",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Visibility,
                                contentDescription = null,
                                tint = AlertAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ISP / Firewall Sees",
                                style = MaterialTheme.typography.labelMedium,
                                color = AlertAmber
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "TLS SNI: $disguiseDomain\nGET $wsPath\nHost: $disguiseDomain",
                            style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                tint = ShieldEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Encrypted Inside",
                                style = MaterialTheme.typography.labelMedium,
                                color = ShieldEmerald
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Cipher: $cipherLabel\nBridge: $destinationSummary\nPayload: Sealed AEAD",
                            style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
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
        title = {
            Text(text = "Share ${profile.name}", style = MaterialTheme.typography.titleLarge)
        },
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
                        text = { Text("V2Ray JSON") }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
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
                            copyToClipboard(context, "V2Ray URI", shareUri)
                            onCopied("Copied share link to clipboard")
                        },
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
                            onCopied("Copied V2Ray config.json to clipboard")
                        },
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
