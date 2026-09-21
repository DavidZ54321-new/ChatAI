package com.zcw.chatai.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
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
    /**
     * 正文字号随视窗宽度缩放；视窗未测量（Unspecified/0）时退回默认字号。
     * 与消息图片缩略图一样，比例相对视窗，旋转/分屏时观感一致。
     */
    fun forWindowWidth(windowWidth: Dp): ChatTypography {
        if (!windowWidth.isSpecified || windowWidth.value <= 0f) return this
        val bodySize = windowWidth.value * BODY_TEXT_FRACTION
        return copy(
            messageBody = messageBody.copy(
                fontSize = bodySize.sp,
                lineHeight = (bodySize * BODY_LINE_HEIGHT_RATIO).sp,
            ),
        )
    }

    companion object {
        /** 正文字号 = 视窗宽度的 4.5%。 */
        const val BODY_TEXT_FRACTION = 0.045f

        /** 正文行高 / 字号（沿用 29sp / 18sp 的比例）。 */
        const val BODY_LINE_HEIGHT_RATIO = 29f / 18f

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

        /** OpenAI / ChatGPT：正文无衬线（沿用同一字号/行高比例）。 */
        val ChatGpt = ChatTypography(
            code = TextStyle(
                fontFamily = MonoCode,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                letterSpacing = 0.sp,
            ),
            messageBody = TextStyle(
                fontFamily = SansUi,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                lineHeight = 29.sp,
                letterSpacing = 0.sp,
            ),
        )
    }
}

/** 回落到 Claude 浅色：`ChatAITheme` 总会提供真实的 [ThemePalette]，这里只是无 Provider 时的兜底。 */
val LocalChatColors = staticCompositionLocalOf { ThemeRegistry.Default.light.chatColors }

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
