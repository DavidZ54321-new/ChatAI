package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.Role

/**
 * 对话分支的复制规则（纯函数，JVM 单测覆盖）。
 *
 * 用户从某条 AI 回复点「创建分支」时，把**该条及之前**的消息复制成一个新会话。
 * 复制出来的历史必须自己就是合法的：服务端要求每个 `assistant(tool_calls)` 都有配对的
 * `role=tool` 应答，而截断点可能正好落在一次工具调用的中间。
 */
object BranchCopy {

    /**
     * 取 [upToSeq]（含）之前的可复制前缀，顺序保持、`seq` 重排为 `1..N`。
     *
     * 1. 只取 `seq <= upToSeq`，按 `seq` 升序；
     * 2. **剥掉没有被应答的 tool_calls**——截断点恰好落在带工具的中间步时，
     *    那条 assistant 在新会话里不能继续宣称「我要调工具」（应答没被复制过去）；
     * 3. 顺手丢掉因此变成无主的 `role=tool` 行（新会话里没有 assistant 引用它，
     *    留着只会在界面上渲染出一个孤立的工具块）；
     * 4. 套用 [ContextBuilder.usable] 的同一套过滤（丢 SYSTEM / STREAMING / 空行），
     *    再把 `seq` 压成连续的 `1..N`（原 `seq` 可能有空洞）。
     */
    fun prefix(messages: List<Message>, upToSeq: Long): List<Message> {
        val within = messages.filter { it.seq <= upToSeq }.sortedBy { it.seq }
        if (within.isEmpty()) return emptyList()
        val answered = within
            .filter { it.role == Role.TOOL }
            .mapNotNull { it.toolCallId }
            .toSet()
        val repaired = within.map { message ->
            if (message.role == Role.ASSISTANT &&
                message.toolCalls.isNotEmpty() &&
                message.toolCalls.any { it.id !in answered }
            ) {
                message.copy(toolCalls = emptyList())
            } else {
                message
            }
        }
        val owned = repaired
            .filter { it.role == Role.ASSISTANT }
            .flatMap { call -> call.toolCalls.map { it.id } }
            .toSet()
        val trimmed = repaired.filter { message ->
            message.role != Role.TOOL || message.toolCallId == null || message.toolCallId in owned
        }
        return ContextBuilder.usable(trimmed).mapIndexed { index, message ->
            message.copy(seq = index + 1L)
        }
    }
}
