package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolImageInventoryTest {

    @Test
    fun numbersVisibleImagesChronologicallyAndLabelsTheirTurn() {
        val history = listOf(
            userWith("u1", "a1"),
            assistant("a1"),
            userWith("u2", "a2", "a3"),
        )
        val images = ToolImageInventory.visibleImages(history, retainTurns = 1)
        assertEquals(listOf(1, 2, 3), images.map { it.globalIndex })
        assertEquals(
            listOf(
                "[Image 1 | previous turn 1/1]",
                "[Image 2 | this turn 1/2]",
                "[Image 3 | this turn 2/2]",
            ),
            images.map { ToolImageInventory.label(it) },
        )
        assertEquals(listOf("a1", "a2", "a3"), images.map { it.attachment.id })
    }

    @Test
    fun zeroTurnsExposesOnlyTheCurrentTurnImages() {
        val history = listOf(
            userWith("u1", "a1"),
            assistant("a1"),
            userWith("u2", "a2"),
        )
        val images = ToolImageInventory.visibleImages(history, retainTurns = 0)
        assertEquals(listOf("a2"), images.map { it.attachment.id })
        assertEquals("[Image 1 | this turn 1/1]", ToolImageInventory.label(images.single()))
    }

    @Test
    fun turnLabelCountsUserMessagesNotAttachmentMessages() {
        val history = listOf(
            userWith("u1", "a1"),
            assistant("a1"),
            userText("u2"), // 中间一轮纯文本，不应把 u1 说成上一轮
            assistant("a2"),
            userWith("u3", "a3"),
        )
        val images = ToolImageInventory.visibleImages(history, retainTurns = -1)
        assertEquals("[Image 1 | 2 turns back 1/1]", ToolImageInventory.label(images[0]))
        assertEquals("[Image 2 | this turn 1/1]", ToolImageInventory.label(images[1]))
    }

    private fun userWith(id: String, vararg attachmentIds: String) =
        user(id, attachmentIds.map { attachment(it) })

    private fun userText(id: String) = user(id, emptyList())

    private fun user(id: String, attachments: List<Attachment>) = message(id, Role.USER, attachments)

    private fun assistant(id: String) = message(id, Role.ASSISTANT, emptyList())

    private fun message(id: String, role: Role, attachments: List<Attachment>) = Message(
        id = id,
        conversationId = "c1",
        role = role,
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

    private fun attachment(id: String) = Attachment(
        id = id,
        kind = AttachmentKind.IMAGE,
        relativePath = "attachments/c/$id.jpg",
        mimeType = "image/jpeg",
        width = 10,
        height = 10,
        sizeBytes = 100,
    )
}
