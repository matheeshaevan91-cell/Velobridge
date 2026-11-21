package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val VeloDarkColorScheme = darkColorScheme(
    primary = SignalCyan,
    onPrimary = Color(0xFF002028),
    primaryContainer = SignalCyanContainer,
    onPrimaryContainer = Color(0xFFB3F7FF),
    secondary = ShieldEmerald,
    onSecondary = Color(0xFF002116),
    secondaryContainer = ShieldEmeraldContainer,
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = AlertAmber,
    onTertiary = Color(0xFF261900),
    tertiaryContainer = AlertAmberContainer,
    onTertiaryContainer = Color(0xFFFDE68A),
    error = DangerCoral,
    onError = Color.White,
    errorContainer = DangerCoralContainer,
    onErrorContainer = Color(0xFFFECACA),
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = SlateSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = SlateElevated,
    onSurfaceVariant = TextSecondaryDark,
    outline = SlateBorder,
    outlineVariant = Color(0xFF1D2B40)
)

private val VeloLightColorScheme = lightColorScheme(
    primary = SignalCyanLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F4FF),
    onPrimaryContainer = Color(0xFF003546),
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = LightBg,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightElevated,
    onSurfaceVariant = TextSecondaryLight,
    outline = LightBorder,
    outlineVariant = Color(0xFFE2E8F0)
)

val VeloShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun VeloBridgeTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> VeloDarkColorScheme
        else -> VeloLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VeloTypography,
        shapes = VeloShapes,
        content = content
    )
}
