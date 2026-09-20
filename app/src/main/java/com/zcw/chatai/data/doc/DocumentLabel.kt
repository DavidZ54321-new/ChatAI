package com.zcw.chatai.data.doc

/**
 * 文档缩略图中央的类型戳（纯函数，JVM 单测覆盖）：PDF / DOCX / XLSX / PPTX / TXT ……
 * MIME 优先，缺失时退回文件扩展名；都取不到给 FILE（调用方只对 DOCUMENT 附件调用）。
 */
object DocumentLabel {

    fun of(mimeType: String?, path: String?): String {
        val mime = mimeType?.trim()?.lowercase()
        val ext = path
            ?.substringAfterLast('/', path)
            ?.substringAfterLast('.', "")
            ?.trim()
            ?.lowercase()
            .orEmpty()
        // resolver 对文本类常年只报 text/plain：扩展名能指认具体类型时优先扩展名。
        if (mime == "text/plain") {
            LABEL_BY_EXT[ext]?.let { return it }
        }
        mime?.let { LABEL_BY_MIME[it] }?.let { return it }
        if (ext.isEmpty()) return "FILE"
        LABEL_BY_EXT[ext]?.let { return it }
        return ext.uppercase().take(MAX_LABEL_CHARS)
    }

    private const val MAX_LABEL_CHARS = 4

    private val LABEL_BY_MIME = mapOf(
        "application/pdf" to "PDF",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "DOCX",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "XLSX",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "PPTX",
        "text/plain" to "TXT",
        "text/markdown" to "MD",
        "text/csv" to "CSV",
        "application/json" to "JSON",
    )

    private val LABEL_BY_EXT = mapOf(
        "pdf" to "PDF",
        "docx" to "DOCX",
        "docm" to "DOCX",
        "xlsx" to "XLSX",
        "xlsm" to "XLSX",
        "pptx" to "PPTX",
        "txt" to "TXT",
        "log" to "TXT",
        "md" to "MD",
        "markdown" to "MD",
        "csv" to "CSV",
        "json" to "JSON",
    )
}
