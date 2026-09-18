package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentLoopTest {

    private val call = ToolCall("c1", "web_search", "{}")

    @Test
    fun finishesWhenNoToolCalls() {
        assertEquals(AgentLoop.Decision.Finish, AgentLoop.decide("stop", emptyList(), 0, 5))
        assertEquals(AgentLoop.Decision.Finish, AgentLoop.decide(null, emptyList(), 0, 5))
    }

    @Test
    fun continuesWhenToolCallsAndBudgetLeft() {
        assertEquals(
            AgentLoop.Decision.Continue(listOf(call)),
            AgentLoop.decide("tool_calls", listOf(call), steps = 0, maxSteps = 5),
        )
    }

    @Test
    fun forcesFinalWhenBudgetExhausted() {
        assertEquals(AgentLoop.Decision.ForceFinal, AgentLoop.decide("tool_calls", listOf(call), 5, 5))
        assertEquals(AgentLoop.Decision.ForceFinal, AgentLoop.decide("tool_calls", listOf(call), 6, 5))
    }

    @Test
    fun ignoresToolCallsWithoutFinishReason() {
        assertEquals(AgentLoop.Decision.Finish, AgentLoop.decide("stop", listOf(call), 0, 5))
    }
}
