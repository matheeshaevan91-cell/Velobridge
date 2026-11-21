package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.domain.model.AppearanceMode

private val VeloDarkColorScheme = darkColorScheme(
    primary = SignalCyan,
    onPrimary = Color(0xFF042030),
    primaryContainer = SignalCyanContainer,
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = ShieldEmerald,
    onSecondary = Color(0xFF022117),
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
    surfaceContainerHigh = SlateElevatedHigh,
    outline = SlateBorder,
    outlineVariant = Color(0xFF1A263A)
)

private val VeloLightColorScheme = lightColorScheme(
    primary = SignalCyanLightPrimary,
    onPrimary = Color.White,
    primaryContainer = SignalCyanLightContainer,
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = ShieldEmeraldLight,
    onSecondary = Color.White,
    secondaryContainer = ShieldEmeraldLightContainer,
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = AlertAmberLight,
    onTertiary = Color.White,
    tertiaryContainer = AlertAmberLightContainer,
    onTertiaryContainer = Color(0xFF78350F),
    error = DangerCoralLight,
    onError = Color.White,
    errorContainer = DangerCoralLightContainer,
    onErrorContainer = Color(0xFF7F1D1D),
    background = LightBg,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightElevated,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerHigh = LightElevatedHigh,
    outline = LightBorder,
    outlineVariant = Color(0xFFE2E8F0)
)

val VeloShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Immutable
data class VeloSemanticColors(
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val mutedText: Color,
    val isDark: Boolean
)

private val DarkSemanticColors = VeloSemanticColors(
    success = ShieldEmerald,
    successContainer = ShieldEmeraldContainer,
    warning = AlertAmber,
    warningContainer = AlertAmberContainer,
    info = SignalCyan,
    infoContainer = SignalCyanContainer,
    danger = DangerCoral,
    dangerContainer = DangerCoralContainer,
    mutedText = TextMutedDark,
    isDark = true
)

private val LightSemanticColors = VeloSemanticColors(
    success = ShieldEmeraldLight,
    successContainer = ShieldEmeraldLightContainer,
    warning = AlertAmberLight,
    warningContainer = AlertAmberLightContainer,
    info = SignalCyanLightPrimary,
    infoContainer = SignalCyanLightContainer,
    danger = DangerCoralLight,
    dangerContainer = DangerCoralLightContainer,
    mutedText = TextSecondaryLight,
    isDark = false
)

val LocalVeloColors = staticCompositionLocalOf { DarkSemanticColors }

object VeloSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val maxContentWidth: Dp = 720.dp
}

@Composable
fun VeloBridgeTheme(
    appearanceMode: AppearanceMode = AppearanceMode.DARK,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val useDark = when (appearanceMode) {
        AppearanceMode.DARK -> true
        AppearanceMode.LIGHT -> false
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDark -> VeloDarkColorScheme
        else -> VeloLightColorScheme
    }

    val semantic = if (useDark) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalVeloColors provides semantic) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = VeloTypography,
            shapes = VeloShapes,
            content = content
        )
    }
}
