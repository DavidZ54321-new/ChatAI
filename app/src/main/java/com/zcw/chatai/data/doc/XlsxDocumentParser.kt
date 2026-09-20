package com.zcw.chatai.data.doc

import java.util.Calendar
import java.util.Locale
import org.xmlpull.v1.XmlPullParser

/**
 * xlsx 按工作表抽取成 Markdown 表格（手写 XmlPullParser，不过 POI）。
 *
 * - 工作表顺序/名称按 workbook.xml + rels（不假设 sheet1..N）；
 * - 字符串按 sharedStrings 下标还原，行内字符串/布尔/错误分别处理；
 * - 日期按 styles 的 numFmt 渲染（近似 Excel 显示，不保证像素级一致）；
 * - 超过 [DocumentLimits.MAX_SHEET_ROWS] 行 / [DocumentLimits.MAX_SHEETS] 表即截断并标记。
 */
internal object XlsxDocumentParser {

    fun parse(parts: Map<String, ByteArray>): ParsedDocument {
        val workbook = parts["xl/workbook.xml"]
            ?: throw DocumentException("这个表格缺少工作簿目录，文件可能已损坏")
        val sheets = sheetTargets(workbook, parts["xl/_rels/workbook.xml.rels"] ?: ByteArray(0))
        if (sheets.isEmpty()) throw DocumentException("这个表格里没有工作表")
        val shared = parts["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val styles = parts["xl/styles.xml"]?.let(::parseStyles) ?: Styles.EMPTY
        val use1904 = isDate1904(workbook)
        val builder = StringBuilder()
        val shown = sheets.take(DocumentLimits.MAX_SHEETS)
        shown.forEach { sheet ->
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append("## ").append(sheet.name.ifBlank { "工作表" }).append('\n')
            val sheetXml = parts[sheet.target]
            if (sheetXml == null) {
                builder.append("（工作表缺失）")
            } else {
                builder.append(renderSheet(sheetXml, shared, styles, use1904))
            }
        }
        if (shown.size < sheets.size) {
            builder.append("\n\n（还有 ${sheets.size - shown.size} 个工作表未展开）")
        }
        val text = builder.toString().trim()
        if (text.isEmpty()) throw DocumentException("这个表格里没有可提取的文字")
        return ParsedDocument(text = text, meta = "共 ${sheets.size} 个工作表")
    }

    data class Sheet(val name: String, val target: String)

    /** 纯函数：workbook.xml + rels → 有序工作表（名 + 部件路径）。 */
    internal fun sheetTargets(workbookXml: ByteArray, relsXml: ByteArray): List<Sheet> {
        val entries = ArrayList<Pair<String, String>>()
        val parser = OoxmlXml.parser(workbookXml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                val name = parser.getAttributeValue(null, "name").orEmpty()
                val rid = parser.getAttributeValue(PptxDocumentParser.RELS_NAMESPACE, "id")
                if (!rid.isNullOrEmpty()) entries += name to rid
            }
            event = parser.next()
        }
        if (entries.isEmpty()) return emptyList()
        val targets = PptxDocumentParser.relationshipTargets(relsXml)
        return entries.mapNotNull { (name, rid) ->
            targets[rid]?.let { Sheet(name, PptxDocumentParser.resolve("xl", it)) }
        }
    }

