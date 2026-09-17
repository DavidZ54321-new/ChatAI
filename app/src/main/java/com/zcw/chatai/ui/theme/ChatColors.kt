package com.zcw.chatai.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class ChatColors(
    val canvas: Color,
    val surfaceSoft: Color,
    val surfaceCard: Color,
    val surfaceCreamStrong: Color,
    val hairline: Color,
    val hairlineSoft: Color,
    val codeBackground: Color,
    val codeOnBackground: Color,
    val codeHeaderText: Color,
    val codeButtonBackground: Color,
    val bubbleUser: Color,
    val bubbleUserText: Color,
    val chipBackground: Color,
    val accentTeal: Color,
    val accentAmber: Color,
    val success: Color,
    val warning: Color,
)

@Immutable
data class ChatTypography(
    val code: TextStyle,
    val messageBody: TextStyle,
) {
    companion object {
        val Default = ChatTypography(
            code = TextStyle(
                fontFamily = MonoCode,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                letterSpacing = 0.sp,
            ),
            messageBody = TextStyle(
                fontFamily = SerifDisplay,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                lineHeight = 29.sp,
                letterSpacing = 0.sp,
            ),
        )
    }
}

val LightChatColors = ChatColors(
    canvas = Canvas,
    surfaceSoft = SurfaceSoft,
    surfaceCard = SurfaceCard,
    surfaceCreamStrong = SurfaceCreamStrong,
    hairline = Hairline,
    hairlineSoft = HairlineSoft,
    codeBackground = SurfaceDark,
    codeOnBackground = OnDark,
    codeHeaderText = OnDarkSoft,
    codeButtonBackground = SurfaceDarkElevated,
    bubbleUser = SurfaceCard,
    bubbleUserText = Ink,
    chipBackground = SurfaceSoft,
    accentTeal = AccentTeal,
    accentAmber = AccentAmber,
    success = Success,
    warning = Warning,
)

val DarkChatColors = ChatColors(
    canvas = DarkCanvas,
    surfaceSoft = DarkSurface,
    surfaceCard = DarkSurfaceElevated,
    surfaceCreamStrong = DarkSurfaceHighest,
    hairline = DarkHairline,
    hairlineSoft = DarkHairlineSoft,
    codeBackground = DarkSurfaceLowest,
    codeOnBackground = OnDark,
    codeHeaderText = OnDarkSoft,
    codeButtonBackground = DarkSurfaceElevated,
    bubbleUser = DarkSurfaceElevated,
    bubbleUserText = OnDark,
    chipBackground = DarkSurfaceHighest,
    accentTeal = AccentTeal,
    accentAmber = AccentAmber,
    success = Success,
    warning = Warning,
)

val LocalChatColors = staticCompositionLocalOf { LightChatColors }

val LocalChatTypography = staticCompositionLocalOf { ChatTypography.Default }

object ChatTheme {
    val colors: ChatColors
        @Composable
        @ReadOnlyComposable
        get() = LocalChatColors.current

    val typography: ChatTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalChatTypography.current
}
