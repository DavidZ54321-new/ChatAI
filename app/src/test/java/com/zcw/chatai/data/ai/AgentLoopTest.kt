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

    /** 回归：`tool_calls` 却没有可执行的调用是协议异常，不能当成正常结束（会留空白气泡）。 */
    @Test
    fun malformedWhenToolCallsFinishedButNothingAssembled() {
        assertEquals(AgentLoop.Decision.Malformed, AgentLoop.decide("tool_calls", emptyList(), 0, 5))
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
