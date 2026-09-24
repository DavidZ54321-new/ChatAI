package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPromptTest {

    @Test
    fun blankTextWithAttachmentsGetsTheDefaultPrompt() {
        assertEquals(
            DEFAULT_ATTACHMENT_PROMPT,
            AttachmentPrompt.effectiveText("", listOf(attachment(AttachmentKind.DOCUMENT))),
        )
    }

    @Test
    fun blankTextWithoutAttachmentsStaysBlank() {
        assertEquals("", AttachmentPrompt.effectiveText("", emptyList()))
    }

    @Test
    fun realTextIsKeptUnchanged() {
        assertEquals("帮我看看这个", AttachmentPrompt.effectiveText("帮我看看这个", listOf(attachment(AttachmentKind.IMAGE))))
    }

    @Test
    fun theDefaultPromptIsRecognised() {
        assertTrue(AttachmentPrompt.isDefault(DEFAULT_ATTACHMENT_PROMPT))
        assertFalse(AttachmentPrompt.isDefault("帮我分别解读这些文件。"))
        assertFalse(AttachmentPrompt.isDefault(""))
    }

    /** 默认占位不算「用户真写的正文」，标题/摘要要回落到类型旁白。 */
    @Test
    fun theDefaultPlaceholderIsTreatedAsEmptyForTitles() {
        val withFile = listOf(attachment(AttachmentKind.DOCUMENT))
        assertEquals("", AttachmentPrompt.realText(DEFAULT_ATTACHMENT_PROMPT, withFile))
        assertEquals("正文", AttachmentPrompt.realText("正文", withFile))
    }

    /** 自己手打这句又不带附件，是真正文，不能当占位吞掉。 */
    @Test
    fun theDefaultSentenceWithoutAttachmentsIsRealText() {
        assertEquals(
            DEFAULT_ATTACHMENT_PROMPT,
            AttachmentPrompt.realText(DEFAULT_ATTACHMENT_PROMPT, emptyList()),
        )
        assertFalse(AttachmentPrompt.isDefaultPlaceholder(DEFAULT_ATTACHMENT_PROMPT, emptyList()))
        assertTrue(AttachmentPrompt.isDefaultPlaceholder(DEFAULT_ATTACHMENT_PROMPT, listOf(attachment(AttachmentKind.IMAGE))))
    }

    @Test
    fun kindLabelPrefersDocumentThenVideoThenAudioThenImage() {
        assertEquals("文档", AttachmentPrompt.kindLabel(listOf(attachment(AttachmentKind.DOCUMENT), attachment(AttachmentKind.IMAGE))))
        assertEquals("视频", AttachmentPrompt.kindLabel(listOf(attachment(AttachmentKind.IMAGE), attachment(AttachmentKind.VIDEO))))
        assertEquals("音频", AttachmentPrompt.kindLabel(listOf(attachment(AttachmentKind.AUDIO))))
        assertEquals("图片", AttachmentPrompt.kindLabel(listOf(attachment(AttachmentKind.IMAGE))))
    }

    private fun attachment(kind: AttachmentKind) = Attachment(
        id = kind.name,
        kind = kind,
        relativePath = "attachments/c/${kind.name}.bin",
        mimeType = "application/octet-stream",
        width = 0,
        height = 0,
        sizeBytes = 1,
    )
}
