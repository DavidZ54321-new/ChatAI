package com.zcw.chatai.data.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真实生产者文件（python-docx/openpyxl/python-pptx 生成）做 fixtures，放在 test/resources/doc。 */
class DocumentParserTest {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/doc/$name")!!.readBytes()

    // ---------- 纯文本 ----------

    @Test
    fun decodesUtf8AndNormalizesLineEndings() {
        val bytes = "第一行\r\n第二行\r第三行\n".toByteArray(Charsets.UTF_8)
        val parsed = DocumentParser.parse(bytes, DocumentKind.TEXT, "a.txt")
        assertEquals("第一行\n第二行\n第三行", parsed.text)
    }

    @Test
    fun stripsBomAndFallsBackToGbk() {
        val withBom = "﻿有BOM".toByteArray(Charsets.UTF_8)
        assertEquals("有BOM", DocumentParser.parse(withBom, DocumentKind.TEXT, "a.txt").text)

        val gbk = "中文GBK测试".toByteArray(charset("GBK"))
        assertEquals("中文GBK测试", DocumentParser.parse(gbk, DocumentKind.TEXT, "a.txt").text)
    }

    @Test
    fun rejectsEmptyText() {
        try {
            DocumentParser.parse("  \n ".toByteArray(Charsets.UTF_8), DocumentKind.TEXT, "a.txt")
            throw AssertionError("expected DocumentException")
        } catch (t: DocumentException) {
            // expected
        }
    }

    @Test
    fun longTextPassesThroughUntruncated() {
        // 门内有多少发多少：解析期不再截断（含代理对在内的原文逐字保留）。
        val body = "x".repeat(50_000) + "😀" + "y".repeat(50_000)
        val parsed = DocumentParser.parse(body.toByteArray(Charsets.UTF_8), DocumentKind.TEXT, "a.txt")
        assertEquals(body, parsed.text)
    }

    @Test
    fun collapsesExcessiveBlankLines() {
        assertEquals("a\n\nb", collapseBlankLines("a\n\n\n\nb"))
        assertEquals("a\nb", collapseBlankLines("a\nb"))
    }

    // ---------- docx ----------

    @Test
    fun extractsDocxParagraphsAndTables() {
        val parsed = DocumentParser.parse(fixture("sample.docx"), DocumentKind.DOCX, "简历.docx")
        assertTrue(parsed.text.contains("夹心第一段正文"))
        assertTrue(parsed.text.contains("第二段 mixed with English."))
        assertTrue(parsed.text.contains("王五"))
        assertTrue(parsed.text.contains("姓名\t城市"))
    }

    // ---------- xlsx ----------

    @Test
    fun extractsXlsxSheetsAsMarkdownTables() {
        val parsed = DocumentParser.parse(fixture("sample.xlsx"), DocumentKind.XLSX, "成绩.xlsx")
        assertTrue(parsed.text.contains("## 分数"))
        assertTrue(parsed.text.contains("赵六"))
        assertTrue(parsed.text.contains("88.5"))
        // 日期按单元格格式渲染，而不是 46000 这样的序列号。
        assertTrue(parsed.text.contains("2026-09-20"))
        assertTrue(parsed.text.contains("TRUE"))
        assertTrue(parsed.text.contains("## 第二表"))
        assertEquals("共 2 个工作表", parsed.meta)
    }

    @Test
    fun escapesTableBreakingCharacters() {
        val parsed = DocumentParser.parse(fixture("sample.xlsx"), DocumentKind.XLSX, "成绩.xlsx")
        assertTrue(parsed.text.contains("含\\|竖线"))
        assertFalse(parsed.text.contains("换\n行"))
    }

    @Test
    fun handlesEmptyAndSparseSheets() {
        val parsed = DocumentParser.parse(fixture("edge.xlsx"), DocumentKind.XLSX, "边角.xlsx")
        assertTrue(parsed.text.contains("（空表）"))
        assertTrue(parsed.text.contains("右下角"))
    }

    @Test
    fun sheetRowCapIsHighButPresent() {
        // 5000 行内存安全网：手拼 sheet XML（不走 zip），超限行截断并标注。
        val xml = buildString {
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            repeat(DocumentLimits.MAX_SHEET_ROWS + 50) { index ->
                append("<row><c><v>行$index</v></c></row>")
            }
            append("</sheetData></worksheet>")
        }.toByteArray(Charsets.UTF_8)
        val table = XlsxDocumentParser.renderSheet(
            xml,
            emptyList(),
            XlsxDocumentParser.Styles.EMPTY,
            false,
        )
        assertTrue(table.contains("行0"))
        assertTrue(table.contains("行${DocumentLimits.MAX_SHEET_ROWS - 1}"))
        assertFalse(table.contains("行${DocumentLimits.MAX_SHEET_ROWS + 49}"))
        assertTrue(table.contains("仅保留前 ${DocumentLimits.MAX_SHEET_ROWS} 行"))
    }

    // ---------- pptx ----------

    @Test
    fun extractsPptxSlidesWithSeparators() {
        val parsed = DocumentParser.parse(fixture("sample.pptx"), DocumentKind.PPTX, "分享.pptx")
        assertTrue(parsed.text.contains("【第 1 页，共 3 页】"))
        assertTrue(parsed.text.contains("夹心演示标题"))
        assertTrue(parsed.text.contains("要点一"))
        assertTrue(parsed.text.contains("列A"))
        assertTrue(parsed.text.contains("数据1"))
        assertEquals("共 3 页", parsed.meta)
    }

    @Test
    fun blankSlideYieldsPlaceholder() {
        val parsed = DocumentParser.parse(fixture("blank.pptx"), DocumentKind.PPTX, "空.pptx")
        assertTrue(parsed.text.contains("（本页无文字）"))
    }
}
