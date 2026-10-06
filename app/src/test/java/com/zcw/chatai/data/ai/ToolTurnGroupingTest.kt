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

    /** 删 assistant：连带它紧随的工具结果，不碰用户消息，也不碰后面的步骤。 */
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
     * 回归：tool_call_id 跨回合复用时，不能让早先那个回合的删除
     * 波及后面回合的 TOOL 行。
     */
    @Test
    fun deletingAssistantDoesNotReachLaterTurnWithSameToolCallId() {
        val nodes = listOf(
            user(1),
            assistant(2, "dup"),
            tool(3, "dup"),
            assistant(4, "other"),
            tool(5, "dup"),
        )
        assertEquals(setOf(2L, 3L), ToolTurnGrouping.deletionSetFor(nodes, 2))
    }

    /** 只带走连续的工具行：中间插了用户消息就断开。 */
    @Test
    fun onlyContiguousFollowingToolRowsAreTaken() {
        val nodes = listOf(
            user(1),
            assistant(2, "a"),
            user(3),
            tool(4, "a"),
        )
        assertEquals(setOf(2L), ToolTurnGrouping.deletionSetFor(nodes, 2))
    }

    /** 工具行和它的助手被用户消息隔开时，仍要带走助手，否则 tool_calls 没有应答。 */
    @Test
    fun deletingToolSeparatedByAUserStillRemovesItsAssistant() {
        val nodes = listOf(
            user(1),
            assistant(2, "a"),
            user(3),
            tool(4, "a"),
        )
        assertEquals(setOf(2L, 4L), ToolTurnGrouping.deletionSetFor(nodes, 4))
    }

    /** 整轮：同一视觉回合里的助手步骤和工具行，加上紧邻的用户气泡。 */
    @Test
    fun turnSetTakesTheWholeRunAndThePrecedingUser() {
        val nodes = listOf(
            user(1),
            assistant(2, "a"),
            tool(3, "a"),
            assistant(4, "b"),
            tool(5, "b"),
        )
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), ToolTurnGrouping.turnSetFor(nodes, 4))
    }

    /** 从这条用户消息起算一轮，不往前并，也不跨过下一条用户消息。 */
    @Test
    fun turnSetFromAUserIncludesOnlyTheFollowingRun() {
        val nodes = listOf(
            user(1),
            assistant(2, "a"),
            tool(3, "a"),
            user(4),
            assistant(5, "b"),
        )
        assertEquals(setOf(1L, 2L, 3L), ToolTurnGrouping.turnSetFor(nodes, 1))
    }

    /** 下一条用户消息是整轮的边界，同名 tool_call_id 的后一轮留着。 */
    @Test
    fun turnSetStopsAtTheNextUser() {
        val nodes = listOf(
            user(1),
            assistant(2, "dup"),
            tool(3, "dup"),
            user(4),
            assistant(5, "other"),
            tool(6, "dup"),
        )
        assertEquals(setOf(1L, 2L, 3L), ToolTurnGrouping.turnSetFor(nodes, 2))
    }

    /** 隔在用户消息后面的工具行是另一轮，整轮删除不回头修配对。 */
    @Test
    fun turnSetDoesNotReachAToolSeparatedByAUser() {
        val nodes = listOf(
            user(1),
            assistant(2, "a"),
            user(3),
            tool(4, "a"),
        )
        assertEquals(setOf(1L, 2L), ToolTurnGrouping.turnSetFor(nodes, 2))
        assertEquals(setOf(3L, 4L), ToolTurnGrouping.turnSetFor(nodes, 4))
    }

    @Test
    fun noMissingAnswersWhenEveryCallIsAnswered() {
        val nodes = listOf(
            user(1),
            assistant(2, "a", "b"),
            tool(3, "a"),
            tool(4, "b"),
        )
        assertEquals(emptyList<ToolTurnGrouping.MissingAnswer>(), ToolTurnGrouping.planMissingToolAnswers(nodes))
    }

    @Test
    fun missingAnswerIsPlannedRightAfterTheLastContiguousToolRow() {
        val nodes = listOf(
            user(1),
            assistant(2, "a", "b"),
            tool(3, "a"),
        )
        val plan = ToolTurnGrouping.planMissingToolAnswers(nodes)
        assertEquals(listOf(ToolTurnGrouping.MissingAnswer(ToolCall("b", "web_search", "{}"), 3)), plan)
    }

    @Test
    fun missingAnswerWithoutAnyToolRowGoesRightAfterTheAssistant() {
        val nodes = listOf(
            user(1),
            assistant(2, "a"),
            user(3),
        )
        val plan = ToolTurnGrouping.planMissingToolAnswers(nodes)
        assertEquals(listOf(ToolTurnGrouping.MissingAnswer(ToolCall("a", "web_search", "{}"), 2)), plan)
    }

    /** 同一个 assistant 缺多条：第二条要排在第一条插入之后（计划里的 seq 已含前面的位移）。 */
    @Test
    fun multipleMissingAnswersForSameAssistantGetIncreasingAnchors() {
        val nodes = listOf(
            user(1),
            assistant(2, "a", "b", "c"),
            tool(3, "a"),
        )
        val plan = ToolTurnGrouping.planMissingToolAnswers(nodes)
        assertEquals(listOf(3L, 4L), plan.map { it.afterSeq })
        assertEquals(listOf("b", "c"), plan.map { it.call.id })
    }

    /** 后面的 assistant 的插入点要算上前面已规划的位移。 */
    @Test
    fun laterAssistantAnchorAccountsForEarlierInsertions() {
        val nodes = listOf(
            user(1),
            assistant(2, "a", "b"),
            tool(3, "a"),
            assistant(4, "c"),
            user(5),
        )
        val plan = ToolTurnGrouping.planMissingToolAnswers(nodes)
        assertEquals(listOf(3L, 5L), plan.map { it.afterSeq })
        assertEquals(listOf("b", "c"), plan.map { it.call.id })
    }

    /**
     * 回归（真机损坏样本）：assistant(a,b) → tool(a) → USER → tool(b)，
     * 缺的应答必须插在 USER **之前**，不能补到队尾。
     */
    @Test
    fun missingAnswerNeverLandsAfterAnInterveningUserMessage() {
        val nodes = listOf(
            user(1),
            assistant(2, "a", "b", "c"),
            tool(3, "a"),
            user(4),
            tool(5, "b"),
        )
        val plan = ToolTurnGrouping.planMissingToolAnswers(nodes)
        assertEquals(listOf("c", 3L), listOf(plan.single().call.id, plan.single().afterSeq))
    }
}
