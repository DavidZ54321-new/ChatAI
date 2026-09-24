package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEditTest {

    private fun attachment(id: String, kind: AttachmentKind = AttachmentKind.IMAGE) = Attachment(
        id = id,
        kind = kind,
        relativePath = "attachments/conv/$id.jpg",
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        sizeBytes = 1024,
    )

    private fun pending(id: String, kind: AttachmentKind = AttachmentKind.IMAGE) =
        PendingAttachment(
            id = id,
            thumbnailPath = "attachments/conv/$id.thumb.jpg",
            attachment = attachment(id, kind),
        )

    private fun draft(
        text: String = "改后的文本",
        attachments: List<PendingAttachment> = emptyList(),
        importedIds: Set<String> = emptySet(),
        laterCount: Int = 0,
    ) = EditDraft(
        messageId = "m1",
        conversationId = "c1",
        text = text,
        attachments = attachments,
        importedIds = importedIds,
        model = "model-a",
        providerId = "deepseek",
        webSearchEnabled = false,
        laterCount = laterCount,
    )

    private fun message(id: String, role: Role = Role.ASSISTANT) = ChatMessageItem(
        id = id,
        role = role,
        content = "内容",
    )

    @Test
    fun addMarksAttachmentAsImported() {
        val added = MessageEdit.add(draft(), pending("a"))

        assertEquals(listOf("a"), added.attachments.map { it.id })
        assertEquals(setOf("a"), added.importedIds)
    }

    @Test
    fun removingImportedAttachmentDeletesItsFileNow() {
        val original = draft(attachments = listOf(pending("a")), importedIds = setOf("a"))

        val removal = MessageEdit.remove(original, "a")

        assertTrue(removal.draft.attachments.isEmpty())
        assertTrue(removal.draft.importedIds.isEmpty())
        assertEquals(listOf("a"), removal.deleteNow.map { it.id })
    }

    /** 原有附件仍被消息引用：移除它只是「这次重发不带它」，文件要等确认重发后由仓库层删。 */
    @Test
    fun removingExistingAttachmentDeletesNothingNow() {
        val original = draft(attachments = listOf(pending("a"), pending("b")), importedIds = setOf("b"))

        val removal = MessageEdit.remove(original, "a")

        assertEquals(listOf("b"), removal.draft.attachments.map { it.id })
        assertTrue(removal.deleteNow.isEmpty())
    }

    @Test
    fun removingUnknownAttachmentIsNoOp() {
        val original = draft(attachments = listOf(pending("a")))

        val removal = MessageEdit.remove(original, "nope")

        assertEquals(original, removal.draft)
        assertTrue(removal.deleteNow.isEmpty())
    }

    /** 取消编辑只删本次新导入的文件；原有附件的文件绝不能碰（静默数据丢失的入口）。 */
    @Test
    fun discardFilesOnlyCoversImportedAttachments() {
        val draft = draft(
            attachments = listOf(pending("old-1"), pending("new-1"), pending("new-2")),
            importedIds = setOf("new-1", "new-2"),
        )

        assertEquals(listOf("new-1", "new-2"), MessageEdit.discardFiles(draft).map { it.id })
    }

    @Test
    fun discardFilesIsEmptyWhenNothingWasImported() {
        val draft = draft(attachments = listOf(pending("old-1")))

        assertTrue(MessageEdit.discardFiles(draft).isEmpty())
    }

    /** 已被移除的新导入附件不该再被 discardFiles 重复删一次（remove 时已经删过）。 */
    @Test
    fun discardFilesSkipsAlreadyRemovedImports() {
        val draft = draft(attachments = listOf(pending("old-1")), importedIds = setOf("new-1"))

        assertTrue(MessageEdit.discardFiles(draft).isEmpty())
    }

    /** 一轮 = 一个 agent 回合（思考 + 工具调用 + 回答整组），不按原始行数算。 */
    @Test
    fun laterCountCountsTurnsNotRawRows() {
        // u1 的回答是 a1(工具调用) + t1(工具结果) + a2(收尾)：三行只算 1 轮。
        val singleTurn = listOf(
            message("u1", Role.USER),
            message("a1"),
            message("t1", Role.TOOL),
            message("a2"),
        )

        assertEquals(1, MessageEdit.laterCount(singleTurn, "u1"))
        assertEquals(0, MessageEdit.laterCount(singleTurn, "a2"))
        assertEquals(0, MessageEdit.laterCount(singleTurn, "missing"))
    }

    @Test
    fun laterCountCountsEachSubsequentTurn() {
        val messages = listOf(
            message("u1", Role.USER),
            message("a1"),
            message("t1", Role.TOOL),
            message("u2", Role.USER),
            message("a2"),
        )

        // u1 之后：a1+t1 一轮、u2+a2 一轮 → 2。
        assertEquals(2, MessageEdit.laterCount(messages, "u1"))
        // u2 之后：只剩 a2 一轮。
        assertEquals(1, MessageEdit.laterCount(messages, "u2"))
    }

    /** 末尾还没回答的用户消息也算一轮，免得「删了东西却说 0 轮」。 */
    @Test
    fun laterCountCountsTrailingUnansweredUserMessage() {
        val messages = listOf(
            message("u1", Role.USER),
            message("a1"),
            message("u2", Role.USER),
        )

        assertEquals(2, MessageEdit.laterCount(messages, "u1"))
    }

    @Test
    fun kindOfMimeClassifiesByMime() {
        assertEquals(AttachmentKind.IMAGE, MessageEdit.kindOfMime("image/png"))
        assertEquals(AttachmentKind.VIDEO, MessageEdit.kindOfMime("video/mp4"))
        assertEquals(AttachmentKind.AUDIO, MessageEdit.kindOfMime("audio/mpeg"))
        assertEquals(AttachmentKind.DOCUMENT, MessageEdit.kindOfMime("application/pdf"))
    }

    /** 回归：`application/ogg` 不以 `audio/` 开头，但它在 AUDIO_MIME_TYPES 里，必须归音频。 */
    @Test
    fun kindOfMimeTreatsApplicationOggAsAudio() {
        assertEquals(AttachmentKind.AUDIO, MessageEdit.kindOfMime("application/ogg"))
    }

    /** MIME 不可用时按扩展名兜底（少数 provider 不实现 getType）。 */
    @Test
    fun kindOfMimeFallsBackToFileNameWhenMimeIsUnusable() {
        assertEquals(AttachmentKind.IMAGE, MessageEdit.kindOfMime(null, "IMG_0001.JPG"))
        assertEquals(AttachmentKind.AUDIO, MessageEdit.kindOfMime(null, "voice.ogg"))
        assertEquals(AttachmentKind.VIDEO, MessageEdit.kindOfMime("application/octet-stream", "clip.mp4"))
        assertEquals(AttachmentKind.DOCUMENT, MessageEdit.kindOfMime(null, "notes.pdf"))
        assertEquals(AttachmentKind.DOCUMENT, MessageEdit.kindOfMime(null, null))
    }

    @Test
    fun canResendRequiresTextOrAttachment() {
        assertFalse(draft(text = "   ").canResend)
        assertTrue(draft(text = "  x  ").canResend)
        assertTrue(draft(text = "", attachments = listOf(pending("a"))).canResend)
    }
}
