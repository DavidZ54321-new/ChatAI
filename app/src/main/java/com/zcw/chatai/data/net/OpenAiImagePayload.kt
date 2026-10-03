package com.zcw.chatai.data.net

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
 * 质量、输出格式、背景省略，让服务端用 `auto`。
 */
object OpenAiImagePayload {

    const val TEXT_SIZE = "1024x1024"
    const val EDIT_SIZE = "auto"

    fun generations(model: String, prompt: String): JsonObject = buildJsonObject {
        put("model", model)
        put("prompt", prompt)
        put("n", 1)
        put("size", TEXT_SIZE)
    }

    fun edits(model: String, prompt: String, images: List<String>): JsonObject = buildJsonObject {
        put("model", model)
        put("prompt", prompt)
        put("n", 1)
        put("size", EDIT_SIZE)
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
