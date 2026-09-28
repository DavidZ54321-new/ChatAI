package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolKind
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolStatus
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnOutlineTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun message(
        id: String,
        role: Role,
        content: String = "",
        createdAt: Long = 0,
        reasoning: String? = null,
        reasoningMs: Long? = null,
        toolResult: ToolResult? = null,
        kinds: List<AttachmentKind> = emptyList(),
    ) = ChatMessageItem(
        id = id,
        role = role,
        content = content,
        reasoning = reasoning,
        reasoningMs = reasoningMs,
        createdAt = createdAt,
        toolResult = toolResult,
        images = kinds.mapIndexed { index, kind ->
            MessageImage(
                id = "$id-$index",
                thumbnailPath = "",
                fullPath = "",
                width = 1,
                height = 1,
                kind = kind,
            )
        },
    )

    private fun tool(detail: String, kind: ToolKind = ToolKind.SEARCH) = ToolResult(
        status = ToolStatus.OK,
        detail = detail,
        kind = kind,
    )

    @Test
    fun pairsUserWithTheFollowingAssistantTurn() {
        val turns = TurnOutline.of(
            listOf(
                message("u1", Role.USER, "问题", createdAt = 10),
                message("a1", Role.ASSISTANT, "我先查一下", reasoning = "想一下", reasoningMs = 2_000),
                message("t1", Role.TOOL, toolResult = tool("关键词")),
                message("a2", Role.ASSISTANT, "答案"),
                message("u2", Role.USER, "继续", createdAt = 20),
            ),
        )
        assertEquals(2, turns.size)
        val first = turns[0]
        assertEquals("u1", first.anchorKey)
        assertEquals("a1", first.assistantKey)
        assertEquals(0, first.startGroupIndex)
        assertEquals(10L, first.sentAt)
        assertEquals("问题", first.userText)
        assertEquals("答案", first.assistantText)
        assertEquals(listOf("a1"), first.reasoning.map { it.messageId })
        assertEquals(2_000L, first.reasoning.single().reasoningMs)
        assertEquals(listOf("t1"), first.tools.map { it.messageId })
        assertEquals("u2", turns[1].anchorKey)
        assertNull(turns[1].assistantKey)
        assertEquals(2, turns[1].startGroupIndex)
        assertEquals("", turns[1].assistantText)
    }

    @Test
    fun leadingAssistantWithoutUserIsItsOwnStop() {
        val turns = TurnOutline.of(
            listOf(message("a1", Role.ASSISTANT, "你好", createdAt = 5)),
        )
        assertEquals(1, turns.size)
        assertEquals("a1", turns.single().anchorKey)
        assertEquals("a1", turns.single().assistantKey)
        assertEquals("", turns.single().userText)
        assertEquals("你好", turns.single().assistantText)
        assertEquals(5L, turns.single().sentAt)
    }

    @Test
    fun emptyMessagesGiveNoStops() {
        assertTrue(TurnOutline.of(emptyList()).isEmpty())
    }

    @Test
    fun excerptCollapsesWhitespaceAndMarksTruncation() {
        assertEquals("a b", TurnOutline.excerpt("a  \n\t b"))
        assertEquals("", TurnOutline.excerpt("  \n"))
        assertEquals("abcd", TurnOutline.excerpt("abcd", maxChars = 4))
        assertEquals("abcd…", TurnOutline.excerpt("abcd efg", maxChars = 4))
        assertEquals("啊".repeat(240) + "…", TurnOutline.excerpt("啊".repeat(300)))
    }

    @Test
    fun attachmentLabelCountsKindsInAStableOrder() {
        assertNull(TurnOutline.attachmentLabel(emptyList()))
        assertEquals(
            "2 张图片 · 1 段视频 · 1 个文档 · 1 段音频",
            TurnOutline.attachmentLabel(
                listOf(
                    AttachmentKind.AUDIO,
                    AttachmentKind.IMAGE,
                    AttachmentKind.DOCUMENT,
                    AttachmentKind.IMAGE,
                    AttachmentKind.VIDEO,
                ),
            ),
        )
    }

    @Test
    fun turnCarriesTheAttachmentLabel() {
        val turns = TurnOutline.of(
            listOf(
                message(
                    "u1",
                    Role.USER,
                    "看图",
                    kinds = listOf(AttachmentKind.IMAGE, AttachmentKind.IMAGE),
                ),
            ),
        )
        assertEquals("2 张图片", turns.single().attachmentsLabel)
    }

    @Test
    fun timeLabelDropsTheDateOnTheSameLocalDay() {
        val sent = LocalDateTime.of(2026, 9, 29, 14, 5).atZone(zone).toInstant().toEpochMilli()
        val now = LocalDateTime.of(2026, 9, 29, 18, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("14:05", TurnOutline.timeLabel(sent, now, zone))
    }

    @Test
    fun timeLabelKeepsMonthAndDayAcrossDays() {
        val sent = LocalDateTime.of(2026, 9, 28, 9, 7).atZone(zone).toInstant().toEpochMilli()
        val now = LocalDateTime.of(2026, 9, 29, 18, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("9月28日 09:07", TurnOutline.timeLabel(sent, now, zone))
    }

    @Test
    fun lazyIndexAccountsForTheBranchHeader() {
        val messages = listOf(
            message("u1", Role.USER, "一"),
            message("a1", Role.ASSISTANT, "答"),
        )
        val groups = MessageGroups.of(messages)
        assertEquals(0, TurnOutline.lazyIndexOf("u1", groups, hasBranchHeader = false))
        assertEquals(1, TurnOutline.lazyIndexOf("u1", groups, hasBranchHeader = true))
        assertEquals(2, TurnOutline.lazyIndexOf("a1", groups, hasBranchHeader = true))
        assertNull(TurnOutline.lazyIndexOf("missing", groups, hasBranchHeader = false))
    }

    @Test
    fun visibleListIndexMapsOntoTheTurnUnderIt() {
        val messages = listOf(
            message("u1", Role.USER, "一"),
            message("a1", Role.ASSISTANT, "答"),
            message("u2", Role.USER, "二"),
            message("a2", Role.ASSISTANT, "再答"),
        )
        val turns = TurnOutline.of(messages)
        assertEquals(0, TurnOutline.turnIndexAtLazyIndex(0, hasBranchHeader = false, turns))
        assertEquals(0, TurnOutline.turnIndexAtLazyIndex(1, hasBranchHeader = false, turns))
        assertEquals(1, TurnOutline.turnIndexAtLazyIndex(2, hasBranchHeader = false, turns))
        assertEquals(1, TurnOutline.turnIndexAtLazyIndex(3, hasBranchHeader = false, turns))
        // 免责声明在组的后面。
        assertEquals(1, TurnOutline.turnIndexAtLazyIndex(4, hasBranchHeader = false, turns))
        // 有横幅时第 0 项是横幅，第一轮从 1 开始。
        assertEquals(0, TurnOutline.turnIndexAtLazyIndex(0, hasBranchHeader = true, turns))
        assertEquals(0, TurnOutline.turnIndexAtLazyIndex(1, hasBranchHeader = true, turns))
        assertEquals(1, TurnOutline.turnIndexAtLazyIndex(3, hasBranchHeader = true, turns))
        assertEquals(0, TurnOutline.turnIndexAtLazyIndex(0, hasBranchHeader = false, emptyList()))
    }

    @Test
    fun scrollTargetIsTheUserBubble() {
        val turns = TurnOutline.of(
            listOf(
                message("u1", Role.USER, "问题"),
                message("a1", Role.ASSISTANT, "我先查一下"),
                message("t1", Role.TOOL),
                message("a2", Role.ASSISTANT, "答案"),
                message("u2", Role.USER, "还没回"),
            ),
        )
        assertEquals("u1", turns[0].anchorKey)
        assertEquals("u2", turns[1].anchorKey)
    }

    @Test
    fun onlyTheActiveLastTurnSticksToTheBottom() {
        val messages = listOf(
            message("u1", Role.USER, "一"),
            message("a1", Role.ASSISTANT, "答"),
            message("u2", Role.USER, "二"),
            message("a2", Role.ASSISTANT, "再答"),
        )
        val groups = MessageGroups.of(messages)
        val turns = TurnOutline.of(messages)
        assertFalse(TurnOutline.sticksToBottom(turns[0], groups, isTurnActive = true))
        assertTrue(TurnOutline.sticksToBottom(turns[1], groups, isTurnActive = true))
        assertFalse(TurnOutline.sticksToBottom(turns[1], groups, isTurnActive = false))

        val waiting = TurnOutline.of(listOf(message("u1", Role.USER, "刚发出")))
        val waitingGroups = MessageGroups.of(listOf(message("u1", Role.USER, "刚发出")))
        assertTrue(TurnOutline.sticksToBottom(waiting.single(), waitingGroups, isTurnActive = true))
    }

    @Test
    fun toolTitleMatchesTheMainList() {
        assertEquals("联网搜索", toolCallTitle(tool("关键词")))
        assertEquals("网页抓取", toolCallTitle(tool("https://example.com")))
        assertEquals("网页抓取", toolCallTitle(tool("正在抓取")))
        assertEquals("文搜图", toolCallTitle(tool("猫", ToolKind.IMAGE_SEARCH)))
        assertEquals("以图搜图", toolCallTitle(tool("1", ToolKind.IMAGE_SIMILAR)))
        assertEquals(
            "网页抓取",
            toolCallTitle(ToolResult(status = ToolStatus.OK, detail = "正文", kind = ToolKind.FETCH)),
        )
    }
}
