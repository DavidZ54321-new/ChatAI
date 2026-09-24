package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.doc.DocumentLabel
import java.io.File

/** 消息里一张图/一个视频/一段音频：缩略图给气泡，原文件给全屏预览/播放器。 */
data class MessageImage(
    val id: String,
    val thumbnailPath: String,
    val fullPath: String,
    val width: Int,
    val height: Int,
    val kind: AttachmentKind = AttachmentKind.IMAGE,
    val durationMs: Long? = null,
    /** 音频/文档的展示名（音频预览页要显示文件名）。 */
    val displayName: String? = null,
    /** 文档类型戳（如 PDF）：非空时缩略图显示字母。 */
    val label: String? = null,
    val mimeType: String? = null,
    val extractedPath: String? = null,
)

/** 待发送附件（已落私有目录，发送成功前可撤回并删文件）。 */
data class PendingAttachment(
    val id: String,
    val thumbnailPath: String,
    val attachment: Attachment,
)

internal fun PendingAttachment.toMessageImage(filesDir: File): MessageImage {
    val attachment = attachment
    return MessageImage(
        id = id,
        thumbnailPath = thumbnailPath,
        fullPath = File(filesDir, attachment.relativePath).absolutePath,
        width = attachment.width,
        height = attachment.height,
        kind = attachment.kind,
        durationMs = attachment.durationMs,
        displayName = attachment.displayName,
        label = when (attachment.kind) {
            AttachmentKind.DOCUMENT -> DocumentLabel.of(
                attachment.mimeType,
                attachment.displayName ?: attachment.relativePath,
            )
            AttachmentKind.AUDIO -> "AUDIO"
            else -> null
        },
        mimeType = attachment.mimeType,
        extractedPath = attachment.extractedPath?.let { File(filesDir, it).absolutePath },
    )
}

/**
 * 编辑用户消息的草稿。会话级语义：模型/供应商/🌐 的改动随「重新发送」一起写回会话，
 * 所以取消编辑不留任何副作用（草稿里的模型只影响这一次重发）。
 */
data class EditDraft(
    val messageId: String,
    val conversationId: String,
    val text: String,
    /** 草稿里的附件（原有 + 本次新导入），顺序即出站顺序。 */
    val attachments: List<PendingAttachment>,
    /** 本次新导入的附件 id：取消编辑要删掉这些文件；原有附件一律不动。 */
    val importedIds: Set<String> = emptySet(),
    val model: String,
    val providerId: String,
    val webSearchEnabled: Boolean,
    /** 这条消息之后会被删掉的「轮」数（一轮 = 一个 agent 回合，用于截断确认框文案）。 */
    val laterCount: Int,
    /**
     * 用户在弹层里动过模型/供应商/🌐（会话级绑定）。没动过就不写回会话——
     * 老会话的 `provider_id` 是空串（跟随激活供应商），随手改个错字不该把它钉死成当前供应商。
     */
    val bindingDirty: Boolean = false,
) {
    val canResend: Boolean
        get() = text.isNotBlank() || attachments.isNotEmpty()
}

data class ChatMessageItem(
    val id: String,
    val role: Role,
    val content: String,
    val reasoning: String? = null,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val errorMessage: String? = null,
    val model: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    /** 思考耗时（毫秒）；null = 未测量。 */
    val reasoningMs: Long? = null,
    val images: List<MessageImage> = emptyList(),
    /** 原始附件元数据（编辑弹层要按 id/kind 增删；`images` 只有渲染用的路径）。 */
    val attachments: List<Attachment> = emptyList(),
    /** `role=TOOL` 行的结构化结果；其余行为 null。 */
    val toolResult: ToolResult? = null,
)

data class ChatUiState(
    val conversationId: String? = null,
    val title: String = "新对话",
    val model: String = "",
    /** 会话绑定的供应商 id（决定模型列表与能力提示）。 */
    val providerId: String = "",
    /** 会话绑定的角色 id（空 = 跟随激活角色）；名称用于顶栏/弹层展示。 */
    val personaId: String = "",
    val personaName: String = "",
    val messages: List<ChatMessageItem> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingMessageId: String? = null,
    /** 本会话整回合是否在跑（含工具执行阶段）：发送键禁用、显示停止键。 */
    val isTurnActive: Boolean = false,
    val input: String = "",
    val pending: List<PendingAttachment> = emptyList(),
    val defaultModel: String = "",
    val notice: String? = null,
    val webSearchEnabled: Boolean = false,
    val webSearchAvailable: Boolean = true,
    /** 会话绑定的供应商是否支持视频输入（决定附件面板里是否出现「选择视频」）。 */
    val videoInputAvailable: Boolean = false,
    /** 会话绑定的供应商是否支持音频输入（决定附件面板里是否出现「选择音频」）。 */
    val audioInputAvailable: Boolean = false,
    /** 视频上传/解析中的一行提示；null 表示没有进行中的视频处理。 */
    val videoUploadNotice: String? = null,
) {
    val canSend: Boolean
        get() = (input.isNotBlank() || pending.isNotEmpty()) && !isTurnActive
}
