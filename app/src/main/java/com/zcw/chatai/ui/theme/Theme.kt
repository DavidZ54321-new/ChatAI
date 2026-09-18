package com.zcw.chatai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo

private val LightScheme = lightColorScheme(
    primary = Coral,
    onPrimary = OnPrimary,
    primaryContainer = SurfaceCreamStrong,
    onPrimaryContainer = Ink,
    inversePrimary = CoralActive,
    secondary = AccentTeal,
    onSecondary = OnPrimary,
    secondaryContainer = SurfaceCreamStrong,
    onSecondaryContainer = Ink,
    tertiary = AccentAmber,
    onTertiary = Ink,
    tertiaryContainer = SurfaceCard,
    onTertiaryContainer = Ink,
    background = Canvas,
    onBackground = Ink,
    surface = Canvas,
    onSurface = Ink,
    surfaceVariant = SurfaceSoft,
    onSurfaceVariant = Muted,
    surfaceTint = Coral,
    inverseSurface = SurfaceDark,
    inverseOnSurface = OnDark,
    error = ErrorRed,
    onError = OnPrimary,
    errorContainer = SurfaceCreamStrong,
    onErrorContainer = ErrorRed,
    outline = Hairline,
    outlineVariant = HairlineSoft,
    scrim = Ink,
    surfaceBright = Canvas,
    surfaceDim = SurfaceSoft,
    surfaceContainerLowest = Canvas,
    surfaceContainerLow = SurfaceSoft,
    surfaceContainer = SurfaceCard,
    surfaceContainerHigh = SurfaceCreamStrong,
    surfaceContainerHighest = SurfaceCreamStrong,
)

private val DarkScheme = darkColorScheme(
    primary = Coral,
    onPrimary = OnPrimary,
    primaryContainer = DarkCoralContainer,
    onPrimaryContainer = DarkOnCoralContainer,
    inversePrimary = CoralActive,
    secondary = AccentTeal,
    onSecondary = DarkOnTealContainer,
    secondaryContainer = DarkTealContainer,
    onSecondaryContainer = DarkOnTealContainer,
    tertiary = AccentAmber,
    onTertiary = DarkOnAmberContainer,
    tertiaryContainer = DarkAmberContainer,
    onTertiaryContainer = DarkOnAmberContainer,
    background = DarkCanvas,
    onBackground = OnDark,
    surface = DarkSurface,
    onSurface = OnDark,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = OnDarkSoft,
    surfaceTint = Coral,
    inverseSurface = OnDark,
    inverseOnSurface = DarkCanvas,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkCoralContainer,
    onErrorContainer = DarkError,
    outline = DarkOutline,
    outlineVariant = DarkHairline,
    scrim = DarkSurfaceLowest,
    surfaceBright = DarkSurfaceHighest,
    surfaceDim = DarkCanvas,
    surfaceContainerLowest = DarkSurfaceLowest,
    surfaceContainerLow = DarkCanvas,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceElevated,
    surfaceContainerHighest = DarkSurfaceHighest,
)

@Composable
fun ChatAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 正文等视觉尺寸相对视窗，而不是写死 sp：分屏/折叠/旋转时观感一致。
    val windowWidth = LocalWindowInfo.current.containerDpSize.width
    val chatTypography = remember(windowWidth) {
        ChatTypography.Default.forWindowWidth(windowWidth)
    }
    CompositionLocalProvider(
        LocalChatColors provides if (darkTheme) DarkChatColors else LightChatColors,
        LocalChatTypography provides chatTypography,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = Typography,
            content = content,
        )
    }
}
