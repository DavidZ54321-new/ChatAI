package com.zcw.chatai.ui.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewBlockSplitterTest {

    @Test
    fun detectsMermaidBlockWithSurroundingText() {
        val content = "思路如下：\n\n```mermaid\ngraph TD\nA-->B\n```\n\n以上是流程。"
        val segments = PreviewBlockSplitter.split(content)
        assertEquals(3, segments.size)
        assertTrue((segments[0] as PreviewSegment.Markdown).text.contains("思路如下"))
        val preview = segments[1] as PreviewSegment.Preview
        assertEquals(PreviewLanguage.MERMAID, preview.language)
        assertTrue(preview.code.contains("graph TD"))
        assertTrue((segments[2] as PreviewSegment.Markdown).text.contains("以上是流程"))
    }

    @Test
    fun languageMatchingIsCaseInsensitive() {
        val content = "```Mermaid\ngraph TD\nA-->B\n```"
        val preview = PreviewBlockSplitter.split(content).single() as PreviewSegment.Preview
        assertEquals(PreviewLanguage.MERMAID, preview.language)
    }

    @Test
    fun detectsSvgAndHtmlBlocks() {
        val svg = "```svg\n<svg></svg>\n```"
        assertEquals(
            PreviewLanguage.SVG,
            (PreviewBlockSplitter.split(svg).single() as PreviewSegment.Preview).language,
        )
        val html = "```html\n<b>x</b>\n```"
        assertEquals(
            PreviewLanguage.HTML,
            (PreviewBlockSplitter.split(html).single() as PreviewSegment.Preview).language,
        )
    }

    @Test
    fun otherLanguagesStayMarkdown() {
        val content = "```kotlin\nval x = 1\n```"
        val segments = PreviewBlockSplitter.split(content)
        assertEquals(1, segments.size)
        assertEquals(content, (segments.single() as PreviewSegment.Markdown).text)
    }

    @Test
    fun unclosedFenceStaysMarkdownForStreaming() {
        val content = "说明\n\n```mermaid\ngraph TD\nA-->B"
        val segments = PreviewBlockSplitter.split(content)
        assertEquals(1, segments.size)
        assertTrue(segments.single() is PreviewSegment.Markdown)
    }

    @Test
    fun blankCodeBlockStaysMarkdown() {
        val content = "```mermaid\n   \n```"
        val segments = PreviewBlockSplitter.split(content)
        assertEquals(1, segments.size)
        assertTrue(segments.single() is PreviewSegment.Markdown)
    }

    @Test
    fun tildeFenceIsSupported() {
        val content = "~~~mermaid\ngraph TD\nA-->B\n~~~"
        val preview = PreviewBlockSplitter.split(content).single() as PreviewSegment.Preview
        assertEquals(PreviewLanguage.MERMAID, preview.language)
    }

    @Test
    fun indentedCodeBlockIsNotSplit() {
        val content = "示例：\n\n    ```mermaid\n    graph TD\n    ```"
        val segments = PreviewBlockSplitter.split(content)
        assertEquals(1, segments.size)
        assertTrue(segments.single() is PreviewSegment.Markdown)
    }

    @Test
    fun infoStringExtraTokensAreIgnored() {
        val content = "```html run\n<b>x</b>\n```"
        val preview = PreviewBlockSplitter.split(content).single() as PreviewSegment.Preview
        assertEquals(PreviewLanguage.HTML, preview.language)
        assertEquals("<b>x</b>", preview.code)
    }

    @Test
    fun contentWithoutPreviewIsReturnedExact() {
        val content = "# 标题\n\n```kotlin\ncode\n```\n"
        assertEquals(
            listOf<PreviewSegment>(PreviewSegment.Markdown(content)),
            PreviewBlockSplitter.split(content),
        )
    }

    @Test
    fun emptyContentReturnsEmpty() {
        assertEquals(emptyList<PreviewSegment>(), PreviewBlockSplitter.split(""))
    }
}
