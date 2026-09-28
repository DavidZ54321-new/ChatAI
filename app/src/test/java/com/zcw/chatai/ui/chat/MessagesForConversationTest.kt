package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesForConversationTest {

    @Test
    fun keepsMessagesThatBelongToTheOpenConversation() {
        val owned = messagesFor("b", listOf(message("b", "1"), message("b", "2")))
        assertEquals(listOf("1", "2"), owned.map { it.id })
    }

    @Test
    fun dropsThePreviousConversationWhileTheIdHasAlreadyChanged() {
        assertTrue(messagesFor("b", listOf(message("a", "1"), message("a", "2"))).isEmpty())
    }

    @Test
    fun blankConversationHasNoMessages() {
        assertTrue(messagesFor(null, listOf(message("a", "1"))).isEmpty())
        assertTrue(messagesFor("", listOf(message("a", "1"))).isEmpty())
    }

    private fun message(conversationId: String, id: String) = Message(
        id = id,
        conversationId = conversationId,
        role = Role.USER,
        content = "",
        status = MessageStatus.COMPLETE,
        errorMessage = null,
        reasoningContent = null,
        seq = 0,
        model = null,
        promptTokens = null,
        completionTokens = null,
        createdAt = 0,
        updatedAt = 0,
    )
}
