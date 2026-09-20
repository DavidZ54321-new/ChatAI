package com.zcw.chatai.data.db

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentCodecDocumentsTest {

    @Test
    fun roundTripsDocumentAttachment() {
        val attachments = listOf(
            Attachment(
                id = "d1",
                kind = AttachmentKind.DOCUMENT,
                relativePath = "attachments/c/d1.pdf",
                mimeType = "application/pdf",
                width = 0,
                height = 0,
                sizeBytes = 12_345,
                extractedPath = "attachments/c/d1.txt",
                extractedMeta = "共 8 页",
                extractedChars = 12_345,
                displayName = "report.pdf",
            ),
        )
        val decoded = AttachmentCodec.decode(AttachmentCodec.encode(attachments))
        assertEquals(attachments, decoded)
    }

    /** 回归：旧版本写下的 JSON（没有新字段）必须照常解出，新字段为 null。 */
    @Test
    fun decodesLegacyJsonWithoutDocumentFields() {
        val legacy = """[{"id":"d1","kind":"DOCUMENT","path":"attachments/c/d1.pdf","mime":"application/pdf","width":0,"height":0,"size":100}]"""
        val decoded = AttachmentCodec.decode(legacy)
        assertEquals(1, decoded.size)
        assertEquals(AttachmentKind.DOCUMENT, decoded.single().kind)
        assertNull(decoded.single().extractedPath)
        assertNull(decoded.single().extractedMeta)
        assertEquals(0, decoded.single().extractedChars)
        assertNull(decoded.single().displayName)
    }
}
