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
        val images = ToolImageInventory.visibleImages(history, retainTurns = 1, ToolImageInventory.turnNumbers(history))
        assertEquals(listOf(1, 2, 3), images.map { it.globalIndex })
        assertEquals(
            listOf(
                "[Image 1 | turn 1 1/1]",
                "[Image 2 | turn 2 1/2]",
                "[Image 3 | turn 2 2/2]",
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
        val images = ToolImageInventory.visibleImages(history, retainTurns = 0, ToolImageInventory.turnNumbers(history))
        assertEquals(listOf("a2"), images.map { it.attachment.id })
        assertEquals("[Image 1 | turn 2 1/1]", ToolImageInventory.label(images.single()))
    }

    @Test
    fun turnNumberCountsUserMessagesNotAttachmentMessages() {
        val history = listOf(
            userWith("u1", "a1"),
            assistant("a1"),
            userText("u2"), // 中间一轮纯文本，u1 仍是第 1 轮（绝对轮次不断号重排）
            assistant("a2"),
            userWith("u3", "a3"),
        )
        val images = ToolImageInventory.visibleImages(history, retainTurns = -1, ToolImageInventory.turnNumbers(history))
        assertEquals("[Image 1 | turn 1 1/1]", ToolImageInventory.label(images[0]))
        assertEquals("[Image 2 | turn 3 1/1]", ToolImageInventory.label(images[1]))
    }

    @Test
    fun appendingNewTurnsKeepsExistingLabelsStable() {
        // KV 前缀缓存要求：新消息只追加，历史标注的字节必须原样保留。
        val before = listOf(
            userWith("u1", "a1"),
            assistant("a1"),
            userWith("u2", "a2"),
        )
        val beforeLabels = ToolImageInventory.visibleImages(before, retainTurns = -1, ToolImageInventory.turnNumbers(before))
            .map { ToolImageInventory.label(it) }
        val after = before + listOf(
            assistant("a2"),
            userText("u3"),
            assistant("a3"),
            userWith("u4", "a3"),
        )
        val afterLabels = ToolImageInventory.visibleImages(after, retainTurns = -1, ToolImageInventory.turnNumbers(after))
            .map { ToolImageInventory.label(it) }
        assertEquals(beforeLabels, afterLabels.take(beforeLabels.size))
        assertEquals("[Image 1 | turn 1 1/1]", afterLabels[0])
        assertEquals("[Image 2 | turn 2 1/1]", afterLabels[1])
        assertEquals("[Image 3 | turn 4 1/1]", afterLabels[2])
    }

    @Test
    fun windowedViewKeepsAbsoluteTurnsFromFullHistory() {
        // 生产模式：轮次表按全量历史算，可见清单按出站窗口取——
        // 旧图被截掉时幸存者保留绝对轮次（不重排为 turn 1），且与 build 口径一致。
        val full = listOf(
            userWith("u1", "a1"),
            assistant("a1"),
            userText("u2"),
            assistant("a2"),
            userWith("u3", "a2"),
            assistant("a3"),
        )
        val turns = ToolImageInventory.turnNumbers(full)
        val window = full.takeLast(3)
        val images = ToolImageInventory.visibleImages(window, retainTurns = -1, turns)
        assertEquals(1, images.size)
        assertEquals("a2", images.single().attachment.id)
        assertEquals("[Image 1 | turn 3 1/1]", ToolImageInventory.label(images.single()))
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
