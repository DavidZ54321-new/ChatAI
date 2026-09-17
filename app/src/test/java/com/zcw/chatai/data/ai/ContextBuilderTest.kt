package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.net.ChatRequestImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {

    private val image = ChatRequestImage(dataUrl = "data:image/jpeg;base64,AAAA")

    private fun imageProvider(attachment: Attachment): ChatRequestImage? =
        if (attachment.relativePath.endsWith("missing.jpg")) null else image

    @Test
    fun keepsOnlyRecentMessages() {
        val history = (1..60).map { message("m$it", Role.USER, "内容$it") }
        val built = ContextBuilder.build(history, imageLimit = 2, imageProvider = { image })
        assertEquals(ContextBuilder.MAX_MESSAGES, built.size)
        assertEquals("内容21", built.first().content)
        assertEquals("内容60", built.last().content)
    }

    @Test
    fun dropsStreamingPlaceholdersAndEmptyMessages() {
        val history = listOf(
            message("u1", Role.USER, "有内容"),
            message("a1", Role.ASSISTANT, "", status = MessageStatus.STREAMING),
            message("a2", Role.ASSISTANT, ""),
            message("u2", Role.SYSTEM, "系统消息"),
        )
        val built = ContextBuilder.build(history, imageLimit = 2, imageProvider = { image })
        assertEquals(1, built.size)
        assertEquals("有内容", built.single().content)
        assertEquals("user", built.single().role)
    }

    @Test
    fun demotesOlderImagesToPlaceholderInOutgoingText() {
        val history = listOf(
            message("u1", Role.USER, "第一张图", attachments = listOf(attachment("a1", "attachments/c/a1.jpg"))),
            message("a1", Role.ASSISTANT, "看到了"),
            message("u2", Role.USER, "第二张图", attachments = listOf(attachment("a2", "attachments/c/a2.jpg"))),
            message("a2", Role.ASSISTANT, "嗯"),
            message("u3", Role.USER, "第三张图", attachments = listOf(attachment("a3", "attachments/c/a3.jpg"))),
        )
        val built = ContextBuilder.build(history, imageLimit = 2, imageProvider = ::imageProvider)

        val first = built.first()
        assertTrue(first.images.isEmpty())
        assertTrue(first.content, first.content.contains(ContextBuilder.IMAGE_OMITTED))
        assertTrue(first.content.startsWith("第一张图"))

        assertEquals(1, built[2].images.size)
        assertEquals(1, built[4].images.size)
    }

    @Test
    fun imageLimitZeroSendsNoHistoryImages() {
        val history = listOf(
            message("u1", Role.USER, "图", attachments = listOf(attachment("a1", "attachments/c/a1.jpg"))),
        )
        val built = ContextBuilder.build(history, imageLimit = 0, imageProvider = ::imageProvider)
        assertTrue(built.single().images.isEmpty())
        assertTrue(built.single().content.contains(ContextBuilder.IMAGE_OMITTED))
    }

    @Test
    fun imageLimitMinusOneSendsEverything() {
        val history = listOf(
            message("u1", Role.USER, "图1", attachments = listOf(attachment("a1", "attachments/c/a1.jpg"))),
            message("u2", Role.USER, "图2", attachments = listOf(attachment("a2", "attachments/c/a2.jpg"))),
        )
        val built = ContextBuilder.build(history, imageLimit = -1, imageProvider = ::imageProvider)
        assertTrue(built.all { it.images.size == 1 })
        assertFalse(built.any { it.content.contains(ContextBuilder.IMAGE_OMITTED) })
    }

    @Test
    fun missingFilesBecomeUnavailablePlaceholder() {
        val history = listOf(
            message("u1", Role.USER, "图丢了", attachments = listOf(attachment("a1", "attachments/c/missing.jpg"))),
        )
        val built = ContextBuilder.build(history, imageLimit = 2, imageProvider = ::imageProvider)
        assertTrue(built.single().images.isEmpty())
        assertTrue(built.single().content.contains(ContextBuilder.IMAGE_MISSING))
    }

    @Test
    fun textWithoutImagesKeepsPlainStringContent() {
        val built = ContextBuilder.build(
            listOf(message("u1", Role.USER, "纯文本")),
            imageLimit = 2,
            imageProvider = ::imageProvider,
        )
        assertTrue(built.single().images.isEmpty())
        assertEquals("纯文本", built.single().content)
    }

    @Test
    fun keepsToolMessagesAndCarriesToolCallId() {
        val calls = listOf(ToolCall("call_1", "web_search", "{\"query\":\"x\"}"))
        val messages = listOf(
            message(id = "a1", role = Role.ASSISTANT, content = "", toolCalls = calls, reasoningContent = "想"),
            message(id = "t1", role = Role.TOOL, content = "结果", toolCallId = "call_1"),
        )
        val built = ContextBuilder.build(messages, imageLimit = 0) { null }
        assertEquals(listOf("assistant", "tool"), built.map { it.role })
        assertEquals(calls, built[0].toolCalls)
        assertEquals("想", built[0].reasoning)
        assertEquals("call_1", built[1].toolCallId)
    }

    @Test
    fun plainAssistantDoesNotEchoReasoning() {
        val messages = listOf(message(id = "a1", role = Role.ASSISTANT, content = "答案", reasoningContent = "想"))
        val built = ContextBuilder.build(messages, imageLimit = 0) { null }
        assertNull(built.single().reasoning)
    }

    private fun attachment(id: String, path: String) = Attachment(
        id = id,
        kind = AttachmentKind.IMAGE,
        relativePath = path,
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        sizeBytes = 1000,
    )

    private fun message(
        id: String,
        role: Role,
        content: String,
        attachments: List<Attachment> = emptyList(),
        status: MessageStatus = MessageStatus.COMPLETE,
        toolCalls: List<ToolCall> = emptyList(),
        toolCallId: String? = null,
        reasoningContent: String? = null,
    ) = Message(
        id = id,
        conversationId = "c1",
        role = role,
        content = content,
        status = status,
        errorMessage = null,
        reasoningContent = reasoningContent,
        seq = id.hashCode().toLong(),
        model = "deepseek-flash",
        promptTokens = null,
        completionTokens = null,
        attachments = attachments,
        toolCalls = toolCalls,
        toolCallId = toolCallId,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
