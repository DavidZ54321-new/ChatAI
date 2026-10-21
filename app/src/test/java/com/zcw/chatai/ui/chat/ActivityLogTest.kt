package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolKind
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLogTest {

    @Test
    fun interleavesThinkingAndToolsInMessageOrder() {
        val steps = ActivityLog.steps(
            listOf(
                message("a1", Role.ASSISTANT, reasoning = "先想一下"),
                message("t1", Role.TOOL, toolResult = tool("罗大佑 首都")),
                message("t2", Role.TOOL, toolResult = tool("罗大佑 新世代")),
                message("a2", Role.ASSISTANT, reasoning = "再看专辑"),
                message("u", Role.USER, content = "不该进来"),
            ),
            streamingMessageId = null,
        )
        assertEquals(listOf("a1", "t1", "t2", "a2"), steps.map { it.messageId })
        assertTrue(steps[0] is ActivityStep.Thinking)
        assertTrue(steps[1] is ActivityStep.Tool)
    }

    @Test
    fun skipsBlankReasoningAndToolsWithoutAResult() {
        val steps = ActivityLog.steps(
            listOf(
                message("a1", Role.ASSISTANT, reasoning = "  \n "),
                message("a2", Role.ASSISTANT, reasoning = null),
                message("t1", Role.TOOL),
                message("t2", Role.TOOL, toolResult = tool("还有")),
            ),
            streamingMessageId = null,
        )
        assertEquals(listOf("t2"), steps.map { it.messageId })
    }

    @Test
    fun liveThinkingBeatsARunningToolAndFollowsTheTail() {
        val head = "头"
        val tail = "尾"
        val reasoning = head + "中".repeat(250) + tail
        val steps = ActivityLog.steps(
            listOf(
                message("t1", Role.TOOL, toolResult = tool("查询", status = ToolStatus.RUNNING)),
                message("a1", Role.ASSISTANT, reasoning = reasoning),
            ),
            streamingMessageId = "a1",
        )
        val label = ActivityLog.latestLabel(steps, turnActive = true)
        assertEquals(true, label?.followEnd)
        assertTrue(label!!.text.startsWith(head))
        assertTrue(label.text.endsWith(tail))
    }

    @Test
    fun answerStartedSettlesOnTheHeadOfTheLastThinking() {
        val reasoning = "头" + "中".repeat(250) + "尾"
        val steps = ActivityLog.steps(
            listOf(message("a1", Role.ASSISTANT, content = "正文来了", reasoning = reasoning)),
            streamingMessageId = "a1",
        )
        val label = ActivityLog.latestLabel(steps, turnActive = true)
        assertEquals("已思考完成", label?.text)
        assertEquals(false, label?.followEnd)
    }

    @Test
    fun runningToolLabelsTheCollapsedRow() {
        val steps = ActivityLog.steps(
            listOf(
                message("a1", Role.ASSISTANT, reasoning = "想过了", reasoningMs = 2_000),
                message("t1", Role.TOOL, toolResult = tool("刹车踏板", kind = ToolKind.SEARCH, status = ToolStatus.RUNNING)),
            ),
            streamingMessageId = null,
        )
        val label = ActivityLog.latestLabel(steps, turnActive = true)
        assertEquals("联网搜索… · 刹车踏板", label?.text)
        assertFalse(label!!.followEnd)
    }

    @Test
    fun finishedTurnStopsOnTheLastStep() {
        val steps = ActivityLog.steps(
            listOf(
                message("a1", Role.ASSISTANT, reasoning = "先搜", reasoningMs = 2_000),
                message("t1", Role.TOOL, toolResult = tool("罗大佑 飞车")),
            ),
            streamingMessageId = null,
        )
        assertEquals("已思考完成", ActivityLog.latestLabel(steps, turnActive = false)?.text)
        assertEquals("思考中…", ActivityLog.latestLabel(steps, turnActive = true)?.text)
        assertEquals("已深度思考 2s · 先搜", ActivityLog.line(steps[0]))
        assertEquals("联网搜索 · 罗大佑 飞车", ActivityLog.line(steps[1]))
    }

    @Test
    fun failedToolIsFlaggedSeparatelyFromTheLine() {
        val result = tool("打不开", status = ToolStatus.FAILED)
        val steps = listOf(ActivityStep.Tool("t1", result))
        val label = ActivityLog.latestLabel(steps, turnActive = false)
        assertEquals("已思考完成", label?.text)
        assertTrue(ActivityLog.isFailed(steps.single()))
        assertEquals("文搜图… · 猫", ActivityLog.toolLine(tool("猫", ToolKind.IMAGE_SEARCH, ToolStatus.RUNNING)))
        assertEquals("网页抓取 · https://a.example", ActivityLog.toolLine(tool("https://a.example")))
        assertEquals("以图搜图", ActivityLog.toolLine(ToolResult(ToolStatus.OK, detail = "", kind = ToolKind.IMAGE_SIMILAR)))
    }

    @Test
    fun emptyTurnShowsThinkingOnlyWhileActive() {
        assertNull(ActivityLog.latestLabel(emptyList(), turnActive = false))
        assertEquals("思考中…", ActivityLog.latestLabel(emptyList(), turnActive = true)?.text)
    }

    @Test
    fun onlyTheLastActivityStaysLiveUntilTheNextAnswerStarts() {
        val messages = listOf(
            message("a1", Role.ASSISTANT, content = "先说一句", reasoning = "想第一轮", hasToolCalls = true),
            message("t1", Role.TOOL, toolResult = tool("第一轮")),
            message("a2", Role.ASSISTANT, content = "最后的回答", reasoning = "收尾"),
        )
        val pieces = ActivityLog.pieces(messages, streamingMessageId = "a2")
        assertFalse(ActivityLog.isLive(pieces, "a1", turnActive = true, streamingMessageId = "a2"))
        assertFalse(ActivityLog.isLive(pieces, "t1", turnActive = true, streamingMessageId = "a2"))
        val beforeAnswer = ActivityLog.pieces(messages, streamingMessageId = null)
        assertFalse(ActivityLog.isLive(beforeAnswer, "a1", turnActive = true, streamingMessageId = null))
        assertTrue(ActivityLog.isLive(beforeAnswer, "t1", turnActive = true, streamingMessageId = null))
        assertEquals(
            "已思考完成",
            ActivityLog.rowLabel(beforeAnswer, 0, turnActive = true, streamingMessageId = null)?.text,
        )
    }

    @Test
    fun thinkingStaysAboveItsContentAndTheFollowingSearchStaysBelow() {
        val pieces = ActivityLog.pieces(
            listOf(
                message(
                    "a1",
                    Role.ASSISTANT,
                    content = "让我先联想一下，再联网确认。",
                    reasoning = "用户问看到这张图片想到罗大佑哪首歌",
                    hasToolCalls = true,
                ),
                message("t1", Role.TOOL, toolResult = tool("罗大佑 夜晚骑", status = ToolStatus.RUNNING)),
            ),
            streamingMessageId = null,
        )
        assertEquals(listOf("activity:a1", "body:a1", "activity:t1"), pieces.map { it.label() })
        val thinking = pieces[0] as TurnPiece.Activity
        val search = pieces[2] as TurnPiece.Activity
        assertEquals(listOf("a1"), thinking.steps.map { it.messageId })
        assertTrue(thinking.steps.single() is ActivityStep.Thinking)
        assertEquals(listOf("t1"), search.steps.map { it.messageId })
        assertEquals("已思考完成", ActivityLog.latestLabel(thinking.steps, turnActive = false)?.text)
        assertEquals("联网搜索… · 罗大佑 夜晚骑", ActivityLog.latestLabel(search.steps, turnActive = true)?.text)
    }

    @Test
    fun eachRoundKeepsItsOwnBubbleAroundItsOwnContent() {
        val pieces = ActivityLog.pieces(
            listOf(
                message("a1", Role.ASSISTANT, content = "先说一句", reasoning = "想第一轮", hasToolCalls = true),
                message("t1", Role.TOOL, toolResult = tool("第一轮")),
                message("t1b", Role.TOOL, toolResult = tool("第一轮补充")),
                message("a2", Role.ASSISTANT, content = "中间再补一句", reasoning = "想第二轮", hasToolCalls = true),
                message("t2", Role.TOOL, toolResult = tool("第二轮")),
                message("a3", Role.ASSISTANT, content = "最后的回答", reasoning = "收尾"),
            ),
            streamingMessageId = null,
        )
        assertEquals(
            listOf("activity:a1", "body:a1", "activity:t1", "body:a2", "activity:t2", "body:a3"),
            pieces.map { it.label() },
        )
        val between = pieces[2] as TurnPiece.Activity
        assertEquals(listOf("t1", "t1b", "a2"), between.steps.map { it.messageId })
        val beforeLast = pieces[4] as TurnPiece.Activity
        assertEquals(listOf("t2", "a3"), beforeLast.steps.map { it.messageId })
    }

    @Test
    fun thinkingWithoutToolsStaysAboveTheAnswer() {
        val pieces = ActivityLog.pieces(
            listOf(message("a1", Role.ASSISTANT, content = "直接回答", reasoning = "想一下")),
            streamingMessageId = null,
        )
        assertEquals(listOf("activity:a1", "body:a1"), pieces.map { it.label() })
    }

    private fun TurnPiece.label(): String = when (this) {
        is TurnPiece.Body -> "body:$messageId"
        is TurnPiece.Activity -> "activity:$key"
    }

    private fun message(
        id: String,
        role: Role,
        content: String = "",
        reasoning: String? = null,
        reasoningMs: Long? = null,
        toolResult: ToolResult? = null,
        hasToolCalls: Boolean = false,
    ) = ChatMessageItem(
        id = id,
        role = role,
        content = content,
        reasoning = reasoning,
        reasoningMs = reasoningMs,
        toolResult = toolResult,
        hasToolCalls = hasToolCalls,
    )

    private fun tool(
        detail: String,
        kind: ToolKind = ToolKind.SEARCH,
        status: ToolStatus = ToolStatus.OK,
    ) = ToolResult(status = status, detail = detail, kind = kind)
}
