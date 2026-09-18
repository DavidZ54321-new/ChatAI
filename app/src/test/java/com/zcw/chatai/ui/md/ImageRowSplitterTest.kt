package com.zcw.chatai.ui.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRowSplitterTest {

    @Test
    fun mergesAdjacentImageLinesIntoOneRow() {
        val content = """
            ![a](https://x/a.jpg)
            ![b](https://x/b.jpg)
        """.trimIndent()
        val segments = ImageRowSplitter.split(content)
        assertEquals(1, segments.size)
        val row = segments.single() as ContentSegment.ImageRow
        assertEquals(listOf("https://x/a.jpg", "https://x/b.jpg"), row.images.map { it.url })
    }

    /** 实测 Qwen 的图搜回答：每张图之间都空一行，必须仍合并成一行。 */
    @Test
    fun mergesImagesSeparatedByBlankLines() {
        val content = """
            ![a](https://x/a.jpg)

            ![b](https://x/b.jpg)

            ![c](https://x/c.jpg)
        """.trimIndent()
        val row = ImageRowSplitter.split(content).single() as ContentSegment.ImageRow
        assertEquals(3, row.images.size)
    }

    @Test
    fun keepsTextChunksAroundTheRow() {
        val content = """
            开头说明

            ![a](https://x/a.jpg)

            ![b](https://x/b.jpg)

            结尾说明
        """.trimIndent()
        val segments = ImageRowSplitter.split(content)
        assertEquals(3, segments.size)
        assertTrue((segments[0] as ContentSegment.Markdown).text.contains("开头说明"))
        assertEquals(2, (segments[1] as ContentSegment.ImageRow).images.size)
        assertTrue((segments[2] as ContentSegment.Markdown).text.contains("结尾说明"))
    }

    @Test
    fun singleImageIsLeftToMarkdownRendering() {
        val content = "说明\n\n![a](https://x/a.jpg)\n\n更多说明"
        val segments = ImageRowSplitter.split(content)
        assertEquals(1, segments.size)
        assertTrue(segments.single() is ContentSegment.Markdown)
        assertEquals(content, (segments.single() as ContentSegment.Markdown).text)
    }

    @Test
    fun headingBetweenImagesBreaksTheRun() {
        val content = """
            ![a](https://x/a.jpg)

            **相似补充**

            ![b](https://x/b.jpg)
        """.trimIndent()
        val segments = ImageRowSplitter.split(content)
        // 两段单图 + 中间标题：没有任何 ≥2 的连续组 → 原样返回。
        assertEquals(1, segments.size)
        assertTrue(segments.single() is ContentSegment.Markdown)
    }

    @Test
    fun twoRunsSeparatedByTextBecomeTwoRows() {
        val content = """
            ![a](https://x/a.jpg)
            ![b](https://x/b.jpg)

            标题

            ![c](https://x/c.jpg)
            ![d](https://x/d.jpg)
        """.trimIndent()
        val segments = ImageRowSplitter.split(content)
        assertEquals(3, segments.size)
        assertEquals(2, (segments[0] as ContentSegment.ImageRow).images.size)
        assertTrue((segments[1] as ContentSegment.Markdown).text.contains("标题"))
        assertEquals(2, (segments[2] as ContentSegment.ImageRow).images.size)
    }

    @Test
    fun listItemImagesAreNotGrouped() {
        val content = "- ![a](https://x/a.jpg)\n- ![b](https://x/b.jpg)"
        val segments = ImageRowSplitter.split(content)
        assertEquals(1, segments.size)
        assertTrue(segments.single() is ContentSegment.Markdown)
    }

    @Test
    fun nonHttpUrlsAreNotGrouped() {
        val content = "![a](attachments/x/a.jpg)\n![b](/local/b.jpg)"
        val segments = ImageRowSplitter.split(content)
        assertEquals(1, segments.size)
        assertTrue(segments.single() is ContentSegment.Markdown)
    }

    @Test
    fun trailingTextOnTheImageLineIsNotGrouped() {
        val content = "![a](https://x/a.jpg) 这是说明\n![b](https://x/b.jpg)"
        assertEquals(1, ImageRowSplitter.split(content).size)
    }

    @Test
    fun capturesAltIncludingBracketsAndHandlesMissingAlt() {
        val content = "![水乡[桥]](https://x/a.jpg)\n![](https://x/b.jpg)"
        val row = ImageRowSplitter.split(content).single() as ContentSegment.ImageRow
        assertEquals("水乡[桥]", row.images[0].alt)
        assertEquals(null, row.images[1].alt)
    }

    @Test
    fun handlesWindowsLineEndings() {
        val content = "![a](https://x/a.jpg)\r\n![b](https://x/b.jpg)\r\n"
        val row = ImageRowSplitter.split(content).single() as ContentSegment.ImageRow
        assertEquals(2, row.images.size)
        assertEquals("https://x/b.jpg", row.images[1].url)
    }

    @Test
    fun contentWithoutImagesIsReturnedExact() {
        val content = "# 标题\n\n正文\n"
        val segments = ImageRowSplitter.split(content)
        assertEquals(listOf<ContentSegment>(ContentSegment.Markdown(content)), segments)
    }

    @Test
    fun emptyContentReturnsEmpty() {
        assertEquals(emptyList<ContentSegment>(), ImageRowSplitter.split(""))
    }

    @Test
    fun urlsWithParenthesesInsideArePreserved() {
        val content = "![a](https://x/a(b).jpg)\n![b](https://x/b.jpg)"
        val row = ImageRowSplitter.split(content).single() as ContentSegment.ImageRow
        assertEquals("https://x/a(b).jpg", row.images[0].url)
    }
}
