package com.zcw.chatai.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentPreviewTest {

    @Test
    fun selectsPdfRendererForPdf() {
        assertEquals(DocumentPreviewMode.PDF, DocumentPreview.mode("report.pdf", null))
        assertEquals(DocumentPreviewMode.PDF, DocumentPreview.mode(null, "application/pdf"))
    }

    @Test
    fun selectsMarkdownRendererForMarkdown() {
        assertEquals(DocumentPreviewMode.MARKDOWN, DocumentPreview.mode("notes.md", "text/plain"))
        assertEquals(DocumentPreviewMode.MARKDOWN, DocumentPreview.mode("notes.markdown", null))
        assertEquals(DocumentPreviewMode.MARKDOWN, DocumentPreview.mode(null, "text/markdown"))
    }

    @Test
    fun keepsWordTextSeparateFromMarkdownTablesAndSlides() {
        assertEquals(DocumentPreviewMode.DOCX, DocumentPreview.mode("report.docx", null))
        assertEquals(DocumentPreviewMode.OFFICE, DocumentPreview.mode("budget.xlsx", null))
        assertEquals(DocumentPreviewMode.OFFICE, DocumentPreview.mode("deck.pptx", null))
    }

    @Test
    fun usesMarkdownRendererForExtractedOfficeText() {
        assertTrue(DocumentPreview.usesMarkdown(DocumentPreviewMode.MARKDOWN))
        assertTrue(DocumentPreview.usesMarkdown(DocumentPreviewMode.DOCX))
        assertTrue(DocumentPreview.usesMarkdown(DocumentPreviewMode.OFFICE))
        assertFalse(DocumentPreview.usesMarkdown(DocumentPreviewMode.TEXT))
        assertFalse(DocumentPreview.usesMarkdown(DocumentPreviewMode.PDF))
    }

    @Test
    fun treatsPlainTextAndLegacyFallbackAsText() {
        assertEquals(DocumentPreviewMode.TEXT, DocumentPreview.mode("readme.txt", "text/plain"))
        assertEquals(DocumentPreviewMode.TEXT, DocumentPreview.mode(null, "text/plain"))
    }

    @Test
    fun closesResourcesPublishedAfterOwnerDisposal() {
        val released = mutableListOf<String>()
        val owner = PreviewResourceOwner<String>(released::add)
        owner.close()

        assertNull(owner.publish("late"))
        assertEquals(listOf("late"), released)
    }

    @Test
    fun replacesAndClosesOwnedResourcesExactlyOnce() {
        val released = mutableListOf<String>()
        val owner = PreviewResourceOwner<String>(released::add)
        val first = "first"
        val second = "second"

        assertSame(first, owner.publish(first))
        assertSame(second, owner.publish(second))
        owner.close()
        owner.close()

        assertEquals(listOf("first", "second"), released)
    }
}
