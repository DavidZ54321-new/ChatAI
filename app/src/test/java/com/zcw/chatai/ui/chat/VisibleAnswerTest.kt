package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleAnswerTest {

    private fun assistant(
        content: String = "",
        reasoning: String? = "思维链",
        status: MessageStatus = MessageStatus.COMPLETE,
        hasToolCalls: Boolean = false,
    ) = ChatMessageItem(
        id = "a1",
        role = Role.ASSISTANT,
        content = content,
        reasoning = reasoning,
        status = status,
        hasToolCalls = hasToolCalls,
    )

    @Test
    fun completedReasoningOnlyTurnShowsTheThought() {
        assertEquals("思维链", assistant().visibleAnswer())
    }

    @Test
    fun streamingDoesNotPromoteReasoning() {
        assertEquals("", assistant().visibleAnswer(streaming = true))
    }

    @Test
    fun cancelledTurnDoesNotPromoteReasoning() {
        assertEquals("", assistant(status = MessageStatus.CANCELLED).visibleAnswer())
    }

    @Test
    fun failedTurnDoesNotPromoteReasoning() {
        assertEquals("", assistant(status = MessageStatus.ERROR).visibleAnswer())
    }

    @Test
    fun cancelledPartialAnswerKeepsOnlyContent() {
        assertEquals(
            "半句",
            assistant(content = "半句", status = MessageStatus.CANCELLED).visibleAnswer(),
        )
    }

    @Test
    fun toolCallTurnDoesNotPromoteReasoning() {
        assertEquals("", assistant(hasToolCalls = true).visibleAnswer())
    }
}
