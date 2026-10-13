package com.zcw.chatai.data.ai

/**
 * 助手回合给人看、也给出站用的正文。
 * 没有工具调用、正文又是空的，思考就是全部结果。带 tool_calls 的空正文保持为空。
 */
object AssistantVisible {

    fun text(content: String, reasoning: String?, hasToolCalls: Boolean = false): String {
        if (content.isNotBlank() || hasToolCalls) return content
        return reasoning?.takeIf { it.isNotBlank() } ?: content
    }
}
