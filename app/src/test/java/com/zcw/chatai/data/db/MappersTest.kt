package com.zcw.chatai.data.db

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolSource
import com.zcw.chatai.data.model.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappersTest {

    private val conversation = Conversation(
        id = "conv-1",
        title = "示例对话",
        model = "deepseek-chat",
        systemPrompt = "you are helpful",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
        lastMessagePreview = "hello there",
        messageCount = 3,
        isPinned = true,
    )

    private val message = Message(
        id = "msg-1",
        conversationId = "conv-1",
        role = Role.ASSISTANT,
        content = "hello",
        status = MessageStatus.STREAMING,
        errorMessage = "boom",
        reasoningContent = "thinking",
        seq = 7L,
        model = "deepseek-reasoner",
        promptTokens = 11,
        completionTokens = 22,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
    )

    @Test
    fun conversationRoundTripIsStable() {
        assertEquals(conversation, conversation.toEntity().toModel())
    }

    @Test
    fun messageRoundTripIsStable() {
        assertEquals(message, message.toEntity().toModel())
    }

    @Test
    fun roundTripKeepsNullableFieldsNullable() {
        val sparse = message.copy(
            errorMessage = null,
            reasoningContent = null,
            model = null,
            promptTokens = null,
            completionTokens = null,
        )
        val restored = sparse.toEntity().toModel()
        assertEquals(sparse, restored)
        assertNull(restored.errorMessage)
        assertNull(restored.reasoningContent)
        assertNull(restored.model)
        assertNull(restored.promptTokens)
        assertNull(restored.completionTokens)

        val noSystemPrompt = conversation.copy(systemPrompt = null)
        assertEquals(noSystemPrompt, noSystemPrompt.toEntity().toModel())
    }

    @Test
    fun enumNamesArePersisted() {
        assertEquals("ASSISTANT", message.toEntity().role)
        assertEquals("STREAMING", message.toEntity().status)
    }

    @Test
    fun unknownRoleFallsBackToSystem() {
        assertEquals(Role.SYSTEM, message.toEntity().copy(role = "WIZARD").toModel().role)
        assertEquals(Role.SYSTEM, message.toEntity().copy(role = "assistant").toModel().role)
        assertEquals(Role.SYSTEM, message.toEntity().copy(role = "").toModel().role)
    }

    @Test
    fun unknownStatusFallsBackToComplete() {
        assertEquals(
            MessageStatus.COMPLETE,
            message.toEntity().copy(status = "PARTIAL").toModel().status,
        )
        assertEquals(
            MessageStatus.COMPLETE,
            message.toEntity().copy(status = "streaming").toModel().status,
        )
        assertEquals(
            MessageStatus.COMPLETE,
            message.toEntity().copy(status = "").toModel().status,
        )
    }

    @Test
    fun messageWithAttachmentsAndUsageRoundTrips() {
        val withExtras = message.copy(
            attachments = listOf(
                Attachment(
                    id = "att-1",
                    kind = AttachmentKind.IMAGE,
                    relativePath = "attachments/conv-1/att-1.jpg",
                    mimeType = "image/jpeg",
                    width = 1200,
                    height = 900,
                    sizeBytes = 34567,
                ),
            ),
            reasoningTokens = 24,
            cachedTokens = 7,
            reasoningMs = 9_400L,
        )
        val restored = withExtras.toEntity().toModel()
        assertEquals(withExtras, restored)
        assertEquals(24, restored.reasoningTokens)
        assertEquals(7, restored.cachedTokens)
        assertEquals(9_400L, restored.reasoningMs)
        assertEquals("attachments/conv-1/att-1.jpg", restored.attachments.single().relativePath)
    }

    @Test
    fun reasoningDurationIsNullWhenNotMeasured() {
        assertNull(message.toEntity().reasoningMs)
        assertNull(message.toEntity().toModel().reasoningMs)
    }

    @Test
    fun messageWithoutAttachmentsStoresNullColumn() {
        assertNull(message.toEntity().attachments)
        assertEquals(emptyList<Attachment>(), message.toEntity().toModel().attachments)
    }

    @Test
    fun corruptedAttachmentJsonDegradesToEmptyList() {
        val broken = message.toEntity().copy(attachments = "not json at all")
        assertEquals(emptyList<Attachment>(), broken.toModel().attachments)
    }

    @Test
    fun knownRoleAndStatusAreParsed() {
        assertEquals(Role.USER, message.toEntity().copy(role = "USER").toModel().role)
        assertEquals(Role.SYSTEM, message.toEntity().copy(role = "SYSTEM").toModel().role)
        assertEquals(MessageStatus.ERROR, message.toEntity().copy(status = "ERROR").toModel().status)
        assertEquals(
            MessageStatus.CANCELLED,
            message.toEntity().copy(status = "CANCELLED").toModel().status,
        )
    }

    @Test
    fun roundTripsToolFields() {
        val message = Message(
            id = "m1",
            conversationId = "c1",
            role = Role.TOOL,
            content = "result text",
            status = MessageStatus.COMPLETE,
            errorMessage = null,
            reasoningContent = null,
            seq = 3,
            model = null,
            promptTokens = null,
            completionTokens = null,
            toolCalls = listOf(ToolCall("call_1", "web_search", "{\"query\":\"x\"}")),
            toolCallId = "call_1",
            toolResult = ToolResult(
                status = ToolStatus.OK,
                detail = "q",
                sources = listOf(ToolSource("https://a", "A")),
                text = "result text",
            ),
            createdAt = 1,
            updatedAt = 1,
        )
        val entity = message.toEntity()
        assertEquals(
            listOf(ToolCall("call_1", "web_search", "{\"query\":\"x\"}")),
            entity.toModel().toolCalls,
        )
        assertEquals("call_1", entity.toModel().toolCallId)
        assertEquals(message, entity.toModel())
    }

    @Test
    fun messageWithoutToolFieldsStoresNullColumns() {
        val entity = message.toEntity()
        assertNull(entity.toolCalls)
        assertNull(entity.toolCallId)
        assertNull(entity.toolResult)

        val restored = entity.toModel()
        assertEquals(emptyList<ToolCall>(), restored.toolCalls)
        assertNull(restored.toolCallId)
        assertNull(restored.toolResult)
    }

    @Test
    fun corruptedToolJsonDegradesToEmptyAndNull() {
        val broken = message.toEntity().copy(
            toolCalls = "not json at all",
            toolResult = "{",
        )
        val restored = broken.toModel()
        assertEquals(emptyList<ToolCall>(), restored.toolCalls)
        assertNull(restored.toolResult)
    }

    @Test
    fun roundTripsWebSearchEnabledConversation() {
        val conversation = Conversation(
            id = "c1", title = "t", model = "m", systemPrompt = null,
            createdAt = 1, updatedAt = 1, lastMessagePreview = "", messageCount = 0,
            isPinned = false, webSearchEnabled = true,
        )
        assertEquals(conversation, conversation.toEntity().toModel())
    }

    @Test
    fun roundTripsConversationBoundProvider() {
        val bound = conversation.copy(providerId = "qwen")
        assertEquals(bound, bound.toEntity().toModel())
        assertEquals("", conversation.toEntity().providerId)
    }

    @Test
    fun roundTripsConversationBoundPersona() {
        val bound = conversation.copy(personaId = "translator")
        assertEquals(bound, bound.toEntity().toModel())
        assertEquals("", conversation.toEntity().personaId)
    }
}
