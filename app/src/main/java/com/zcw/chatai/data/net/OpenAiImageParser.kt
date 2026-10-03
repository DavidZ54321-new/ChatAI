package com.zcw.chatai.data.net

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** OpenAI Images 响应（`/images/generations` 与 `/images/edits` 同形）。 */
sealed interface OpenAiImageOutcome {
    data class Success(val images: List<ByteArray>) : OpenAiImageOutcome

    data class Failure(val message: String?) : OpenAiImageOutcome

    data object Malformed : OpenAiImageOutcome
}

/**
 * 解析 GPT Image 的 JSON（纯函数）。成功项只认 `data[].b64_json`。
 * `url` 对 GPT Image 不可用，忽略。
 */
object OpenAiImageParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): OpenAiImageOutcome {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return OpenAiImageOutcome.Malformed
        val errorMessage = runCatching { root["error"]?.jsonObject?.stringOf("message") }.getOrNull()
        if (!errorMessage.isNullOrBlank()) return OpenAiImageOutcome.Failure(errorMessage)

        val data = runCatching { root["data"]?.jsonArray }.getOrNull()
            ?: return OpenAiImageOutcome.Failure(root.stringOf("message") ?: "服务端未返回图片")
        val images = data.mapNotNull { element ->
            val b64 = runCatching { element.jsonObject.stringOf("b64_json") }.getOrNull() ?: return@mapNotNull null
            runCatching { Base64.getDecoder().decode(b64) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        if (images.isEmpty()) {
            return OpenAiImageOutcome.Failure(root.stringOf("message") ?: "服务端未返回图片")
        }
        return OpenAiImageOutcome.Success(images)
    }

    private fun JsonObject.stringOf(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
}
