package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolStatus
import com.zcw.chatai.data.net.ChatRequestImage
import com.zcw.chatai.data.net.ChatRequestVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {

    private val image = ChatRequestImage(dataUrl = "data:image/jpeg;base64,AAAA")

    private val video = ChatRequestVideo(url = "oss://dashscope-instant/x/v.mp4", isOss = true)

    private fun imageProvider(attachment: Attachment): ChatRequestImage? =
        if (attachment.relativePath.endsWith("missing.jpg")) null else image

    /** 回归：图片编号与 find_similar_images 的 image_index 必须基于同一份出站窗口，长会话截断后不会错位。 */
    @Test
    fun usableHistoryIsTheWindowUsedForImageNumbering() {
        val history = buildList {
            add(message("old", Role.USER, "旧图", attachments = listOf(attachment("a0", "attachments/c/a0.jpg"))))
            repeat(ContextBuilder.MAX_MESSAGES + 5) { add(message("m$it", Role.USER, "内容$it")) }
        }
        val windowed = ContextBuilder.usableHistory(history)
        assertEquals(ContextBuilder.MAX_MESSAGES, windowed.size)
        assertTrue(windowed.none { it.id == "old" })

        // 全量历史里有 1 张图，但窗口里 0 张——工具必须按窗口取，否则编号会指向模型没看到的图。
        // 轮次表按全量算（生产口径），窗口里没图就是没图，不回落、不错位。
        val turns = ToolImageInventory.turnNumbers(history)
        assertEquals(1, ToolImageInventory.visibleImages(history, retainTurns = -1, turns).size)
        assertTrue(ToolImageInventory.visibleImages(windowed, retainTurns = -1, turns).isEmpty())
    }

    /** 超长会话被窗口截断后，窗口内图片的标注仍是全量历史里的绝对轮次（KV 前缀缓存不被改写）。 */
    @Test
    fun truncatedHistoryKeepsAbsoluteImageTurns() {
        val history = buildList {
            add(message("u_old", Role.USER, "旧图", attachments = listOf(attachment("a0", "attachments/c/a0.jpg"))))
            repeat(ContextBuilder.MAX_MESSAGES + 5) { add(message("pad$it", Role.USER, "内容$it")) }
            add(message("u_new", Role.USER, "新图", attachments = listOf(attachment("a1", "attachments/c/a1.jpg"))))
        }
        val built = ContextBuilder.build(history, imageTurns = -1, imageProvider = ::imageProvider)
        val labels = built.flatMap { it.images }.mapNotNull { it.label }
        // 旧图被截掉，只剩新图；轮次是全量里的绝对值（最后一条 user），不是窗口内的 1。
        assertEquals(1, labels.size)
        val lastUserTurn = history.count { it.role == Role.USER }
        assertEquals("[Image 1 | turn $lastUserTurn 1/1]", labels.single())
    }

    @Test
    fun keepsOnlyRecentMessages() {
        val history = (1..60).map { message("m$it", Role.USER, "内容$it") }
        val built = ContextBuilder.build(history, imageTurns = 1, imageProvider = { image })
        assertEquals(ContextBuilder.MAX_MESSAGES, built.size)
        assertEquals("内容21", built.first().content)
        assertEquals("内容60", built.last().content)
    }

    @Test
    fun dropsStreamingPlaceholdersAndEmptyMessages() {
        val history = listOf(
            message("u1", Role.USER, "有内容"),
            message("a1", Role.ASSISTANT, "", status = MessageStatus.STREAMING),
            message("a2", Role.ASSISTANT, ""),
            message("u2", Role.SYSTEM, "系统消息"),
        )
        val built = ContextBuilder.build(history, imageTurns = 1, imageProvider = { image })
        assertEquals(1, built.size)
        assertEquals("有内容", built.single().content)
        assertEquals("user", built.single().role)
    }

    @Test
    fun demotesOlderImagesToPlaceholderInOutgoingText() {
        val history = listOf(
            message("u1", Role.USER, "第一张图", attachments = listOf(attachment("a1", "attachments/c/a1.jpg"))),
            message("a1", Role.ASSISTANT, "看到了"),
            message("u2", Role.USER, "第二张图", attachments = listOf(attachment("a2", "attachments/c/a2.jpg"))),
            message("a2", Role.ASSISTANT, "嗯"),
            message("u3", Role.USER, "第三张图", attachments = listOf(attachment("a3", "attachments/c/a3.jpg"))),
        )
        val built = ContextBuilder.build(history, imageTurns = 1, imageProvider = ::imageProvider)

        val first = built.first()
        assertTrue(first.images.isEmpty())
        assertTrue(first.content, first.content.contains(ContextBuilder.IMAGE_OMITTED))
        assertTrue(first.content.startsWith("第一张图"))

        assertEquals(1, built[2].images.size)
        assertEquals(1, built[4].images.size)
    }

    /** 回归：当前轮的图永远保留——即使配置成「只发当前轮」也不能丢本轮内容。 */
    @Test
    fun imageTurnsZeroStillSendsTheCurrentImage() {
        val history = listOf(
            message("u1", Role.USER, "图", attachments = listOf(attachment("a1", "attachments/c/a1.jpg"))),
        )
        val built = ContextBuilder.build(history, imageTurns = 0, imageProvider = ::imageProvider)
        assertEquals(1, built.single().images.size)
        assertFalse(built.single().content.contains(ContextBuilder.IMAGE_OMITTED))
    }

    @Test
    fun imageTurnsZeroOmitsOlderImagesButKeepsCurrent() {
        val history = listOf(
            message("u1", Role.USER, "图1", attachments = listOf(attachment("a1", "attachments/c/a1.jpg"))),
            message("a1", Role.ASSISTANT, "看到了"),
            message("u2", Role.USER, "图2", attachments = listOf(attachment("a2", "attachments/c/a2.jpg"))),
        )
        val built = ContextBuilder.build(history, imageTurns = 0, imageProvider = ::imageProvider)
        assertTrue(built.first().images.isEmpty())
        assertTrue(built.first().content.contains(ContextBuilder.IMAGE_OMITTED))
        assertEquals(1, built.last().images.size)
    }

    @Test
    fun imageTurnsMinusOneSendsEverything() {
        val history = listOf(
            message("u1", Role.USER, "图1", attachments = listOf(attachment("a1", "attachments/c/a1.jpg"))),
            message("u2", Role.USER, "图2", attachments = listOf(attachment("a2", "attachments/c/a2.jpg"))),
        )
        val built = ContextBuilder.build(history, imageTurns = -1, imageProvider = ::imageProvider)
        assertTrue(built.all { it.images.size == 1 })
        assertFalse(built.any { it.content.contains(ContextBuilder.IMAGE_OMITTED) })
    }

    /** 每张出站图片都带全局编号 + 轮次来源标注（英文），避免历史图与本轮图混淆。 */
    @Test
    fun labelsEveryVisibleImageWithGlobalIndexAndTurn() {
        val history = listOf(
            message("u1", Role.USER, "上一轮", attachments = listOf(attachment("a1", "attachments/c/a1.jpg"))),
            message("a1", Role.ASSISTANT, "看到了"),
            message(
                "u2",
                Role.USER,
                "本轮",
                attachments = listOf(attachment("a2", "attachments/c/a2.jpg"), attachment("a3", "attachments/c/a3.jpg")),
            ),
        )
        val built = ContextBuilder.build(history, imageTurns = 1, imageProvider = ::imageProvider)
        val previous = built.first().images.single()
        val current = built.last().images
        assertEquals("[Image 1 | turn 1 1/1]", previous.label)
        assertEquals("[Image 2 | turn 2 1/2]", current[0].label)
        assertEquals("[Image 3 | turn 2 2/2]", current[1].label)
    }

    @Test
    fun missingFilesBecomeUnavailablePlaceholder() {
        val history = listOf(
            message("u1", Role.USER, "图丢了", attachments = listOf(attachment("a1", "attachments/c/missing.jpg"))),
        )
        val built = ContextBuilder.build(history, imageTurns = 1, imageProvider = ::imageProvider)
        assertTrue(built.single().images.isEmpty())
        assertTrue(built.single().content.contains(ContextBuilder.IMAGE_MISSING))
    }

    @Test
    fun textWithoutImagesKeepsPlainStringContent() {
        val built = ContextBuilder.build(
            listOf(message("u1", Role.USER, "纯文本")),
            imageTurns = 1,
            imageProvider = ::imageProvider,
        )
        assertTrue(built.single().images.isEmpty())
        assertEquals("纯文本", built.single().content)
    }

    @Test
    fun keepsToolMessagesAndCarriesToolCallId() {
        val calls = listOf(ToolCall("call_1", "web_search", "{\"query\":\"x\"}"))
        val messages = listOf(
            message(id = "a1", role = Role.ASSISTANT, content = "", toolCalls = calls, reasoningContent = "想"),
            message(id = "t1", role = Role.TOOL, content = "结果", toolCallId = "call_1"),
        )
        val built = ContextBuilder.build(messages, imageTurns = 0, imageProvider = { null })
        assertEquals(listOf("assistant", "tool"), built.map { it.role })
        assertEquals(calls, built[0].toolCalls)
        assertEquals("想", built[0].reasoning)
        assertEquals("call_1", built[1].toolCallId)
    }

    @Test
    fun plainAssistantDoesNotEchoReasoning() {
        val messages = listOf(message(id = "a1", role = Role.ASSISTANT, content = "答案", reasoningContent = "想"))
        val built = ContextBuilder.build(messages, imageTurns = 0, imageProvider = { null })
        assertNull(built.single().reasoning)
    }

    @Test
    fun dropsLeadingOrphanToolMessageAfterWindowTruncation() {
        val history = buildList {
            add(
                message(
                    id = "a0",
                    role = Role.ASSISTANT,
                    content = "",
                    toolCalls = listOf(ToolCall("call_1", "web_search", "{}")),
                ),
            )
            add(message(id = "t0", role = Role.TOOL, content = "结果", toolCallId = "call_1"))
            repeat(39) { add(message(id = "u$it", role = Role.USER, content = "内容$it")) }
        }
        val built = ContextBuilder.build(history, imageTurns = 0, imageProvider = { null })
        assertEquals(ContextBuilder.MAX_MESSAGES - 1, built.size)
        assertFalse(built.first().role == "tool")
        assertTrue(built.none { it.role == "tool" })
    }

    @Test
    fun toolResultBlankTextFallsBackToNonBlankContent() {
        val messages = listOf(
            message(
                id = "a1",
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall("call_1", "web_search", "{}")),
            ),
            message(
                id = "t1",
                role = Role.TOOL,
                content = "真实结果",
                toolCallId = "call_1",
                toolResult = ToolResult(status = ToolStatus.OK, detail = "d", text = ""),
            ),
        )
        val built = ContextBuilder.build(messages, imageTurns = 0, imageProvider = { null })
        assertEquals(2, built.size)
        assertEquals("真实结果", built[1].content)
    }

    @Test
    fun toolResultModelNoteIsAppendedForTheModel() {
        val messages = listOf(
            message(
                id = "a1",
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall("call_1", "web_search", "{}")),
            ),
            message(
                id = "t1",
                role = Role.TOOL,
                content = "结果",
                toolCallId = "call_1",
                toolResult = ToolResult(
                    status = ToolStatus.OK,
                    detail = "d",
                    text = "结果",
                    modelNote = "[Tool budget] 1 more tool round(s) available this turn.",
                ),
            ),
        )
        val built = ContextBuilder.build(messages, imageTurns = 0, imageProvider = { null })
        assertEquals(
            "结果\n\n[Tool budget] 1 more tool round(s) available this turn.",
            built[1].content,
        )
    }

    @Test
    fun blankToolResultIsKeptWithPlaceholderToPreservePairing() {
        val messages = listOf(
            message(
                id = "a1",
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall("call_1", "web_search", "{}")),
            ),
            message(
                id = "t1",
                role = Role.TOOL,
                content = "",
                toolCallId = "call_1",
                toolResult = ToolResult(status = ToolStatus.OK, detail = "d", text = ""),
            ),
        )
        val built = ContextBuilder.build(messages, imageTurns = 0, imageProvider = { null })
        assertEquals(listOf("assistant", "tool"), built.map { it.role })
        assertEquals("call_1", built[1].toolCallId)
        assertTrue(built[1].content.isNotBlank())
        assertEquals(ContextBuilder.TOOL_EMPTY, built[1].content)
    }

    @Test
    fun toolAnswersArePulledUpRightAfterTheirAssistantWhenRowsAreOutOfOrder() {
        val messages = listOf(
            message(
                id = "a1",
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall("call_1", "web_search", "{}"),
                    ToolCall("call_2", "web_search", "{}"),
                ),
            ),
            message(id = "t1", role = Role.TOOL, content = "结果1", toolCallId = "call_1"),
            message(id = "u1", role = Role.USER, content = "你继续"),
            message(id = "t2", role = Role.TOOL, content = "结果2", toolCallId = "call_2"),
        )
        val built = ContextBuilder.build(messages, imageTurns = 0, imageProvider = { null })
        assertEquals(listOf("assistant", "tool", "tool", "user"), built.map { it.role })
        assertEquals(listOf("call_1", "call_2"), built.filter { it.role == "tool" }.map { it.toolCallId })
        assertEquals("结果2", built[2].content)
        assertEquals("你继续", built[3].content)
    }

    @Test
    fun unansweredToolCallGetsSynthesizedPlaceholderRightAfterTheAssistant() {
        val messages = listOf(
            message(
                id = "a1",
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall("call_1", "web_search", "{}"),
                    ToolCall("call_2", "web_search", "{}"),
                ),
            ),
            message(id = "t1", role = Role.TOOL, content = "结果1", toolCallId = "call_1"),
            message(id = "u1", role = Role.USER, content = "你继续"),
        )
        val built = ContextBuilder.build(messages, imageTurns = 0, imageProvider = { null })
        assertEquals(listOf("assistant", "tool", "tool", "user"), built.map { it.role })
        assertEquals("call_2", built[2].toolCallId)
        assertEquals(ContextBuilder.TOOL_EMPTY, built[2].content)
    }

    @Test
    fun orphanToolMessageInTheMiddleIsDropped() {
        val messages = listOf(
            message(id = "u1", role = Role.USER, content = "你好"),
            message(id = "t1", role = Role.TOOL, content = "孤儿", toolCallId = "call_x"),
            message(id = "a1", role = Role.ASSISTANT, content = "回答"),
        )
        val built = ContextBuilder.build(messages, imageTurns = 0, imageProvider = { null })
        assertEquals(listOf("user", "assistant"), built.map { it.role })
    }

    @Test
    fun blankReasoningOnToolCallAssistantIsNotForwarded() {
        val messages = listOf(
            message(
                id = "a1",
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall("call_1", "web_search", "{}")),
                reasoningContent = "",
            ),
        )
        val built = ContextBuilder.build(messages, imageTurns = 0, imageProvider = { null })
        // 缺应答的 tool_call 会合成占位应答，但 assistant 本体的空思考不回传。
        assertEquals(listOf("assistant", "tool"), built.map { it.role })
        assertNull(built.first().reasoning)
    }

    @Test
    fun videoOnlyMessageIsEncodedAsVideoBlock() {
        val history = listOf(
            message(
                "u1",
                Role.USER,
                "看看这个视频",
                attachments = listOf(videoAttachment("v1")),
            ),
        )
        val built = ContextBuilder.build(
            history,
            imageTurns = 1,
            imageProvider = { null },
            videoProvider = { video },
        )
        assertEquals(listOf(video), built.single().videos)
        assertTrue(built.single().images.isEmpty())
        assertEquals("看看这个视频", built.single().content)
    }

    @Test
    fun olderVideoIsDemotedToVideoPlaceholder() {
        val history = listOf(
            message("u1", Role.USER, "第一个视频", attachments = listOf(videoAttachment("v1"))),
            message("a1", Role.ASSISTANT, "看到了"),
            message("u2", Role.USER, "第二个视频", attachments = listOf(videoAttachment("v2"))),
            message("a2", Role.ASSISTANT, "嗯"),
            message("u3", Role.USER, "第三个视频", attachments = listOf(videoAttachment("v3"))),
        )
        val built = ContextBuilder.build(
            history,
            imageTurns = 1,
            imageProvider = { null },
            videoProvider = { video },
        )
        assertTrue(built.first().videos.isEmpty())
        assertTrue(built.first().content, built.first().content.contains(ContextBuilder.VIDEO_OMITTED))
        assertEquals(1, built[2].videos.size)
        assertEquals(1, built[4].videos.size)
    }

    @Test
    fun missingVideoFileBecomesUnavailablePlaceholder() {
        val history = listOf(
            message("u1", Role.USER, "视频丢了", attachments = listOf(videoAttachment("v1"))),
        )
        val built = ContextBuilder.build(
            history,
            imageTurns = 1,
            imageProvider = { null },
            videoProvider = { null },
        )
        assertTrue(built.single().videos.isEmpty())
        assertTrue(built.single().content.contains(ContextBuilder.VIDEO_MISSING))
    }

    @Test
    fun imageAndVideoInOneMessageAreBothEncoded() {
        val history = listOf(
            message(
                "u1",
                Role.USER,
                "图文视频",
                attachments = listOf(attachment("a1", "attachments/c/a1.jpg"), videoAttachment("v1")),
            ),
        )
        val built = ContextBuilder.build(
            history,
            imageTurns = 1,
            imageProvider = { image },
            videoProvider = { video },
        )
        assertEquals(1, built.single().images.size)
        assertEquals(1, built.single().videos.size)
    }

    private fun attachment(id: String, path: String) = Attachment(
        id = id,
        kind = AttachmentKind.IMAGE,
        relativePath = path,
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        sizeBytes = 1000,
    )

    private fun videoAttachment(id: String) = Attachment(
        id = id,
        kind = AttachmentKind.VIDEO,
        relativePath = "attachments/c/$id.mp4",
        mimeType = "video/mp4",
        width = 320,
        height = 240,
        sizeBytes = 6L * 1024 * 1024,
        durationMs = 3_000,
    )

    @Test
    fun envNoteIsAppendedAsTrailingSystemMessage() {
        val history = listOf(
            message("u1", Role.USER, "你好"),
            message("a1", Role.ASSISTANT, "你好！"),
        )
        val built = ContextBuilder.build(
            history,
            imageTurns = 1,
            imageProvider = { image },
            envNote = "当前时间：2026年9月20日 星期日 14:32:05",
        )
        assertEquals(3, built.size)
        val last = built.last()
        assertEquals("system", last.role)
        assertEquals("当前时间：2026年9月20日 星期日 14:32:05", last.content)
        // 历史本身不受影响。
        assertEquals("你好", built.first().content)
    }

    @Test
    fun envNoteSurvivesWindowTruncation() {
        val history = (1..60).map { message("m$it", Role.USER, "内容$it") }
        val built = ContextBuilder.build(
            history,
            imageTurns = 1,
            imageProvider = { image },
            envNote = "当前时间：2026年9月20日 星期日 14:32:05",
        )
        // 窗口 40 条 + 尾条 1 条：尾条在截断之后加，永远占末位。
        assertEquals(ContextBuilder.MAX_MESSAGES + 1, built.size)
        assertEquals("system", built.last().role)
    }

    @Test
    fun blankOrNullEnvNoteAppendsNothing() {
        val history = listOf(message("u1", Role.USER, "你好"))
        val without = ContextBuilder.build(history, imageTurns = 1, imageProvider = { image })
        val blank = ContextBuilder.build(
            history,
            imageTurns = 1,
            imageProvider = { image },
            envNote = "   ",
        )
        assertEquals(without, blank)
        assertEquals(1, without.size)
    }

    private fun message(
        id: String,
        role: Role,
        content: String,
        attachments: List<Attachment> = emptyList(),
        status: MessageStatus = MessageStatus.COMPLETE,
        toolCalls: List<ToolCall> = emptyList(),
        toolCallId: String? = null,
        toolResult: ToolResult? = null,
        reasoningContent: String? = null,
    ) = Message(
        id = id,
        conversationId = "c1",
        role = role,
        content = content,
        status = status,
        errorMessage = null,
        reasoningContent = reasoningContent,
        seq = id.hashCode().toLong(),
        model = "deepseek-flash",
        promptTokens = null,
        completionTokens = null,
        attachments = attachments,
        toolCalls = toolCalls,
        toolCallId = toolCallId,
        toolResult = toolResult,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
