package com.zcw.chatai.data.ai

import com.zcw.chatai.data.ai.ToolTurnGrouping.Node
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolTurnGroupingTest {

    private fun assistant(seq: Long, vararg ids: String) =
        Node(seq, Role.ASSISTANT, toolCalls = ids.map { ToolCall(it, "web_search", "{}") })

    private fun tool(seq: Long, id: String) = Node(seq, Role.TOOL, toolCallId = id)

    private fun user(seq: Long) = Node(seq, Role.USER)

    /** 窗口从 TOOL 行中间切开时，前半段的 assistant 必须一起被删，否则 tool_calls 孤立。 */
    @Test
    fun findsAssistantWhoseToolAnswersFallAfterTarget() {
        val nodes = listOf(
            user(1),
            assistant(2, "a", "b"),
            tool(3, "a"),          // tool_calls 的应答在 2 之后
            assistant(4, "c"),
            tool(5, "c"),
        )
        // 从 seq=4 起截断：seq=2 的 assistant 应答在 4 之后吗？不，应答在 3。所以只删 2 之外的。
        assertEquals(emptySet<Long>(), ToolTurnGrouping.orphanAssistantSeqsBefore(nodes, 4))
    }

    @Test
    fun findsOrphanWhenTargetCutsBetweenAssistantAndItsAnswers() {
        val nodes = listOf(
            user(1),
            assistant(2, "a", "b"),
            tool(3, "a"),
            tool(4, "b"),          // 应答跨过目标 seq
        )
        // 从 seq=4 起截断：seq=2 的 assistant 有一个应答（b）落在 4 之后 → 整组带走。
        assertEquals(setOf(2L), ToolTurnGrouping.orphanAssistantSeqsBefore(nodes, 4))
    }

    @Test
    fun noOrphansWhenTargetIsAnAssistantStart() {
        val nodes = listOf(
            user(1),
            assistant(2, "a"),
            tool(3, "a"),
            assistant(4, "b"),
        )
        assertEquals(emptySet<Long>(), ToolTurnGrouping.orphanAssistantSeqsBefore(nodes, 4))
    }

    /** 删 assistant：连带它紧随的工具结果，但**不**碰后面的回合。 */
    @Test
    fun deletingAssistantTakesItsOwnToolRowsOnly() {
        val nodes = listOf(
            user(1),
            assistant(2, "a"),
            tool(3, "a"),
            assistant(4, "b"),
            tool(5, "b"),
        )
        assertEquals(setOf(2L, 3L), ToolTurnGrouping.deletionSetFor(nodes, 2))
    }

    /** 删 TOOL：把发起它的 assistant 一起删，避免工具调用无应答。 */
    @Test
    fun deletingToolRowAlsoRemovesItsAssistant() {
        val nodes = listOf(
            user(1),
            assistant(2, "a"),
            tool(3, "a"),
        )
        assertEquals(setOf(3L, 2L), ToolTurnGrouping.deletionSetFor(nodes, 3))
    }

    @Test
    fun deletingUserRowOnlyRemovesItself() {
        val nodes = listOf(user(1), assistant(2, "a"), tool(3, "a"))
        assertEquals(setOf(1L), ToolTurnGrouping.deletionSetFor(nodes, 1))
    }

    @Test
    fun assistantWithoutToolCallsTakesNothingExtra() {
        val nodes = listOf(user(1), Node(2, Role.ASSISTANT), Node(3, Role.ASSISTANT))
        assertEquals(setOf(2L), ToolTurnGrouping.deletionSetFor(nodes, 2))
    }

    /**
     * 回归：`tool_call_id` 跨回合复用（或后来补了同名占位）时，不能让早先那个回合的删除
     * 波及后面回合的 TOOL 行——那会让后面 assistant 的 tool_calls 失去配对（400）。
     */
    @Test
    fun deletingAssistantDoesNotReachLaterTurnWithSameToolCallId() {
        val nodes = listOf(
            user(1),
            assistant(2, "dup"),      // 回合 A
            tool(3, "dup"),
            assistant(4, "other"),    // 回合 B，工具调用被复用同一 id
            tool(5, "dup"),           // 属于回合 B，不能因为 id 相同被删
        )
        assertEquals(setOf(2L, 3L), ToolTurnGrouping.deletionSetFor(nodes, 2))
    }

    /** 只带走连续的工具行：中间插了一行别的角色就断开。 */
    @Test
    fun onlyContiguousFollowingToolRowsAreTaken() {
        val nodes = listOf(
            user(1),
            assistant(2, "a"),
            user(3),                  // 插在中间，断开连续性
            tool(4, "a"),
        )
        assertEquals(setOf(2L), ToolTurnGrouping.deletionSetFor(nodes, 2))
    }
}
