package com.zcw.chatai.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import com.zcw.chatai.data.prefs.ThemeFamily

/**
 * 一套主题在某种明暗下的完整观感：自有 [ChatColors] + Material3 [ColorScheme]。
 * 两者必须同源，否则组件里的 `MaterialTheme.colorScheme.primary` 与 `ChatTheme.colors` 会打架。
 */
@Immutable
data class ThemePalette(
    val chatColors: ChatColors,
    val colorScheme: ColorScheme,
)

/**
 * 一个主题族（品牌配色 + 字体）：明暗两套 [ThemePalette]，外加 Material3 [Typography]
 * 与消息正文用的 [ChatTypography]（正文衬线/无衬线随主题变）。
 * 加主题 = 在 [ThemeRegistry.all] 注册一个 `AppTheme`，不在 UI/请求层写 if-vendor。
 */
@Immutable
data class AppTheme(
    val family: ThemeFamily,
    val displayName: String,
    val light: ThemePalette,
    val dark: ThemePalette,
    val typography: Typography,
    val chatTypography: ChatTypography,
)

/**
 * 主题注册表：`ThemeFamily`（持久化枚举）→ [AppTheme]。
 * 未注册的族回落到 [Default]；`ThemeRegistryTest` 保证 [ThemeFamily.entries] 全覆盖。
 */
object ThemeRegistry {

    val Default: AppTheme = ClaudeTheme

    val all: List<AppTheme> = listOf(ClaudeTheme, ChatGptTheme)

    fun byFamily(family: ThemeFamily): AppTheme =
        all.firstOrNull { it.family == family } ?: Default

    /** 设置页选项：枚举值 → AppTheme（名称展示用 [AppTheme.displayName]）。 */
    fun options(): List<Pair<ThemeFamily, AppTheme>> = ThemeFamily.entries.map { it to byFamily(it) }
}
