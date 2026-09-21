package com.zcw.chatai.ui.theme

import androidx.compose.ui.graphics.Color
import com.zcw.chatai.data.prefs.ThemeFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ThemeRegistryTest {

    /** 每个持久化枚举值都必须有对应主题，否则 `byFamily` 会静默回落。 */
    @Test
    fun registryCoversEveryFamilyExactlyOnce() {
        assertEquals(
            ThemeFamily.entries.toSet(),
            ThemeRegistry.all.map { it.family }.toSet(),
        )
        assertEquals(ThemeFamily.entries.size, ThemeRegistry.all.size)
        ThemeFamily.entries.forEach { family ->
            assertEquals(family, ThemeRegistry.byFamily(family).family)
        }
    }

    @Test
    fun everyThemeHasDistinctLightAndDarkSurfaces() {
        ThemeRegistry.all.forEach { theme ->
            assertNotEquals(
                "画布明暗必须不同：${theme.family}",
                theme.light.chatColors.canvas,
                theme.dark.chatColors.canvas,
            )
            assertNotEquals(
                "Material3 background 明暗必须不同：${theme.family}",
                theme.light.colorScheme.background,
                theme.dark.colorScheme.background,
            )
        }
    }

    /** Claude 珊瑚/奶油 vs ChatGPT 黑白 —— 防两套主题靠复制粘贴变成同一张表。 */
    @Test
    fun familiesHaveDistinctBrandAccentAndSurfaces() {
        val claude = ThemeRegistry.byFamily(ThemeFamily.CLAUDE)
        val chatGpt = ThemeRegistry.byFamily(ThemeFamily.CHATGPT)
        assertNotEquals(claude.light.colorScheme.primary, chatGpt.light.colorScheme.primary)
        assertNotEquals(claude.dark.colorScheme.primary, chatGpt.dark.colorScheme.primary)
        assertNotEquals(claude.light.chatColors.canvas, chatGpt.light.chatColors.canvas)
        assertNotEquals(claude.light.chatColors.surfaceCard, chatGpt.light.chatColors.surfaceCard)
    }

    /** ChatGPT 画布用纯白/纯黑；Claude 保留奶油 #faf9f5 / 暖黑 #181715，别跟着改。 */
    @Test
    fun chatGptCanvasIsPureWhiteAndBlackWhileClaudeStaysWarm() {
        val chatGpt = ThemeRegistry.byFamily(ThemeFamily.CHATGPT)
        assertEquals(Color(0xFFFFFFFF), chatGpt.light.chatColors.canvas)
        assertEquals(Color(0xFF000000), chatGpt.dark.chatColors.canvas)
        assertEquals(Color(0xFFFFFFFF), chatGpt.light.colorScheme.background)
        assertEquals(Color(0xFF000000), chatGpt.dark.colorScheme.background)

        val claude = ThemeRegistry.byFamily(ThemeFamily.CLAUDE)
        assertEquals(Color(0xFFFAF9F5), claude.light.chatColors.canvas)
        assertEquals(Color(0xFF181715), claude.dark.chatColors.canvas)
    }

    @Test
    fun optionsExposeDisplayNameForEachFamily() {
        val options = ThemeRegistry.options()
        assertEquals(ThemeFamily.entries.size, options.size)
        options.forEach { (family, theme) ->
            assertEquals(family, theme.family)
            assertTrue("主题名不能为空：$family", theme.displayName.isNotBlank())
        }
    }

    /**
     * ChatGPT 是单色系统：主色必须是无色相的黑（浅色）/ 白（深色）。
     * 这条断言是防回归护栏 —— 别再把 logo 绿之类的东西塞进 chrome。
     */
    @Test
    fun chatGptPrimaryIsNeutralBlackAndWhite() {
        val chatGpt = ThemeRegistry.byFamily(ThemeFamily.CHATGPT)
        val light = chatGpt.light.colorScheme.primary
        val dark = chatGpt.dark.colorScheme.primary
        assertNeutral(light, "ChatGPT 浅色 primary")
        assertNeutral(dark, "ChatGPT 深色 primary")
        assertEquals(Color(0xFF000000), light)
        assertEquals(Color(0xFFFFFFFF), dark)
    }

    /** 正文衬线/无衬线随主题变：Claude 衬线，ChatGPT 无衬线。 */
    @Test
    fun chatGptBodyIsSansWhileClaudeIsSerif() {
        val claude = ThemeRegistry.byFamily(ThemeFamily.CLAUDE).chatTypography.messageBody
        val chatGpt = ThemeRegistry.byFamily(ThemeFamily.CHATGPT).chatTypography.messageBody
        assertNotEquals(claude.fontFamily, chatGpt.fontFamily)
    }

    private fun assertNeutral(color: Color, name: String) {
        val maxDelta = maxOf(abs(color.red - color.green), abs(color.green - color.blue))
        assertTrue("$name 必须是无色相灰阶，实际 r=${color.red} g=${color.green} b=${color.blue}", maxDelta < 0.02f)
    }
}
