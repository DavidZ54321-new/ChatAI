package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolResult

/** 消息里一张图/一个视频：缩略图给气泡，原文件给全屏预览/播放器。 */
data class MessageImage(
    val id: String,
    val thumbnailPath: String,
    val fullPath: String,
    val width: Int,
    val height: Int,
    val isVideo: Boolean = false,
    val durationMs: Long? = null,
    /** 文档类型戳（如 PDF）：非空时缩略图显示字母且不可点。 */
    val label: String? = null,
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
    /** 视频上传/解析中的一行提示；null 表示没有进行中的视频处理。 */
    val videoUploadNotice: String? = null,
) {
    val canSend: Boolean
        get() = (input.isNotBlank() || pending.isNotEmpty()) && !isTurnActive
}
