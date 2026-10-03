package com.zcw.chatai.data.image

import com.zcw.chatai.data.image.openai.OpenAiImageOptions
import com.zcw.chatai.data.image.qwen.QwenImageOptions

/**
 * 一次生图/改图请求（厂商中立）。
 *
 * [images] 为空是文生图，非空是图像编辑。顺序由调用方排好（上一张结果在前）。
 * [promptExtend] 只有千问会写成 `prompt_extend`；其他后端忽略。null 表示不下发。
 * [openAi] 只有 OpenAI 后端会写进 Images API。
 * [qwen] 只有千问后端会写进 DashScope。
 */
data class ImageGenerateRequest(
    val model: String,
    val prompt: String,
    val images: List<String> = emptyList(),
    val promptExtend: Boolean? = null,
    val openAi: OpenAiImageOptions = OpenAiImageOptions(),
    val qwen: QwenImageOptions = QwenImageOptions(),
)
