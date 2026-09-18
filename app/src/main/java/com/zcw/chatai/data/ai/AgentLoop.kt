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

        /**
         * 模型声称要调工具，但没有可执行的调用（空列表 / 工具名缺失被丢弃）。
         *
         * 这是协议异常，不是正常结束：不能再当 `Finish`，否则会留下一个**空白气泡且没有任何提示**。
         * 交给上层标记为可见的失败。
         */
        data object Malformed : Decision
    }

    fun decide(
        finishReason: String?,
        toolCalls: List<ToolCall>,
        steps: Int,
        maxSteps: Int,
    ): Decision {
        if (finishReason != "tool_calls") return Decision.Finish
        if (toolCalls.isEmpty()) return Decision.Malformed
        return if (steps >= maxSteps) Decision.ForceFinal else Decision.Continue(toolCalls)
    }
}
