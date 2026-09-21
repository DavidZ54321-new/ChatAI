package com.zcw.chatai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo
import com.zcw.chatai.data.prefs.ThemeFamily

/**
 * 双轴主题：主题族（品牌配色）+ 明暗。配色表在 [ThemeRegistry]，加主题不改这里。
 */
@Composable
fun ChatAITheme(
    themeFamily: ThemeFamily = ThemeFamily.CLAUDE,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 正文等视觉尺寸相对视窗，而不是写死 sp：分屏/折叠/旋转时观感一致。
    val windowWidth = LocalWindowInfo.current.containerDpSize.width
    val theme = ThemeRegistry.byFamily(themeFamily)
    val chatTypography = remember(windowWidth, themeFamily) {
        theme.chatTypography.forWindowWidth(windowWidth)
    }
    val palette = if (darkTheme) theme.dark else theme.light
    CompositionLocalProvider(
        LocalChatColors provides palette.chatColors,
        LocalChatTypography provides chatTypography,
    ) {
        MaterialTheme(
            colorScheme = palette.colorScheme,
            typography = theme.typography,
            content = content,
        )
    }
}
