package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.MessageStatus

/**
 * 助手回合给人看、也给出站用的正文。
 *
 * 只有正常结束、没有工具调用、正文又是空的，思考才是全部结果（音频识别稿只落在思考里）。
 * 带 tool_calls 的空正文保持为空。停止、失败、流式中的半截思维链不是回答。
 */
object AssistantVisible {

    fun text(
        content: String,
        reasoning: String?,
        hasToolCalls: Boolean = false,
        status: MessageStatus = MessageStatus.COMPLETE,
    ): String {
        if (status != MessageStatus.COMPLETE || content.isNotBlank() || hasToolCalls) return content
        return reasoning?.takeIf { it.isNotBlank() } ?: content
    }
}
