package com.zcw.chatai.ui.image

import com.zcw.chatai.data.image.ImageInputs
import com.zcw.chatai.data.image.ImageModelSection
import com.zcw.chatai.data.image.openai.OpenAiImageOptions
import com.zcw.chatai.data.image.qwen.QwenImageOptions
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.ui.chat.ChatMessageItem
import com.zcw.chatai.ui.chat.PendingAttachment

/** 生图界面的 UI 状态（消息复用对话的 [ChatMessageItem]）。 */
data class ImageUiState(
    val conversationId: String? = null,
    val title: String = "新图像",
    val model: String = "",
    val providerId: String = ProviderCatalog.QWEN,
    val providerLabel: String = "通义千问",
    val modelSections: List<ImageModelSection> = emptyList(),
    val maxInputImages: Int = ImageInputs.MAX_IMAGES,
    val acceptsImageInput: Boolean = true,
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
    /** 当前图像供应商是否已配置 API Key。 */
    val available: Boolean = true,
    /** OpenAI 生图参数。千问会话不使用。 */
    val openAiImage: OpenAiImageOptions = OpenAiImageOptions(),
    /** 千问生图参数。OpenAI 会话不使用。 */
    val qwenImage: QwenImageOptions = QwenImageOptions(),
    /** 千问 prompt_extend。和设置页是同一个开关。 */
    val imagePromptExtend: Boolean = true,
) {
    val canSend: Boolean
        get() = input.isNotBlank() && !isBusy && !rewriting

    /** 用户本轮最多还能传几张（有上一张时它占掉一个名额）。 */
    val userImageLimit: Int
        get() = ImageInputs.userSlotLimit(hasPreviousImage, maxInputImages, acceptsImageInput)
}
