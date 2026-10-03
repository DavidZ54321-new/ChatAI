package com.zcw.chatai.data.net

import com.zcw.chatai.data.image.openai.OpenAiImageOptions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenAI Images API 请求体（纯函数）。
 *
 * 无参考图走 `/images/generations`，有参考图走 `/images/edits`。
 * GPT Image 只回 `b64_json`，不下发已废弃的 `response_format`。
 * [OpenAiImageOptions] 里的空字段省略，让服务端用自己的默认。
 */
object OpenAiImagePayload {

    const val TEXT_SIZE = OpenAiImageOptions.GENERATION_SIZE
    const val EDIT_SIZE = OpenAiImageOptions.EDIT_SIZE

    fun generations(
        model: String,
        prompt: String,
        options: OpenAiImageOptions = OpenAiImageOptions(),
    ): JsonObject = body(model, prompt, images = emptyList(), editing = false, options = options.sanitized())

    fun edits(
        model: String,
        prompt: String,
        images: List<String>,
        options: OpenAiImageOptions = OpenAiImageOptions(),
    ): JsonObject = body(model, prompt, images, editing = true, options = options.sanitized())

    private fun body(
        model: String,
        prompt: String,
        images: List<String>,
        editing: Boolean,
        options: OpenAiImageOptions,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("prompt", prompt)
        put("n", options.count)
        put("size", options.size.ifBlank { if (editing) EDIT_SIZE else TEXT_SIZE })
        if (options.quality.isNotBlank()) put("quality", options.quality)
        if (options.background.isNotBlank()) put("background", options.background)
        if (options.outputFormat.isNotBlank()) put("output_format", options.outputFormat)
        val compression = options.outputCompression
        if (compression != null) put("output_compression", compression)
        if (images.isNotEmpty()) {
            put(
                "images",
                buildJsonArray {
                    images.forEach { url ->
                        add(buildJsonObject { put("image_url", url) })
                    }
                },
            )
        }
    }
}
