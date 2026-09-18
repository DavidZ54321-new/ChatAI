package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentRetentionTest {

    @Test
    fun limitZeroKeepsNothing() {
        val history = listOf(withAttachment("u1"), withAttachment("u2"))
        assertEquals(emptySet<String>(), AttachmentRetention.keptMessageIds(history, 0))
    }

    @Test
    fun negativeLimitKeepsAllAttachmentMessages() {
        val history = listOf(withAttachment("u1"), plain("a1"), withAttachment("u2"))
        assertEquals(setOf("u1", "u2"), AttachmentRetention.keptMessageIds(history, -1))
    }

    @Test
    fun onlyRecentNWithAttachmentsAreKept() {
        val history = listOf(
            withAttachment("u1"),
            plain("a1"),
            withAttachment("u2"),
            plain("a2"),
            withAttachment("u3"),
        )
        assertEquals(setOf("u2", "u3"), AttachmentRetention.keptMessageIds(history, 2))
    }

    @Test
    fun messagesWithoutAttachmentsDoNotConsumeTheBudget() {
        val history = listOf(
            withAttachment("u1"),
            plain("a1"), plain("a2"), plain("a3"),
            withAttachment("u2"),
        )
        assertEquals(setOf("u1", "u2"), AttachmentRetention.keptMessageIds(history, 2))
    }

    private fun withAttachment(id: String) = message(id, listOf(attachment(id)))

    private fun plain(id: String) = message(id, emptyList())

    private fun attachment(id: String) = Attachment(
        id = id,
        kind = AttachmentKind.IMAGE,
        relativePath = "attachments/c/$id.jpg",
        mimeType = "image/jpeg",
        width = 10,
        height = 10,
        sizeBytes = 100,
    )

    private fun message(id: String, attachments: List<Attachment>) = Message(
        id = id,
        conversationId = "c1",
        role = Role.USER,
        content = "内容",
        status = MessageStatus.COMPLETE,
        errorMessage = null,
        reasoningContent = null,
        seq = id.hashCode().toLong(),
        model = null,
        promptTokens = null,
        completionTokens = null,
        attachments = attachments,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
