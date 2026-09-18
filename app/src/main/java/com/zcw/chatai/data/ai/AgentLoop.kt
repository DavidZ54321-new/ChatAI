package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.ToolCall

/** Agent 循环的纯决策：继续调工具 / 强制收尾 / 结束。JVM 单测覆盖。 */
object AgentLoop {

    const val DEFAULT_MAX_STEPS = 5
    const val HARD_MAX_STEPS = 8

    sealed interface Decision {
        data class Continue(val toolCalls: List<ToolCall>) : Decision

        /** 预算耗尽但模型还想调工具：先执行完这批（保持历史合法），再强制不带工具收尾。 */
        data object ForceFinal : Decision

        data object Finish : Decision
    }

    fun decide(
        finishReason: String?,
        toolCalls: List<ToolCall>,
        steps: Int,
        maxSteps: Int,
    ): Decision {
        if (finishReason != "tool_calls" || toolCalls.isEmpty()) return Decision.Finish
        return if (steps >= maxSteps) Decision.ForceFinal else Decision.Continue(toolCalls)
    }
}
