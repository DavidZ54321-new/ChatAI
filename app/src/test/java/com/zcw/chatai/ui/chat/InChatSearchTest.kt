package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InChatSearchTest {

    @Test
    fun readsUserAndAssistantBodyInOrderAndSkipsToolAndReasoning() {
        val hits = InChatSearch.find(
            listOf(
                message("u", Role.USER, "先说老黄"),
                message("a", Role.ASSISTANT, "老黄还在", reasoning = "秘密老黄"),
                message("t", Role.TOOL, "工具里的老黄"),
            ),
            "老黄",
        )
        assertEquals(listOf("u", "a"), hits.map { it.messageId })
        assertEquals(2, hits[0].start)
        assertEquals(0, hits[1].start)
    }

    @Test
    fun blankQueryHasNoHits() {
        val messages = listOf(message("u", Role.USER, "老黄"))
        assertTrue(InChatSearch.find(messages, "").isEmpty())
        assertTrue(InChatSearch.find(messages, "   ").isEmpty())
    }

    @Test
    fun matchesAreCaseInsensitiveAndDoNotOverlap() {
        val hits = InChatSearch.find(listOf(message("u", Role.USER, "aaa Hello")), "aa")
        assertEquals(1, hits.size)
        assertEquals(0 until 2, hits[0].start until hits[0].end)

        val hello = InChatSearch.find(listOf(message("u", Role.USER, "aaa Hello")), "hello")
        assertEquals(1, hello.size)
        assertEquals(4, hello[0].start)
        assertEquals(9, hello[0].end)
    }

    @Test
    fun imageRowAndBlockMathAreNotHits() {
        val content = "甲\n![图](https://example.com/a.png)\n乙\n$$\nmc\n$$\n后"
        val hits = InChatSearch.find(listOf(message("a", Role.ASSISTANT, content)), "example.com")
        assertTrue(hits.isEmpty())
        assertTrue(InChatSearch.find(listOf(message("a", Role.ASSISTANT, content)), "mc").isEmpty())

        val pieces = InChatSearch.textPieces(content)
        val yi = InChatSearch.find(listOf(message("a", Role.ASSISTANT, content)), "乙").single()
        assertEquals(1, yi.segment)
        assertEquals(pieces[1].indexOf("乙"), yi.start)
        val hou = InChatSearch.find(listOf(message("a", Role.ASSISTANT, content)), "后").single()
        assertEquals(pieces[hou.segment].indexOf("后"), hou.start)
    }

    @Test
    fun inlineMathUsesPreparedOffsets() {
        val raw = "\$x\$ 老黄"
        val hits = InChatSearch.find(listOf(message("a", Role.ASSISTANT, raw)), "老黄")
        val prepared = InChatSearch.textPieces(raw).single()
        assertEquals(prepared.indexOf("老黄"), hits.single().start)
    }

    @Test
    fun userBubbleKeepsRawText() {
        val raw = "\$x\$ 老黄"
        val hits = InChatSearch.find(listOf(message("u", Role.USER, raw)), "老黄")
        assertEquals(raw.indexOf("老黄"), hits.single().start)
    }

    @Test
    fun stepStopsAtTheEnds() {
        assertEquals(0, InChatSearch.step(0, 3, -1))
        assertEquals(2, InChatSearch.step(2, 3, 1))
        assertEquals(1, InChatSearch.step(0, 3, 1))
        assertEquals(-1, InChatSearch.step(0, 0, 1))
        // 长按用整表长度当步长，夹到第一处或最后一处。
        assertEquals(0, InChatSearch.step(5, 8, -8))
        assertEquals(7, InChatSearch.step(2, 8, 8))
    }

    @Test
    fun scrollBlockedOnlyWhenThatDirectionCannotMove() {
        assertFalse(InChatSearch.scrollBlocked(delta = 40f, consumed = 0f, canScrollFurther = true))
        assertTrue(InChatSearch.scrollBlocked(delta = 40f, consumed = 0f, canScrollFurther = false))
        assertFalse(InChatSearch.scrollBlocked(delta = 1f, consumed = 0f, canScrollFurther = false))
        assertFalse(InChatSearch.scrollBlocked(delta = 40f, consumed = 20f, canScrollFurther = false))
    }

    @Test
    fun resolveKeepsAnchorAndFallsBackWhenItDisappears() {
        val hits = listOf(
            InChatHit("a", 0, 0, 2),
            InChatHit("b", 0, 4, 6),
        )
        assertEquals(0, InChatSearch.resolveIndex(hits, null, 0, -1, 1))
        assertEquals(1, InChatSearch.resolveIndex(hits, "b", 0, 4, 0))
        assertEquals(1, InChatSearch.resolveIndex(hits, "gone", 0, 9, 1))
        assertEquals(-1, InChatSearch.resolveIndex(emptyList(), "a", 0, 0, 0))
    }

    @Test
    fun centerDeltaAimsAtTheRegionMiddle() {
        assertEquals(0f, InChatSearch.centerDelta(100f, 0f, 200f), 0.01f)
        assertEquals(50f, InChatSearch.centerDelta(150f, 0f, 200f), 0.01f)
        assertEquals(-40f, InChatSearch.centerDelta(60f, 0f, 200f), 0.01f)
    }

    @Test
    fun paintCodeMapsEqualSliceAndPairsStrippedIndentByOrder() {
        val same = InChatSearch.paintCode(
            sourceStart = 10,
            sourceSlice = "hello hi",
            displayed = "hello hi",
            query = "hi",
            hits = listOf(InChatHit("m", 0, 16, 18)),
            active = InChatHit("m", 0, 16, 18),
        )
        assertEquals(6 until 8, same.active)

        val slice = "\n    hi\n"
        val displayed = slice.replaceIndent()
        val start = 4
        val hit = InChatHit("m", 0, start + slice.indexOf("hi"), start + slice.indexOf("hi") + 2)
        val stripped = InChatSearch.paintCode(start, slice, displayed, "hi", listOf(hit), hit)
        assertEquals(displayed.indexOf("hi"), stripped.active?.first)
    }

    @Test
    fun lazyIndexAccountsForBranchHeader() {
        val groups = MessageGroups.of(
            listOf(
                message("u", Role.USER, "问"),
                message("a", Role.ASSISTANT, "答"),
            ),
        )
        assertEquals(1, InChatSearch.messageLazyIndex(groups, "a", hasBranchHeader = false))
        assertEquals(2, InChatSearch.messageLazyIndex(groups, "a", hasBranchHeader = true))
        assertNull(InChatSearch.messageLazyIndex(groups, "missing", hasBranchHeader = false))
    }

    private fun message(
        id: String,
        role: Role,
        content: String,
        reasoning: String? = null,
    ) = ChatMessageItem(id = id, role = role, content = content, reasoning = reasoning)
}
