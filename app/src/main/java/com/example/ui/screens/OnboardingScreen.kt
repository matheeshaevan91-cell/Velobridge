package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.components.VeloCard
import com.example.ui.theme.LocalVeloColors

private data class OnboardingSlide(
    val icon: ImageVector,
    val badge: String,
    val title: String,
    val description: String,
    val detail: String
)

private val OnboardingSlides = listOf(
    OnboardingSlide(
        icon = Icons.Filled.PhoneAndroid,
        badge = "STEP 1 • BRIDGE",
        title = "Turn Any Old Phone Into Your Private Bridge",
        description = "Run VeloBridge Server on a spare phone at home or on Wi-Fi, then pair your daily phone in one tap.",
        detail = "Zero cloud third-parties required—traffic routes directly between your devices over LAN or Hotspot."
    ),
    OnboardingSlide(
        icon = Icons.Filled.Public,
        badge = "STEP 2 • DISGUISE",
        title = "Camouflage Traffic as Trusted Websites",
        description = "Shape handshake SNI, Host headers, and WebSocket paths to resemble YouTube, Zoom, GitHub, Cloudflare, or a custom domain.",
        detail = "Built-in live SNI & TLS diagnostics test whether a disguise domain is reachable on your current network."
    ),
    OnboardingSlide(
        icon = Icons.Filled.Lock,
        badge = "STEP 3 • SECURITY",
        title = "End-to-End AEAD & Hardware-Protected Secrets",
        description = "Every Phone Bridge frame uses AES-256-GCM or ChaCha20-Poly1305 with HKDF-SHA256 keys and replay-guarded authentication.",
        detail = "Stored UUIDs and credentials stay encrypted locally via Android Keystore when protection is enabled."
    )
)

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    var stepIndex by remember { mutableIntStateOf(0) }
    val slide = OnboardingSlides[stepIndex]
    val velo = LocalVeloColors.current

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding_screen")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "VeloBridge 2.0",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = onFinish,
                        modifier = Modifier.testTag("onboarding_skip_button")
                    ) {
                        Text("Skip")
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        modifier = Modifier.size(96.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = slide.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = slide.badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = velo.info
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = slide.title,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = slide.description,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    VeloCard {
                        Text(
                            text = slide.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OnboardingSlides.forEachIndexed { idx, _ ->
                            Surface(
                                shape = CircleShape,
                                color = if (idx == stepIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                modifier = Modifier
                                    .height(8.dp)
                                    .width(if (idx == stepIndex) 24.dp else 8.dp)
                            ) {}
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (stepIndex > 0) {
                            OutlinedButton(
                                onClick = { stepIndex-- },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text("Back")
                            }
                        }
                        if (stepIndex < OnboardingSlides.lastIndex) {
                            Button(
                                onClick = { stepIndex++ },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("onboarding_next_button")
                            ) {
                                Text("Next")
                            }
                        } else {
                            Button(
                                onClick = onFinish,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("onboarding_finish_button")
                            ) {
                                Text("Get Started")
                            }
                        }
                    }
                }
            }
        }
    }
}
