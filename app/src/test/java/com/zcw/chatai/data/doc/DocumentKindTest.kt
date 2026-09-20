package com.zcw.chatai.data.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentKindTest {

    @Test
    fun detectsByMimeType() {
        val pdfHead = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)
        assertEquals(
            DocumentKind.PDF,
            DocumentKind.detect("application/pdf", "未知", pdfHead),
        )
        assertEquals(
            DocumentKind.DOCX,
            DocumentKind.detect(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "未知",
                byteArrayOf(1, 2, 3),
            ),
        )
        assertEquals(
            DocumentKind.XLSX,
            DocumentKind.detect(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "未知",
                byteArrayOf(1, 2, 3),
            ),
        )
        assertEquals(
            DocumentKind.PPTX,
            DocumentKind.detect(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "未知",
                byteArrayOf(1, 2, 3),
            ),
        )
    }

    @Test
    fun detectsByExtensionWhenMimeIsMissing() {
        val zipHead = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        assertEquals(DocumentKind.DOCX, DocumentKind.detect(null, "报告.DOCX", zipHead))
        assertEquals(DocumentKind.XLSX, DocumentKind.detect("", "表.xlsx", zipHead))
        assertEquals(DocumentKind.PPTX, DocumentKind.detect(null, "稿.pptx", zipHead))
        assertEquals(DocumentKind.PDF, DocumentKind.detect(null, "扫.pdf", byteArrayOf(1)))
        assertEquals(DocumentKind.TEXT, DocumentKind.detect(null, "笔记.md", byteArrayOf(1)))
        assertEquals(DocumentKind.TEXT, DocumentKind.detect("text/plain", "未知", byteArrayOf(1)))
    }

    @Test
    fun detectsPdfByMagicHeader() {
        val head = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37)
        assertEquals(DocumentKind.PDF, DocumentKind.detect(null, null, head))
    }

    @Test
    fun sniffsExtensionlessText() {
        val head = "你好 world\n第二行".toByteArray(Charsets.UTF_8)
        assertEquals(DocumentKind.TEXT, DocumentKind.detect(null, null, head))
    }

    @Test
    fun rejectsLegacyOfficeWithHelpfulMessage() {
        val ole2 = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(), 0x00)
        val error = assertThrows(DocumentException::class.java) {
            DocumentKind.detect(null, "旧报告.doc", ole2)
        }
        assertEquals(true, error.message?.contains(".docx") == true)
    }

    @Test
    fun rejectsUnknownBinary() {
        val exe = byteArrayOf(0x4D, 0x5A, 0x00, 0x01, 0x02)
        assertThrows(DocumentException::class.java) {
            DocumentKind.detect("application/octet-stream", "程序.exe", exe)
        }
    }
}
