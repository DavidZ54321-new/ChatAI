package com.zcw.chatai.data.doc

/**
 * 文档解析入口：字节 + 类型 → 纯文本（调用方保证在 IO 线程上）。
 *
 * 各格式实现只做文本抽取，不处理图片/批注/修订；抛出的 [DocumentException]
 * message 可直接展示给用户。
 */
object DocumentParser {

    fun parse(fileBytes: ByteArray, kind: DocumentKind, fileName: String): ParsedDocument {
        if (fileBytes.isEmpty()) throw DocumentException("文档是空文件")
        try {
            return when (kind) {
                DocumentKind.PDF -> PdfDocumentParser.parse(fileBytes)
                // OOXML 先按中央目录解包（脏包顺手清洗），再按部件解析。
                DocumentKind.DOCX -> DocxDocumentParser.parse(OoxmlZip.parts(fileBytes))
                DocumentKind.XLSX -> XlsxDocumentParser.parse(OoxmlZip.parts(fileBytes))
                DocumentKind.PPTX -> PptxDocumentParser.parse(OoxmlZip.parts(fileBytes))
                DocumentKind.TEXT -> TextDocumentParser.parse(fileBytes)
            }
        } catch (t: DocumentException) {
            throw t
        } catch (t: Throwable) {
            throw DocumentException("解析${labelOf(kind, fileName)}失败：${t.message ?: "文件可能已损坏"}", t)
        }
    }

    private fun labelOf(kind: DocumentKind, fileName: String): String {
        val name = fileName.ifBlank { "文档" }
        return when (kind) {
            DocumentKind.PDF -> " PDF $name"
            DocumentKind.DOCX -> " Word 文档 $name"
            DocumentKind.XLSX -> "表格 $name"
            DocumentKind.PPTX -> "演示文稿 $name"
            DocumentKind.TEXT -> "文本 $name"
        }
    }
}

/** 连续 3+ 个换行压成 2 个（手写循环，不用 Regex：Android 是 ICU 引擎）。 */
internal fun collapseBlankLines(text: String): String {
    val builder = StringBuilder(text.length)
    var newlines = 0
    text.forEach { ch ->
        if (ch == '\n') {
            newlines++
            if (newlines <= 2) builder.append(ch)
        } else {
            newlines = 0
            builder.append(ch)
        }
    }
    return builder.toString()
}
