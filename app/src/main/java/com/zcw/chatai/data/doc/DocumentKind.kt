package com.zcw.chatai.data.doc

/**
 * 支持的文档类型（纯逻辑，JVM 单测覆盖）。
 *
 * 首期只收现代格式 + 纯文本：PDF / docx / xlsx / pptx / txt·md·csv·log·json。
 * 老格式（doc/ppt/xls，OLE2 容器）与扫描件 OCR 明确不在范围内，遇到时给可读提示。
 */
enum class DocumentKind {
    PDF,
    DOCX,
    XLSX,
    PPTX,
    TEXT,
    ;

    companion object {

        /** 按 resolver MIME → 文件名扩展名 → 文件头魔数逐级判定；都不命中则尝试文本嗅探。 */
        fun detect(mimeType: String?, fileName: String?, head: ByteArray): DocumentKind {
            val mime = mimeType?.trim()?.lowercase()
            val ext = fileName?.substringAfterLast('.', "")?.trim()?.lowercase().orEmpty()
            if (mime == MIME_PDF || ext == "pdf" || startsWith(head, PDF_MAGIC)) return PDF
            if (mime == MIME_DOCX || ext == "docx" || ext == "docm") return DOCX
            if (mime == MIME_XLSX || ext == "xlsx" || ext == "xlsm") return XLSX
            if (mime == MIME_PPTX || ext == "pptx") return PPTX
            if (mime == MIME_CSV || ext == "csv") return TEXT
            if (mime == MIME_MARKDOWN || ext == "md" || ext == "markdown") return TEXT
            if (mime == MIME_JSON || ext == "json") return TEXT
            // OLE2 老格式判定位于通用文本兜底之前：误标 text/plain 的 .doc 必须进这里，
            // 否则会被当纯文本解成乱码。
            if (startsWith(head, OLE2_MAGIC)) {
                throw DocumentException(
                    "暂不支持老格式 .$ext（Word 97-2003 等），请另存为 .docx 或 PDF 后再发送",
                )
            }
            if ((mime?.startsWith("text/") == true) || ext in TEXT_EXTENSIONS) return TEXT
            if (looksLikeText(head, mime, ext)) return TEXT
            throw DocumentException(
                "暂不支持这种文档格式（${fileName?.ifBlank { null } ?: "未知文件"}），" +
                    "目前支持 PDF / docx / xlsx / pptx / txt·md·csv",
            )
        }

        private fun startsWith(head: ByteArray, magic: ByteArray): Boolean {
            if (head.size < magic.size) return false
            return magic.indices.all { head[it] == magic[it] }
        }

        /**
         * 无扩展名/无 MIME 时的兜底：前 8KB 里没有 NUL 字节就当纯文本收
         *（PDF 与 OOXML 的二进制头里通常都有 NUL）。
         */
        private fun looksLikeText(head: ByteArray, mime: String?, ext: String): Boolean {
            if (!mime.isNullOrBlank() || ext.isNotBlank()) return false
            if (head.isEmpty()) return false
            val scanned = head.take(8192)
            return scanned.none { it == 0.toByte() }
        }

        private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF
        private val OLE2_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte())

        private const val MIME_PDF = "application/pdf"
        private const val MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val MIME_PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        private const val MIME_CSV = "text/csv"
        private const val MIME_MARKDOWN = "text/markdown"
        private const val MIME_JSON = "application/json"

        private val TEXT_EXTENSIONS = setOf("txt", "log")
    }
}
