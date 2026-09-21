package com.zcw.chatai.ui.md

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览入口的闭合判定用的是真实解析器（与 `rememberMarkdownState` 同一个 GFM flavour）：
 * 只有闭合围栏才挂「预览」，流式中的未闭合围栏不挂。
 */
class CodeFenceClosedTest {

    // 解析器只有 String 重载被标了 deprecated（替代的 CharSequence 重载要显式传区间，
    // 对测试没意义）；这里用最直白的入口，只压掉这条警告。
    @Suppress("DEPRECATION")
    private fun fence(markdown: String): ASTNode =
        MarkdownParser(GFMFlavourDescriptor())
            .buildMarkdownTreeFromString(markdown)
            .children
            .first { it.type == MarkdownElementTypes.CODE_FENCE }

    @Test
    fun closedFenceHasClosingMarker() {
        assertTrue(hasClosingFence(fence("```mermaid\ngraph TD\nA-->B\n```")))
    }

    @Test
    fun streamingFenceHasNoClosingMarker() {
        assertFalse(hasClosingFence(fence("```mermaid\ngraph TD\nA-->B")))
    }

    @Test
    fun closedHtmlFenceHasClosingMarker() {
        assertTrue(hasClosingFence(fence("```html\n<b>x</b>\n```")))
    }

    @Test
    fun openFenceStillParsesAsCodeFence() {
        // 未闭合也必须解析成 CODE_FENCE（而不是退化成段落），否则流式中连代码块都不画。
        val fence = fence("```svg\n<svg>")
        assertFalse(hasClosingFence(fence))
    }
}
