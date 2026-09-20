package com.zcw.chatai.data.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentLabelTest {

    @Test
    fun prefersMimeType() {
        assertEquals("PDF", DocumentLabel.of("application/pdf", "a.docx"))
        assertEquals(
            "DOCX",
            DocumentLabel.of(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "a.pdf",
            ),
        )
        assertEquals("MD", DocumentLabel.of("text/markdown", null))
    }

    @Test
    fun fallsBackToExtension() {
        assertEquals("PDF", DocumentLabel.of(null, "attachments/c/x.pdf"))
        assertEquals("XLSX", DocumentLabel.of("", "表.XLSX"))
        assertEquals("PPTX", DocumentLabel.of(null, "稿.pptx"))
        assertEquals("TXT", DocumentLabel.of(null, "读我.txt"))
        assertEquals("CSV", DocumentLabel.of(null, "数.csv"))
    }

    @Test
    fun genericTextMimeDefersToExtension() {
        // resolver 对文本类常年只报 text/plain：扩展名能指认时优先扩展名。
        assertEquals("MD", DocumentLabel.of("text/plain", "notes.md"))
        assertEquals("CSV", DocumentLabel.of("text/plain", "数.csv"))
        assertEquals("TXT", DocumentLabel.of("text/plain", "读我.txt"))
        assertEquals("TXT", DocumentLabel.of("text/plain", null))
    }

    @Test
    fun capsUnknownExtensionsAtFourChars() {
        assertEquals("FILE", DocumentLabel.of(null, null))
        assertEquals("FILE", DocumentLabel.of(null, "无后缀名"))
        assertEquals("EPUB", DocumentLabel.of(null, "书.epub"))
    }
}
