package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantVisibleTest {

    @Test
    fun completedBlankContentUsesReasoning() {
        assertEquals("想", AssistantVisible.text("", "想"))
    }

    @Test
    fun unfinishedBlankContentStaysBlank() {
        assertEquals("", AssistantVisible.text("", "想", status = MessageStatus.CANCELLED))
        assertEquals("", AssistantVisible.text("", "想", status = MessageStatus.ERROR))
        assertEquals("", AssistantVisible.text("", "想", status = MessageStatus.STREAMING))
    }

    @Test
    fun existingContentWins() {
        assertEquals("答案", AssistantVisible.text("答案", "想"))
    }

    @Test
    fun toolCallsKeepBlankContent() {
        assertEquals("", AssistantVisible.text("", "想", hasToolCalls = true))
    }

    @Test
    fun blankReasoningStaysBlank() {
        assertEquals("", AssistantVisible.text("", "  "))
        assertEquals("", AssistantVisible.text("", null))
    }
}
