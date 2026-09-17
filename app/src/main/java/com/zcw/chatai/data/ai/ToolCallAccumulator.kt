package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.net.ChatStreamEvent

/**
 * 把流式 `tool_calls` 增量按 index 拼成完整调用（纯逻辑，JVM 单测）。
 * `arguments` 是逐字符到达的 JSON 字符串，只能拼接，不能逐块解析。
 * `id` 与 `name` 预期每个 index 只到达一次且完整，不累加，后到的非空值覆盖先前的。
 */
class ToolCallAccumulator {

    private class Partial {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()

        fun isReal(): Boolean = name.isNotBlank()
    }

    private val parts = LinkedHashMap<Int, Partial>()

    fun accept(event: ChatStreamEvent.ToolCallDelta) {
        val part = parts.getOrPut(event.index) { Partial() }
        event.id?.takeIf { it.isNotEmpty() }?.let { part.id = it }
        event.name?.takeIf { it.isNotEmpty() }?.let { part.name = it }
        event.arguments?.takeIf { it.isNotEmpty() }?.let { part.arguments.append(it) }
    }

    val isEmpty: Boolean get() = parts.values.none { it.isReal() }

    fun assemble(): List<ToolCall> = parts.entries
        .sortedBy { it.key }
        .filter { it.value.isReal() }
        .map { (index, part) ->
            ToolCall(
                id = part.id.ifBlank { "synth_call_$index" },
                name = part.name,
                arguments = part.arguments.toString(),
            )
        }
}