    /** 纯函数：workbook 的 1904 日期制式（Mac 版 Excel 存的老文件用它）。 */
    internal fun isDate1904(workbookXml: ByteArray): Boolean {
        val parser = OoxmlXml.parser(workbookXml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "workbookPr") {
                val flag = parser.getAttributeValue(null, "date1904")
                return flag == "1" || flag == "true"
            }
            event = parser.next()
        }
        return false
    }

    /** 纯函数：sharedStrings.xml → 下标表（富文本 run 直接拼接；超长截断防炸内存）。 */
    internal fun parseSharedStrings(xml: ByteArray): List<String> {
        val result = ArrayList<String>()
        val parser = OoxmlXml.parser(xml)
        var event = parser.eventType
        var inItem = false
        val current = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> {
                        inItem = true
                        current.clear()
                    }
                }
                XmlPullParser.TEXT -> if (inItem) {
                    if (current.length < DocumentLimits.MAX_SHARED_STRING_CHARS) {
                        current.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "si" && inItem) {
                    inItem = false
                    if (result.size < DocumentLimits.MAX_SHARED_STRINGS) result += current.toString()
                }
                else -> Unit
            }
            event = parser.next()
        }
        return result
    }

    data class Styles(val xfNumFmtIds: List<Int>, val numFmtCodes: Map<Int, String>) {
        companion object {
            val EMPTY = Styles(emptyList(), emptyMap())
        }
    }

    /** 纯函数：styles.xml → cellXfs 的 numFmtId 序列 + 自定义 numFmt 代码表。 */
    internal fun parseStyles(xml: ByteArray): Styles {
        val codes = HashMap<Int, String>()
        val xfs = ArrayList<Int>()
        val parser = OoxmlXml.parser(xml)
        var event = parser.eventType
        var inCellXfs = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "numFmt" -> {
                        val id = parser.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                        val code = parser.getAttributeValue(null, "formatCode")
                        if (id != null && code != null) codes[id] = code
                    }
                    "cellXfs" -> inCellXfs = true
                    "xf" -> if (inCellXfs) {
                        xfs += parser.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0
                    }
                    else -> Unit
                }
                XmlPullParser.END_TAG -> if (parser.name == "cellXfs") inCellXfs = false
                else -> Unit
            }
            event = parser.next()
        }
        return Styles(xfs, codes)
    }

    /** 纯函数：工作表 XML → Markdown 表格（行数上限在内）。 */
    internal fun renderSheet(
        sheetXml: ByteArray,
        shared: List<String>,
        styles: Styles,
        use1904: Boolean,
    ): String {
        val rows = ArrayList<List<String>>()
        var truncated = false
        val parser = OoxmlXml.parser(sheetXml)
        var event = parser.eventType
        var currentRow: ArrayList<Cell>? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        if (rows.size >= DocumentLimits.MAX_SHEET_ROWS) {
                            truncated = true
                            // 超限的行逐个跳过（停在当前 row 的结束标签）。
                            parser.skipCurrentElement()
                            currentRow = null
                        } else {
                            currentRow = ArrayList()
                        }
                    }
                    "c" -> {
                        val row = currentRow
                        if (row != null) {
                            row += readCell(parser, shared, styles, use1904)
                        } else {
                            parser.skipSubtree()
                        }
                    }
                    else -> Unit
                }
                XmlPullParser.END_TAG -> if (parser.name == "row" && currentRow != null) {
                    // 稀疏行按列下标对齐：缺的格子补空串，无 ref 的按出现顺序追加。
                    val texts = ArrayList<String>()
                    currentRow?.forEach { cell ->
                        if (cell.column < 0) {
                            texts += cell.text
                        } else {
                            while (texts.size <= cell.column) texts += ""
                            texts[cell.column] = cell.text
                        }
                    }
                    rows += texts
                    currentRow = null
                }
                else -> Unit
            }
            event = parser.next()
        }
        if (rows.all { row -> row.all { it.isEmpty() } }) return "（空表）"
        val width = rows.maxOf { it.size }.coerceAtLeast(1)
        val builder = StringBuilder()
        rows.forEachIndexed { rowNumber, cells ->
            val padded = if (cells.size < width) cells + List(width - cells.size) { "" } else cells
            builder.append("| ").append(padded.joinToString(" | ") { escapeCell(it) }).append(" |\n")
            if (rowNumber == 0) {
                builder.append("|").append(List(width) { " --- " }.joinToString("|")).append("|\n")
            }
        }
        if (truncated) builder.append("(表格过长，仅保留前 ${DocumentLimits.MAX_SHEET_ROWS} 行)\n")
        return builder.toString().trimEnd()
    }

    private data class Cell(val column: Int, val text: String)

    /** 调用时停在 `c` 的 START_TAG 上；返回时停在其 END_TAG 上。 */
    private fun readCell(
        parser: XmlPullParser,
        shared: List<String>,
        styles: Styles,
        use1904: Boolean,
    ): Cell {
        val ref = parser.getAttributeValue(null, "r").orEmpty()
        val kind = parser.getAttributeValue(null, "t").orEmpty()
        val styleIndex = parser.getAttributeValue(null, "s")?.toIntOrNull()
        var value = ""
        var inline = false
        val inlineText = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (parser.name == "is") inline = true
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT -> {
                    val text = parser.text
                    if (inline) inlineText.append(text) else value += text
                }
                else -> Unit
            }
        }
        val text = when {
            inline -> inlineText.toString()
            kind == "s" -> value.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
            kind == "str" -> value
            kind == "b" -> if (value == "1") "TRUE" else "FALSE"
            kind == "e" -> value
            else -> formatNumeric(value, styleIndex, styles, use1904)
        }
        return Cell(column = columnIndex(ref), text = text)
    }

    /** "B3" → 1（A=0）；解析不出给 -1（按出现顺序追加）。 */
    internal fun columnIndex(ref: String): Int {
        var index = 0
        var found = false
        ref.forEach { ch ->
            val upper = ch.uppercaseChar()
            if (upper in 'A'..'Z') {
                found = true
                index = index * 26 + (upper - 'A' + 1)
            } else if (found) {
                return index - 1
            }
        }
        return if (found) index - 1 else -1
    }

    private fun formatNumeric(
        raw: String,
        styleIndex: Int?,
        styles: Styles,
        use1904: Boolean,
    ): String {
        if (raw.isBlank()) return ""
        val numFmtId = styleIndex?.let { styles.xfNumFmtIds.getOrNull(it) } ?: 0
        val code = styles.numFmtCodes[numFmtId]
        if (XlsxFormats.isDateFormat(numFmtId, code)) {
            val serial = raw.toDoubleOrNull()
            if (serial != null && serial >= 0) {
                return XlsxFormats.formatDate(serial, code, use1904) ?: plainNumber(raw)
            }
        }
        return plainNumber(raw)
    }

    internal fun plainNumber(raw: String): String {
        return try {
            java.math.BigDecimal(raw).stripTrailingZeros().toPlainString()
        } catch (t: Exception) {
            raw
        }
    }

    /** 单元格里的换行/竖线会破坏表格行结构，先转义（手写替换，不用 Regex）。 */
    internal fun escapeCell(value: String): String {
        val builder = StringBuilder(value.length)
        value.forEach { ch ->
            when (ch) {
                '|' -> builder.append("\\|")
                '\n', '\r' -> builder.append(' ')
                else -> builder.append(ch)
            }
        }
        return builder.toString().trim()
    }

    /** 调用时停在某元素的 START_TAG 上，返回时停在其配对的 END_TAG 上。 */
    private fun XmlPullParser.skipCurrentElement() {
        var depth = 1
        while (depth > 0) {
            when (next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
                else -> Unit
            }
        }
    }
}
