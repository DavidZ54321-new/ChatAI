package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind

/**
 * 只上传附件、正文留空时补上的默认提示词。
 * 作用有二：给模型一句明确指令；让这条用户消息仍有正文气泡可点（点气泡进编辑）。
 */
const val DEFAULT_ATTACHMENT_PROMPT = "帮我分别解读这些文件"

/** 默认提示词的判定与派生（纯函数，便于 JVM 单测）。 */
object AttachmentPrompt {

    /** 这段正文是不是那条默认提示词（标题/摘要不该把它当成用户真写的正文）。 */
    fun isDefault(text: String): Boolean = text == DEFAULT_ATTACHMENT_PROMPT

    /** 默认提示词且确有附件：这才是「用户没写正文」的占位，自己手打这句又不带附件不算。 */
    fun isDefaultPlaceholder(text: String, attachments: List<Attachment>): Boolean =
        attachments.isNotEmpty() && isDefault(text)

    /** 出站/落库正文：正文为空但有附件时补默认提示词，否则原样。调用方已 trim。 */
    fun effectiveText(trimmed: String, attachments: List<Attachment>): String =
        if (trimmed.isEmpty() && attachments.isNotEmpty()) DEFAULT_ATTACHMENT_PROMPT else trimmed

    /** 取标题用的「用户真写的正文」：默认占位视作没写。 */
    fun realText(text: String, attachments: List<Attachment>): String =
        if (isDefaultPlaceholder(text, attachments)) "" else text

    /** 纯附件的类型旁白（首条标题 / 抽屉摘要共用）。 */
    fun kindLabel(attachments: List<Attachment>): String = when {
        attachments.any { it.kind == AttachmentKind.DOCUMENT } -> "文档"
        attachments.any { it.kind == AttachmentKind.VIDEO } -> "视频"
        attachments.any { it.kind == AttachmentKind.AUDIO } -> "音频"
        else -> "图片"
    }
}
