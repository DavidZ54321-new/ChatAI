package com.zcw.chatai.data.ai

import com.zcw.chatai.data.doc.DocumentLimits
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.net.ChatRequestImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderDocumentsTest {

    private val image = ChatRequestImage(dataUrl = "data:image/jpeg;base64,AAAA")

    private fun docProvider(textById: Map<String, String?>): (Attachment) -> String? =
        { attachment -> textById[attachment.id] }

    @Test
    fun pureDocumentMessageRendersBlocks() {
        val history = listOf(
            message(
                "u1",
                Role.USER,
                "帮我看看",
                attachments = listOf(document("d1", "report.pdf", "共 8 页")),
            ),
        )
        val built = ContextBuilder.build(
            history,
            imageTurns = 1,
            imageProvider = { image },
            documentProvider = docProvider(mapOf("d1" to "第一章 内容")),
        )
        val content = built.single().content
        assertTrue(content.startsWith("帮我看看"))
        assertTrue(content.contains("【文档：report.pdf，共 8 页】"))
        assertTrue(content.contains("第一章 内容"))
        assertTrue(built.single().images.isEmpty())
        assertTrue(built.single().videos.isEmpty())
    }

    @Test
    fun missingSidecarYieldsDocumentMissingPlaceholder() {
        val history = listOf(
            message(
                "u1",
                Role.USER,
                "帮我看看",
                attachments = listOf(document("d1", "report.pdf", null)),
            ),
        )
        val built = ContextBuilder.build(
            history,
            imageTurns = 1,
            imageProvider = { image },
            documentProvider = docProvider(mapOf("d1" to null)),
        )
        assertTrue(built.single().content.contains(ContextBuilder.DOCUMENT_MISSING))
    }

    @Test
    fun olderDocumentsStillSendFullText() {
        val history = listOf(
            message("u1", Role.USER, "第一份", attachments = listOf(document("d1", "a.pdf", null))),
            message("a1", Role.ASSISTANT, "收到"),
            message("u2", Role.USER, "第二份", attachments = listOf(document("d2", "b.pdf", null))),
        )
        val built = ContextBuilder.build(
            history,
            imageTurns = 0,
            imageProvider = { image },
            documentProvider = docProvider(mapOf("d1" to "旧文本", "d2" to "新文本")),
        )
        // 历史文档也全文重发：保留轮次只管图片/视频，文档有多少拼多少。
        assertTrue(built[0].content.contains("【文档：a.pdf】"))
        assertTrue(built[0].content.contains("旧文本"))
        assertTrue(built[2].content.contains("【文档：b.pdf】"))
        assertTrue(built[2].content.contains("新文本"))
    }

    @Test
    fun mixedImageAndDocumentKeepsImageBehavior() {
        val history = listOf(
            message(
                "u1",
                Role.USER,
                "一起看",
                attachments = listOf(
                    imageAttachment("a1"),
                    document("d1", "a.pdf", null),
                ),
            ),
        )
        val built = ContextBuilder.build(
            history,
            imageTurns = 1,
            imageProvider = { image },
            documentProvider = docProvider(mapOf("d1" to "文档正文")),
        )
        // 图在、文档在：没有任何省略占位。
        assertEquals(1, built.single().images.size)
        assertTrue(built.single().content.contains("文档正文"))
        assertTrue(
            !built.single().content.contains(ContextBuilder.IMAGE_MISSING) &&
                !built.single().content.contains(ContextBuilder.DOCUMENT_MISSING),
        )
    }

    @Test
    fun combinedDocumentsPassThroughUntruncated() {
        val long = "文".repeat(30_000)
        val history = listOf(
            message(
                "u1",
                Role.USER,
                "",
                attachments = listOf(
                    document("d1", "a.pdf", null),
                    document("d2", "b.pdf", null),
                ),
            ),
        )
        val built = ContextBuilder.build(
            history,
            imageTurns = 1,
            imageProvider = { image },
            documentProvider = docProvider(mapOf("d1" to long, "d2" to long)),
        )
        val content = built.single().content
        // 两份 3 万字逐字都在，没有任何截断标记。
        assertEquals("【文档：a.pdf】\n$long\n\n【文档：b.pdf】\n$long", content)
        assertFalse(content.contains("已截断"))
    }

    @Test
    fun missingDocumentStillFlaggedWhenImagePresent() {
        // 图在但文档 sidecar 丢了：文档缺失必须亮牌，不能因为有图就静默吞掉。
        val history = listOf(
            message(
                "u1",
                Role.USER,
                "一起看",
                attachments = listOf(
                    imageAttachment("a1"),
                    document("d1", "a.pdf", null),
                ),
            ),
        )
        val built = ContextBuilder.build(
            history,
            imageTurns = 1,
            imageProvider = { image },
            documentProvider = docProvider(mapOf("d1" to null)),
        )
        assertEquals(1, built.single().images.size)
        assertTrue(built.single().content.contains(ContextBuilder.DOCUMENT_MISSING))
    }

    @Test
    fun displayNameFallsBackToFileName() {
        val blocks = ContextBuilder.documentBlocks(
            listOf(document("d1", null, null).copy(relativePath = "attachments/c/d1.pdf")),
            docProvider(mapOf("d1" to "正文")),
        )
        assertTrue(blocks.contains("【文档：d1.pdf】"))
    }

    private fun document(id: String, displayName: String?, meta: String?) = Attachment(
        id = id,
        kind = AttachmentKind.DOCUMENT,
        relativePath = "attachments/c/$id.pdf",
        mimeType = "application/pdf",
        width = 0,
        height = 0,
        sizeBytes = 1000,
        extractedPath = "attachments/c/$id.txt",
        extractedMeta = meta,
        displayName = displayName,
    )

    private fun imageAttachment(id: String) = Attachment(
        id = id,
        kind = AttachmentKind.IMAGE,
        relativePath = "attachments/c/$id.jpg",
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        sizeBytes = 1000,
    )

    private fun message(
        id: String,
        role: Role,
        content: String,
        attachments: List<Attachment> = emptyList(),
    ) = Message(
        id = id,
        conversationId = "c1",
        role = role,
        content = content,
        status = MessageStatus.COMPLETE,
        errorMessage = null,
        reasoningContent = null,
        seq = id.hashCode().toLong(),
        model = "deepseek-flash",
        promptTokens = null,
        completionTokens = null,
        attachments = attachments,
        toolCalls = emptyList(),
        toolCallId = null,
        toolResult = null,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
