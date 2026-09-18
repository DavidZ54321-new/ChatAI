package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolCall

/**
 * Agent 回合的分组规则（纯函数，JVM 单测覆盖）。
 *
 * 一个回合是「assistant(tool_calls) + 紧随其后的 TOOL 结果」的整体：
 * - 截断/删除时不能只切一半，否则会留下孤立的 `tool_calls`（无应答）或孤立的 TOOL 行，
 *   下一次请求服务端直接 400；
 * - 反过来，遗留的失败工具结果会误导模型（「搜索没结果」），所以重跑前必须整组清掉。
 */
object ToolTurnGrouping {

    /** 一条消息的角色与配对信息，避免此纯逻辑依赖 Room 实体。 */
    data class Node(
        val seq: Long,
        val role: Role,
        val toolCalls: List<ToolCall> = emptyList(),
        val toolCallId: String? = null,
    )

    /**
     * 从 [targetSeq] 起整组截断时要额外删除的「孤儿 assistant」seq 集合：
     * 即位于 [targetSeq] 之前、但其工具应答落在 [targetSeq] 之后的 assistant。
     */
    fun orphanAssistantSeqsBefore(nodes: List<Node>, targetSeq: Long): Set<Long> {
        val answeredAtOrAfter = nodes
            .filter { it.seq >= targetSeq }
            .mapNotNull { it.toolCallId }
            .toSet()
        if (answeredAtOrAfter.isEmpty()) return emptySet()
        return nodes
            .filter { it.seq < targetSeq && it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
            .filter { assistant -> assistant.toolCalls.any { it.id in answeredAtOrAfter } }
            .map { it.seq }
            .toSet()
    }

    /** 删除单条消息时要连带删除的 seq 集合（含自身）。 */
    fun deletionSetFor(nodes: List<Node>, targetSeq: Long): Set<Long> {
        val target = nodes.firstOrNull { it.seq == targetSeq } ?: return emptySet()
        val ordered = nodes.sortedBy { it.seq }
        val ids = mutableSetOf(target.seq)
        val callIds = target.toolCalls.map { it.id }.toSet()
        if (target.role == Role.ASSISTANT && callIds.isNotEmpty()) {
            // 只带走**紧随其后**的 TOOL 行：连续且 tool_call_id 属于本回合。
            // 不能按「后面任意匹配」删——`tool_call_id` 跨回合复用时会误删后面回合的应答，
            // 让那个 assistant 的 tool_calls 失去配对 → 下次请求 400。
            ordered.asSequence()
                .dropWhile { it.seq <= target.seq }
                .takeWhile { it.role == Role.TOOL && it.toolCallId in callIds }
                .forEach { ids += it.seq }
        } else if (target.role == Role.TOOL && target.toolCallId != null) {
            // 删 TOOL 时把发起它的 assistant 一起删，避免 tool_calls 孤立
            ordered.asSequence()
                .filter { it.seq < target.seq && it.role == Role.ASSISTANT }
                .lastOrNull { assistant -> assistant.toolCalls.any { it.id == target.toolCallId } }
                ?.let { ids += it.seq }
        }
        return ids
    }
}
