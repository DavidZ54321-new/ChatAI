package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.StreamingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamGhostTest {

    private fun stream(id: String = "m1", content: String = "hi") = StreamingMessage(
        conversationId = "c1",
        messageId = id,
        content = content,
        reasoning = "",
    )

    @Test
    fun overlayOnlyStreamIsKeptAsGhostUntilDbRowAppears() {
        val s = stream()
        val g1 = StreamGhost().step("c1", activeStream = s, dbTextLengths = emptyMap())
        assertEquals(s, g1.overlay(s))
        // clearStreaming 后 DB 还没发射：幽灵顶住，消息不消失
        val g2 = g1.step("c1", activeStream = null, dbTextLengths = emptyMap())
        assertEquals(s, g2.overlay(null))
        // DB 行到了且内容追平：幽灵撤掉
        val g3 = g2.step("c1", activeStream = null, dbTextLengths = mapOf("m1" to 2))
        assertNull(g3.overlay(null))
    }

    @Test
    fun rowWithEmptyContentAtStreamEndKeepsGhost() {
        // 本 bug 的核心用例：助手行先 upsert 成空壳，finalize 的 Room 发射还没到。
        // 此时若撤幽灵，content 变空 → 渲染塌成 0 高 → 视口被钳回用户气泡。
        val s = stream(content = "hello world")
        val g1 = StreamGhost().step("c1", activeStream = s, dbTextLengths = mapOf("m1" to 0))
        val g2 = g1.step("c1", activeStream = null, dbTextLengths = mapOf("m1" to 0))
        assertEquals(s, g2.overlay(null))
        // 半截 checkpoint（短于流式快照）→ 仍然续命
        val g3 = g2.step("c1", activeStream = null, dbTextLengths = mapOf("m1" to 5))
        assertEquals(s, g3.overlay(null))
        // 定稿内容发射到位（追平）→ 撤
        val g4 = g3.step("c1", activeStream = null, dbTextLengths = mapOf("m1" to 11))
        assertNull(g4.overlay(null))
    }

    @Test
    fun reasoningLengthCountsTowardCatchUp() {
        val s = StreamingMessage(conversationId = "c1", messageId = "m1", content = "a", reasoning = "think")
        val g1 = StreamGhost().step("c1", activeStream = s, dbTextLengths = emptyMap())
        // DB 只有正文到位、思考还没到 → 继续顶住
        val g2 = g1.step("c1", activeStream = null, dbTextLengths = mapOf("m1" to 1))
        assertEquals(s, g2.overlay(null))
        val g3 = g2.step("c1", activeStream = null, dbTextLengths = mapOf("m1" to 6))
        assertNull(g3.overlay(null))
    }

    @Test
    fun ghostDiesWhenRowIsDeletedInsteadOfEmitting() {
        val s = stream()
        val g1 = StreamGhost().step("c1", activeStream = s, dbTextLengths = emptyMap())
        // 重生成/删除：DB 换了一版（行数变了）却仍没有这一行 → 别把删掉的内容留在屏上
        val g2 = g1.step("c1", activeStream = null, dbTextLengths = mapOf("other" to 3))
        assertNull(g2.overlay(null))
    }

    @Test
    fun ghostNeverLeaksAcrossConversations() {
        val s = stream()
        val g1 = StreamGhost().step("c1", activeStream = s, dbTextLengths = emptyMap())
        val g2 = g1.step("c2", activeStream = null, dbTextLengths = emptyMap())
        assertNull(g2.overlay(null))
    }

    @Test
    fun newStreamReplacesStaleGhost() {
        val old = stream(id = "m1", content = "old")
        val g1 = StreamGhost().step("c1", activeStream = old, dbTextLengths = emptyMap())
        val g2 = g1.step("c1", activeStream = null, dbTextLengths = emptyMap())
        assertEquals(old, g2.overlay(null))
        val fresh = stream(id = "m2", content = "new")
        val g3 = g2.step("c1", activeStream = fresh, dbTextLengths = emptyMap())
        assertEquals(fresh, g3.overlay(null))
        val g4 = g3.step("c1", activeStream = null, dbTextLengths = emptyMap())
        assertEquals(fresh, g4.overlay(null))
    }

    @Test
    fun activeStreamAlwaysWinsOverGhost() {
        val ghost = stream(id = "m1", content = "ghost")
        val live = stream(id = "m1", content = "live")
        val g = StreamGhost(ghost, 0).step("c1", activeStream = live, dbTextLengths = emptyMap())
        assertEquals(live, g.overlay(live))
    }
}
