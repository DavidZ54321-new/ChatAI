package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchCopyTest {

    private fun message(
        seq: Long,
        role: Role,
        content: String = "",
        toolCalls: List<ToolCall> = emptyList(),
        toolCallId: String? = null,
        status: MessageStatus = MessageStatus.COMPLETE,
        attachments: List<Attachment> = emptyList(),
    ) = Message(
        id = "m$seq",
        conversationId = "c1",
        role = role,
        content = content,
        status = status,
        errorMessage = null,
        reasoningContent = null,
        seq = seq,
        model = null,
        promptTokens = null,
        completionTokens = null,
        attachments = attachments,
        toolCalls = toolCalls,
        toolCallId = toolCallId,
        createdAt = seq,
        updatedAt = seq,
    )

    private fun call(id: String) = ToolCall(id, "web_search", "{}")

    private fun user(seq: Long, text: String = "问题$seq") = message(seq, Role.USER, text)

    private fun answer(seq: Long, text: String = "回答$seq") = message(seq, Role.ASSISTANT, text)

    @Test
    fun keepsPrefixUpToSeqAndRenumbersContiguously() {
        val messages = listOf(user(1), answer(2), user(3), answer(4), user(5))
        val prefix = BranchCopy.prefix(messages, 4)
        assertEquals(listOf(1L, 2L, 3L, 4L), prefix.map { it.seq })
        assertEquals(listOf("m1", "m2", "m3", "m4"), prefix.map { it.id })
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT), prefix.map { it.role })
    }

    /** 原 seq 可能有空洞（删过消息），复制出来的必须是 1..N。 */
    @Test
    fun renumbersAcrossGapsInSourceSeq() {
        val messages = listOf(user(1), answer(4), message(9, Role.ASSISTANT, "结尾"))
        assertEquals(listOf(1L, 2L, 3L), BranchCopy.prefix(messages, 9).map { it.seq })
    }

    @Test
    fun answersInsideTheCutKeepTheWholeToolGroup() {
        val messages = listOf(
            user(1),
            message(2, Role.ASSISTANT, toolCalls = listOf(call("a"))),
            message(3, Role.TOOL, "结果", toolCallId = "a"),
            answer(4, "最终回答"),
        )
        val prefix = BranchCopy.prefix(messages, 4)
        assertEquals(4, prefix.size)
        assertEquals(listOf("a"), prefix[1].toolCalls.map { it.id })
        assertEquals("a", prefix[2].toolCallId)
    }

    /**
     * 截断点落在「assistant 请求了工具、应答还没复制过来」的位置时，
     * 那条 assistant 不能再宣称要调工具，否则新会话首次请求 400。
     */
    @Test
    fun stripsToolCallsWhoseAnswersFallOutsideTheCut() {
        val messages = listOf(
            user(1),
            message(2, Role.ASSISTANT, toolCalls = listOf(call("a"))),
            message(3, Role.TOOL, "结果", toolCallId = "a"),
            message(4, Role.ASSISTANT, toolCalls = listOf(call("b"))),
            message(5, Role.TOOL, "结果", toolCallId = "b"),
            answer(6, "最终回答"),
        )
        val prefix = BranchCopy.prefix(messages, 4)
        // 第 4 步的调用被剥掉（应答在截断点之后），它没有正文 → 整行也被过滤掉；
        // 第 2 步的调用有配对应答，整组保留。
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL), prefix.map { it.role })
        assertEquals(listOf("a"), prefix[1].toolCalls.map { it.id })
        assertEquals("a", prefix[2].toolCallId)
    }

    /** 被剥掉调用之后变成无主的 TOOL 行要一起丢掉（否则界面上会多出一个孤立工具块）。 */
    @Test
    fun dropsToolRowsThatLoseTheirAssistant() {
        val messages = listOf(
            user(1),
            message(2, Role.ASSISTANT, "我先查一下", toolCalls = listOf(call("a"), call("b"))),
            message(3, Role.TOOL, "结果a", toolCallId = "a"),
            message(4, Role.TOOL, "结果b", toolCallId = "b"),
        )
        val prefix = BranchCopy.prefix(messages, 3)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), prefix.map { it.role })
        assertTrue(prefix.all { it.toolCalls.isEmpty() })
        assertEquals("我先查一下", prefix[1].content)
    }

    /** 只请求工具、没有正文的那一步，被剥掉调用之后就是空行 → 整行都不复制。 */
    @Test
    fun stripsEmptyAssistantStepEntirely() {
        val messages = listOf(
            user(1),
            message(2, Role.ASSISTANT, toolCalls = listOf(call("a"))),
            message(3, Role.TOOL, "结果a", toolCallId = "a"),
        )
        assertEquals(listOf(Role.USER), BranchCopy.prefix(messages, 2).map { it.role })
    }

    /**
     * 核心不变量：**任何**截断点复制出来的历史都必须自洽——
     * 每个 tool_calls 都有配对应答，每条 TOOL 行都有发起它的 assistant。
     * （只要有一条不成立，新会话的第一次请求就会被服务端 400 掉。）
     */
    @Test
    fun copiedHistoryIsSelfConsistentAtEveryCut() {
        val messages = listOf(
            user(1),
            message(2, Role.ASSISTANT, toolCalls = listOf(call("a"), call("b"))),
            message(3, Role.TOOL, "结果a", toolCallId = "a"),
            message(4, Role.TOOL, "结果b", toolCallId = "b"),
            answer(5, "最终回答"),
        )
        for (cut in 1L..5L) {
            val prefix = BranchCopy.prefix(messages, cut)
            val answered = prefix.mapNotNull { it.toolCallId }.toSet()
            val owned = prefix.flatMap { it.toolCalls }.map { it.id }.toSet()
            assertTrue("cut=$cut 有未应答的调用", prefix.flatMap { it.toolCalls }.all { it.id in answered })
            assertTrue(
                "cut=$cut 有孤立的工具行",
                prefix.filter { it.role == Role.TOOL }.all { it.toolCallId in owned },
            )
            assertEquals("cut=$cut seq 不是 1..N", (1L..prefix.size).toList(), prefix.map { it.seq })
        }
    }

    @Test
    fun excludesStreamingPlaceholderAndBlankRows() {
        val messages = listOf(
            user(1),
            message(2, Role.ASSISTANT, "", status = MessageStatus.STREAMING),
            answer(3, "真正的回答"),
        )
        val prefix = BranchCopy.prefix(messages, 3)
        assertEquals(listOf("m1", "m3"), prefix.map { it.id })
    }

    @Test
    fun excludesSystemRowsAndKeepsAttachments() {
        val attachment = Attachment(
            id = "att-1",
            kind = AttachmentKind.IMAGE,
            relativePath = "attachments/c1/att-1.jpg",
            mimeType = "image/jpeg",
            width = 10,
            height = 10,
            sizeBytes = 100,
        )
        val messages = listOf(
            message(1, Role.SYSTEM, "系统提示"),
            user(2, "看图"),
            message(3, Role.ASSISTANT, "收到", attachments = listOf(attachment)),
        )
        val prefix = BranchCopy.prefix(messages, 3)
        assertEquals(listOf("m2", "m3"), prefix.map { it.id })
        assertEquals(listOf(attachment), prefix[1].attachments)
    }

    @Test
    fun returnsEmptyWhenNothingPrecedesTheCut() {
        assertEquals(emptyList<Message>(), BranchCopy.prefix(listOf(user(5), answer(6)), 3))
        assertEquals(emptyList<Message>(), BranchCopy.prefix(emptyList(), 10))
    }
}
