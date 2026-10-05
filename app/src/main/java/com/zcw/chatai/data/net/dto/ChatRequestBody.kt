package com.zcw.chatai.data.net.dto

import com.zcw.chatai.data.net.ChatRequestAudio
import com.zcw.chatai.data.net.ChatRequestImage
import com.zcw.chatai.data.net.ChatRequestVideo
import com.zcw.chatai.data.net.InlineMedia
import com.zcw.chatai.data.net.InlinePart
import com.zcw.chatai.data.provider.ThinkingWire
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
data class EncodedContent(
    val element: JsonElement,
    val inlineParts: List<InlinePart> = emptyList(),
)

object ChatRequestBody {

    private val ProtectedKeys = setOf("model", "messages", "stream", "tools", "tool_choice")

    private val extraJson = Json { isLenient = true }

    fun encode(
        json: Json,
        payload: ChatCompletionRequest,
        extraParams: String?,
        thinkingWire: ThinkingWire = ThinkingWire.STANDARD_REASONING_EFFORT,
        reasoningEffort: String? = null,
    ): String {
        val dto = json.encodeToJsonElement(ChatCompletionRequest.serializer(), payload).jsonObject
        val base = applyThinkingWire(dto, thinkingWire, reasoningEffort)
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

    /**
     * 按供应商预设的思考风格改写思考字段（纯函数，JVM 可测）。
     *
     * - [ThinkingWire.STANDARD_REASONING_EFFORT]：原样返回（DTO 已按需带了 `reasoning_effort`）。
     * - [ThinkingWire.MIMO_THINKING_OBJECT]：剥掉 `reasoning_effort`，把 persona 档位映射到
     *   MiMo 的非标准 `thinking:{type}` 对象——`none`→`disabled`、`low/high/max`→`enabled`、
     *   `null`（FOLLOW_DEFAULT，DTO 本就不带该字段）→ 省略，尊重服务端默认开。
     */
    fun applyThinkingWire(
        base: JsonObject,
        wire: ThinkingWire,
        reasoningEffort: String?,
    ): JsonObject {
        if (wire == ThinkingWire.STANDARD_REASONING_EFFORT) return base
        val map = base.toMutableMap()
        map.remove("reasoning_effort")
        val type = when (reasoningEffort) {
            null -> null
            "none" -> "disabled"
            else -> "enabled"
        }
        if (type != null) {
            map["thinking"] = buildJsonObject { put("type", type) }
        }
        return JsonObject(map)
    }

    /**
     * 组一条消息的 `content`。内联视频/音频的占位符和 [EncodedContent.inlineParts]
     * 在同一次遍历里产生，顺序就是 JSON 里的出现顺序。
     */
    fun content(
        text: String,
        images: List<ChatRequestImage>,
        videos: List<ChatRequestVideo> = emptyList(),
        audios: List<ChatRequestAudio> = emptyList(),
    ): EncodedContent {
        if (images.isEmpty() && videos.isEmpty() && audios.isEmpty()) {
            return EncodedContent(JsonPrimitive(text))
        }
        val inlineParts = mutableListOf<InlinePart>()
        val element = buildJsonArray {
            if (text.isNotEmpty()) {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            }
            images.forEach { image ->
                // 每张图前插一段来源标注（"第几张 / 哪一轮"），避免历史图与本轮图混淆。
                image.label?.takeIf { it.isNotBlank() }?.let { label ->
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", label)
                        },
                    )
                }
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
                val url = when (video) {
                    is ChatRequestVideo.Remote -> video.url
                    is ChatRequestVideo.Inline -> InlineMedia.placeholder(video.id).also { token ->
                        inlineParts += InlinePart(token, video.file, video.mime)
                    }
                }
                add(
                    buildJsonObject {
                        put("type", "video_url")
                        putJsonObject("video_url") {
                            put("url", url)
                        }
                    },
                )
            }
            audios.forEach { audio ->
                // MiMo 形状：input_audio 只有一个 data 字段（URL 或 data URI），不是 OpenAI 的 {data,format}。
                val data = when (audio) {
                    is ChatRequestAudio.Remote -> audio.dataUrl
                    is ChatRequestAudio.Inline -> InlineMedia.placeholder(audio.id).also { token ->
                        inlineParts += InlinePart(token, audio.file, audio.mime)
                    }
                }
                add(
                    buildJsonObject {
                        put("type", "input_audio")
                        putJsonObject("input_audio") {
                            put("data", data)
                        }
                    },
                )
            }
        }
        return EncodedContent(element, inlineParts)
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
