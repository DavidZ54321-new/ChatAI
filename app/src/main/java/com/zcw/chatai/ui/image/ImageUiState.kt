package com.zcw.chatai.ui.image

import com.zcw.chatai.data.image.ImageInputs
import com.zcw.chatai.ui.chat.ChatMessageItem
import com.zcw.chatai.ui.chat.PendingAttachment

/** 生图界面的 UI 状态（消息复用对话的 [ChatMessageItem]）。 */
data class ImageUiState(
    val conversationId: String? = null,
    val title: String = "新图像",
    val model: String = "",
    val messages: List<ChatMessageItem> = emptyList(),
    val input: String = "",
    /** 用户本轮自己传的图（自动带入的「上一张」不在其中，也不显示）。 */
    val pending: List<PendingAttachment> = emptyList(),
    /** 会话里是否已有上一轮生成结果（会静默作为图 1 带入，并占掉一个图片名额）。 */
    val hasPreviousImage: Boolean = false,
    val isBusy: Boolean = false,
    /** 提示词改写流式进行中。 */
    val rewriting: Boolean = false,
    val notice: String? = null,
    /** 是否已配置通义千问的 API Key（未配置时给提示并拦下发请求）。 */
    val available: Boolean = true,
) {
    val canSend: Boolean
        get() = input.isNotBlank() && !isBusy && !rewriting

    /** 用户本轮最多还能传几张（有上一张时它占掉一个名额）。 */
    val userImageLimit: Int
        get() = ImageInputs.MAX_IMAGES - if (hasPreviousImage) 1 else 0
}
