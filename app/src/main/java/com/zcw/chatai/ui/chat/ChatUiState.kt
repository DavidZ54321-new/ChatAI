package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolResult

/** 消息里一张图：缩略图给气泡，原图给全屏预览。 */
data class MessageImage(
    val id: String,
    val thumbnailPath: String,
    val fullPath: String,
    val width: Int,
    val height: Int,
)

/** 待发送附件（已落私有目录，发送成功前可撤回并删文件）。 */
data class PendingAttachment(
    val id: String,
    val thumbnailPath: String,
    val attachment: Attachment,
)

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
    /** `role=TOOL` 行的结构化结果；其余行为 null。 */
    val toolResult: ToolResult? = null,
)

data class ChatUiState(
    val conversationId: String? = null,
    val title: String = "新对话",
    val model: String = "",
    val messages: List<ChatMessageItem> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingMessageId: String? = null,
    val input: String = "",
    val pending: List<PendingAttachment> = emptyList(),
    val defaultModel: String = "",
    val notice: String? = null,
    val webSearchEnabled: Boolean = false,
    val webSearchAvailable: Boolean = true,
) {
    val canSend: Boolean
        get() = (input.isNotBlank() || pending.isNotEmpty()) && !isStreaming
}
