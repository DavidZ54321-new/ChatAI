package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolStatus
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

    @Test
    fun dropsLeadingOrphanToolMessageAfterWindowTruncation() {
        val history = buildList {
            add(
                message(
                    id = "a0",
                    role = Role.ASSISTANT,
                    content = "",
                    toolCalls = listOf(ToolCall("call_1", "web_search", "{}")),
                ),
            )
            add(message(id = "t0", role = Role.TOOL, content = "结果", toolCallId = "call_1"))
            repeat(39) { add(message(id = "u$it", role = Role.USER, content = "内容$it")) }
        }
        val built = ContextBuilder.build(history, imageLimit = 0) { null }
        assertEquals(ContextBuilder.MAX_MESSAGES - 1, built.size)
        assertFalse(built.first().role == "tool")
        assertTrue(built.none { it.role == "tool" })
    }

    @Test
    fun toolResultBlankTextFallsBackToNonBlankContent() {
        val messages = listOf(
            message(
                id = "a1",
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall("call_1", "web_search", "{}")),
            ),
            message(
                id = "t1",
                role = Role.TOOL,
                content = "真实结果",
                toolCallId = "call_1",
                toolResult = ToolResult(status = ToolStatus.OK, detail = "d", text = ""),
            ),
        )
        val built = ContextBuilder.build(messages, imageLimit = 0) { null }
        assertEquals(2, built.size)
        assertEquals("真实结果", built[1].content)
    }

    @Test
    fun blankToolResultIsKeptWithPlaceholderToPreservePairing() {
        val messages = listOf(
            message(
                id = "a1",
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall("call_1", "web_search", "{}")),
            ),
            message(
                id = "t1",
                role = Role.TOOL,
                content = "",
                toolCallId = "call_1",
                toolResult = ToolResult(status = ToolStatus.OK, detail = "d", text = ""),
            ),
        )
        val built = ContextBuilder.build(messages, imageLimit = 0) { null }
        assertEquals(listOf("assistant", "tool"), built.map { it.role })
        assertEquals("call_1", built[1].toolCallId)
        assertTrue(built[1].content.isNotBlank())
        assertEquals(ContextBuilder.TOOL_EMPTY, built[1].content)
    }

    @Test
    fun toolAnswersArePulledUpRightAfterTheirAssistantWhenRowsAreOutOfOrder() {
        val messages = listOf(
            message(
                id = "a1",
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall("call_1", "web_search", "{}"),
                    ToolCall("call_2", "web_search", "{}"),
                ),
            ),
            message(id = "t1", role = Role.TOOL, content = "结果1", toolCallId = "call_1"),
            message(id = "u1", role = Role.USER, content = "你继续"),
            message(id = "t2", role = Role.TOOL, content = "结果2", toolCallId = "call_2"),
        )
        val built = ContextBuilder.build(messages, imageLimit = 0) { null }
        assertEquals(listOf("assistant", "tool", "tool", "user"), built.map { it.role })
        assertEquals(listOf("call_1", "call_2"), built.filter { it.role == "tool" }.map { it.toolCallId })
        assertEquals("结果2", built[2].content)
        assertEquals("你继续", built[3].content)
    }

    @Test
    fun unansweredToolCallGetsSynthesizedPlaceholderRightAfterTheAssistant() {
        val messages = listOf(
            message(
                id = "a1",
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall("call_1", "web_search", "{}"),
                    ToolCall("call_2", "web_search", "{}"),
                ),
            ),
            message(id = "t1", role = Role.TOOL, content = "结果1", toolCallId = "call_1"),
            message(id = "u1", role = Role.USER, content = "你继续"),
        )
        val built = ContextBuilder.build(messages, imageLimit = 0) { null }
        assertEquals(listOf("assistant", "tool", "tool", "user"), built.map { it.role })
        assertEquals("call_2", built[2].toolCallId)
        assertEquals(ContextBuilder.TOOL_EMPTY, built[2].content)
    }

    @Test
    fun orphanToolMessageInTheMiddleIsDropped() {
        val messages = listOf(
            message(id = "u1", role = Role.USER, content = "你好"),
            message(id = "t1", role = Role.TOOL, content = "孤儿", toolCallId = "call_x"),
            message(id = "a1", role = Role.ASSISTANT, content = "回答"),
        )
        val built = ContextBuilder.build(messages, imageLimit = 0) { null }
        assertEquals(listOf("user", "assistant"), built.map { it.role })
    }

    @Test
    fun blankReasoningOnToolCallAssistantIsNotForwarded() {
        val messages = listOf(
            message(
                id = "a1",
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall("call_1", "web_search", "{}")),
                reasoningContent = "",
            ),
        )
        val built = ContextBuilder.build(messages, imageLimit = 0) { null }
        // 缺应答的 tool_call 会合成占位应答，但 assistant 本体的空思考不回传。
        assertEquals(listOf("assistant", "tool"), built.map { it.role })
        assertNull(built.first().reasoning)
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
        toolResult: ToolResult? = null,
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
        toolResult = toolResult,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
