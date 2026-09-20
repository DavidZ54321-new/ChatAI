package com.zcw.chatai.data.media

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentLimitsDocumentsTest {

    @Test
    fun rejectsTooManyDocuments() {
        val docs = (1..AttachmentLimits.MAX_DOCUMENTS + 1).map { document("d$it", 1000) }
        val reason = AttachmentLimits.validate(docs)
        assertTrue(reason?.contains("${AttachmentLimits.MAX_DOCUMENTS}") == true)
        assertNull(AttachmentLimits.validate(docs.take(AttachmentLimits.MAX_DOCUMENTS)))
    }

    @Test
    fun rejectsOversizedDocument() {
        val big = document("big", AttachmentLimits.MAX_DOC_BYTES + 1)
        val reason = AttachmentLimits.validate(listOf(big))
        assertTrue(reason?.contains("20") == true)
    }

    @Test
    fun documentBytesDoNotConsumeImageBudget() {
        val docs = listOf(document("d1", AttachmentLimits.MAX_DOC_BYTES))
        assertNull(AttachmentLimits.validate(docs))
        assertEquals(0L, AttachmentLimits.estimatedRequestBytes(docs))
    }

    @Test
    fun docGateAllowsUnderAndAtLimit() {
        assertNull(AttachmentLimits.validate(listOf(docWithChars("d1", 99_999))))
        assertNull(
            AttachmentLimits.validate(
                listOf(docWithChars("d1", 60_000), docWithChars("d2", 40_000)),
            ),
        )
    }

    @Test
    fun docGateRejectsOverLimit() {
        // 口径是单次发送的附件字数之和，历史消息不计入。
        val single = AttachmentLimits.validate(listOf(docWithChars("d1", 100_001)))
        assertTrue(single?.contains("万字上限") == true)
        val multi = AttachmentLimits.validate(
            listOf(docWithChars("d1", 60_000), docWithChars("d2", 40_001)),
        )
        assertTrue(multi != null)
    }

    @Test
    fun formatCharsReadsNaturally() {
        assertEquals("9999字", AttachmentLimits.formatChars(9999))
        assertEquals("10万字", AttachmentLimits.formatChars(100_000))
        assertEquals("15万字", AttachmentLimits.formatChars(150_000))
    }

    private fun docWithChars(id: String, chars: Long) = document(id, 1000).copy(
        extractedChars = chars,
    )

    private fun document(id: String, sizeBytes: Long) = Attachment(
        id = id,
        kind = AttachmentKind.DOCUMENT,
        relativePath = "attachments/c/$id.pdf",
        mimeType = "application/pdf",
        width = 0,
        height = 0,
        sizeBytes = sizeBytes,
        extractedPath = "attachments/c/$id.txt",
        displayName = "$id.pdf",
    )
}
