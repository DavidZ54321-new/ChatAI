package com.zcw.chatai.data.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一次千问图像生成/编辑请求的输入（纯数据，JVM 可测）。
 *
 * - 无 [images] = 文生图；有 [images]（≤3 张）= 图像编辑，二者共用同一端点与请求形状。
 * - [images] 的数组顺序即提示词里的「图1/图2」；`size` 有输入图时通常省略（保持原图比例）。
 * - 为 null 的可选参数一律**不下发**（不要发空字符串/默认占位）。
 */
data class QwenImageRequest(
    val model: String,
    val prompt: String,
    val images: List<String> = emptyList(),
    val n: Int? = null,
    val size: String? = null,
    val negativePrompt: String? = null,
    val promptExtend: Boolean? = null,
    val watermark: Boolean? = null,
    val seed: Int? = null,
)

/** 组装 DashScope `multimodal-generation/generation` 的请求体（纯函数，与网络层解耦便于单测）。 */
object QwenImagePayload {

    fun build(request: QwenImageRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put(
            "input",
            buildJsonObject {
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    buildJsonArray {
                                        // 图 1..k 在前、text 在后：数组序号就是提示词里的图号。
                                        request.images.forEach { url ->
                                            add(buildJsonObject { put("image", url) })
                                        }
                                        add(buildJsonObject { put("text", request.prompt) })
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        val parameters = buildJsonObject {
            request.n?.let { put("n", it) }
            request.size?.takeIf { it.isNotBlank() }?.let { put("size", it) }
            request.negativePrompt?.takeIf { it.isNotBlank() }?.let { put("negative_prompt", it) }
            request.promptExtend?.let { put("prompt_extend", it) }
            request.watermark?.let { put("watermark", it) }
            request.seed?.let { put("seed", it) }
        }
        if (parameters.isNotEmpty()) put("parameters", parameters)
    }
}
