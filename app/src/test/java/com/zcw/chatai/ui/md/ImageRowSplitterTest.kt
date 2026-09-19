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

    /** 单张也要拆出行内渲染：mikepenz 的行内占位在真机会压字（S23 实测）。 */
    @Test
    fun singleImageBecomesItsOwnBlock() {
        val content = "说明\n\n![a](https://x/a.jpg)\n\n更多说明"
        val segments = ImageRowSplitter.split(content)
        assertEquals(3, segments.size)
        assertTrue((segments[0] as ContentSegment.Markdown).text.contains("说明"))
        val row = segments[1] as ContentSegment.ImageRow
        assertEquals(listOf("https://x/a.jpg"), row.images.map { it.url })
        assertTrue((segments[2] as ContentSegment.Markdown).text.contains("更多说明"))
    }

    /** 图与后续文字同一个段落（软换行）：抽走图片，文字各归各段。 */
    @Test
    fun imageLineInsideParagraphIsExtracted() {
        val content = "⑤ 励志款\n![a](https://x/a.jpg)\n原帖：[知乎](https://x/q)"
        val segments = ImageRowSplitter.split(content)
        assertEquals(3, segments.size)
        assertTrue((segments[0] as ContentSegment.Markdown).text.contains("励志款"))
        assertEquals(1, (segments[1] as ContentSegment.ImageRow).images.size)
        assertTrue((segments[2] as ContentSegment.Markdown).text.contains("原帖"))
    }

    @Test
    fun headingBetweenImagesBreaksTheRun() {
        val content = """
            ![a](https://x/a.jpg)

            **相似补充**

            ![b](https://x/b.jpg)
        """.trimIndent()
        val segments = ImageRowSplitter.split(content)
        // 单图 + 中间标题：两段都是一张的图行，不再合并且不再原样返回。
        assertEquals(3, segments.size)
        assertEquals(1, (segments[0] as ContentSegment.ImageRow).images.size)
        assertTrue((segments[1] as ContentSegment.Markdown).text.contains("相似补充"))
        assertEquals(1, (segments[2] as ContentSegment.ImageRow).images.size)
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

    /** 围栏代码块里的 `![...]()` 是示例文本，拆出去会把代码块撕成两半。 */
    @Test
    fun imageInsideFenceStaysInMarkdown() {
        val content = "示例：\n```markdown\n![a](https://x/a.jpg)\n```"
        val segments = ImageRowSplitter.split(content)
        assertEquals(1, segments.size)
        assertEquals(content, (segments.single() as ContentSegment.Markdown).text)
    }

    /** 缩进 4 空格是缩进代码块，同样不拆。 */
    @Test
    fun indentedImageLineStaysInMarkdown() {
        val content = "示例：\n\n    ![a](https://x/a.jpg)"
        val segments = ImageRowSplitter.split(content)
        assertEquals(1, segments.size)
        assertTrue(segments.single() is ContentSegment.Markdown)
    }

    /** 围栏结束后，围栏外的图行照常成块（围栏状态要正确关闭）。 */
    @Test
    fun imageAfterClosedFenceStillSplits() {
        val content = "```\ncode\n```\n\n![a](https://x/a.jpg)\n\n![b](https://x/b.jpg)"
        val segments = ImageRowSplitter.split(content)
        assertEquals(2, segments.size)
        assertTrue((segments[0] as ContentSegment.Markdown).text.contains("```"))
        assertEquals(2, (segments[1] as ContentSegment.ImageRow).images.size)
    }

    /** 围栏开在最后一行（未闭合）时，后面的图行也应视为代码内容。 */
    @Test
    fun unclosedFenceSwallowsFollowingImageLines() {
        val content = "```markdown\n![a](https://x/a.jpg)"
        val segments = ImageRowSplitter.split(content)
        assertEquals(1, segments.size)
        assertTrue(segments.single() is ContentSegment.Markdown)
    }

    /** 行尾带说明文字的图片行不是「整行只有图片」，仍留给 markdown；纯图行照常成块。 */
    @Test
    fun trailingTextOnTheImageLineIsNotGrouped() {
        val content = "![a](https://x/a.jpg) 这是说明\n![b](https://x/b.jpg)"
        val segments = ImageRowSplitter.split(content)
        assertEquals(2, segments.size)
        assertTrue((segments[0] as ContentSegment.Markdown).text.contains("这是说明"))
        assertEquals(listOf("https://x/b.jpg"), (segments[1] as ContentSegment.ImageRow).images.map { it.url })
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
