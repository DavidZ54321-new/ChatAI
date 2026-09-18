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

    /** 一条缺失的工具应答：插在 [afterSeq] 之后。seq 已含计划中先前插入造成的位移。 */
    data class MissingAnswer(val call: ToolCall, val afterSeq: Long)

    /**
     * 崩溃窗口修复（纯函数）：assistant 已落 `tool_calls` 但 DB 里没有对应 TOOL 行时，
     * 算出占位应答应该插在哪。
     *
     * 位置必须是**紧跟在它连续的已有应答之后**（没有就紧跟 assistant 自己），
     * 绝不能追加到队尾——真机样本里队尾是后来插入的用户消息，
     * 补在它后面会让服务端把用户消息当成 tool 组的终点（400）。
     */
    fun planMissingToolAnswers(nodes: List<Node>): List<MissingAnswer> {
        val ordered = nodes.sortedBy { it.seq }
        val answered = ordered.filter { it.role == Role.TOOL }.mapNotNull { it.toolCallId }.toSet()
        val plan = mutableListOf<MissingAnswer>()
        // 已规划的插入数：assistant 按 seq 递增处理，因此前面所有插入都在当前锚点之前。
        var insertions = 0
        for (assistant in ordered) {
            if (assistant.role != Role.ASSISTANT || assistant.toolCalls.isEmpty()) continue
            val missing = assistant.toolCalls.filter { it.id !in answered }
            if (missing.isEmpty()) continue
            val callIds = assistant.toolCalls.map { it.id }.toSet()
            var anchor = assistant.seq
            for (node in ordered) {
                if (node.seq <= anchor) continue
                if (node.role != Role.TOOL || node.toolCallId !in callIds) break
                anchor = node.seq
            }
            var position = anchor + insertions
            for (call in missing) {
                plan += MissingAnswer(call, position)
                insertions++
                position++
            }
        }
        return plan
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
