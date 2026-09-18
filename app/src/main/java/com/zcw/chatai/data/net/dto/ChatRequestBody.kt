package com.zcw.chatai.data.net.dto

import com.zcw.chatai.data.net.ChatRequestImage
import com.zcw.chatai.data.net.ChatRequestVideo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 请求体组装（纯函数，JVM 可测）。
 *
 * 两条厂商中立规则：
 * 1. 文本消息的 `content` 编码成 **JSON 字符串**；只有带图片的消息才编码成内容块数组。
 *    图片块用标准 OpenAI 形状 `{"type":"image_url","image_url":{"url","detail"}}`，
 *    图片必须内联为 `data:` URL（外部 URL 会被防盗链拒绝）。
 * 2. [extraParams] 是兼容逃生口：用户 JSON 的顶层键合并进请求体，但
 *    `model` / `messages` / `stream` 以及应用自有的工具协议
 *    (`tools` / `tool_choice`) 受保护，永远以应用生成的值为准。
 */
object ChatRequestBody {

    private val ProtectedKeys = setOf("model", "messages", "stream", "tools", "tool_choice")

    private val extraJson = Json { isLenient = true }

    fun encode(json: Json, payload: ChatCompletionRequest, extraParams: String?): String {
        val base = json.encodeToJsonElement(ChatCompletionRequest.serializer(), payload).jsonObject
        val extra = parseExtra(extraParams) ?: return json.encodeToString(JsonObject.serializer(), base)
        val merged = JsonObject(
            base.toMutableMap().apply {
                extra.forEach { (key, value) ->
                    if (key !in ProtectedKeys) put(key, value)
                }
            },
        )
        return json.encodeToString(JsonObject.serializer(), merged)
    }

    fun content(
        text: String,
        images: List<ChatRequestImage>,
        videos: List<ChatRequestVideo> = emptyList(),
    ): JsonElement {
        if (images.isEmpty() && videos.isEmpty()) return JsonPrimitive(text)
        return buildJsonArray {
            if (text.isNotEmpty()) {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            }
            images.forEach { image ->
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", image.dataUrl)
                            image.detail?.takeIf { it.isNotBlank() }?.let { put("detail", it) }
                        }
                    },
                )
            }
            videos.forEach { video ->
                add(
                    buildJsonObject {
                        put("type", "video_url")
                        putJsonObject("video_url") {
                            put("url", video.url)
                        }
                    },
                )
            }
        }
    }

    private fun parseExtra(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return try {
            extraJson.parseToJsonElement(raw) as? JsonObject
        } catch (t: Exception) {
            null
        }
    }
}
