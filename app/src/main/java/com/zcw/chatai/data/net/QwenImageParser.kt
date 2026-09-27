package com.zcw.chatai.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 一次生图/改图调用的解析结果（纯数据）。 */
sealed interface QwenImageOutcome {
    /** 成功：生成的图片 URL 列表（24 小时有效，须立刻下载）+ 尺寸/张数。 */
    data class Success(
        val urls: List<String>,
        val width: Int,
        val height: Int,
        val imageCount: Int,
    ) : QwenImageOutcome

    /** 业务失败：DashScope 顶层 `code` / `message`。 */
    data class Failure(val code: String?, val message: String?) : QwenImageOutcome

    /** 响应不是可识别的 JSON 结构（网关错误页等）。 */
    data object Malformed : QwenImageOutcome
}

/**
 * 解析 DashScope `multimodal-generation/generation` 的响应（纯函数，JVM 单测覆盖）。
 *
 * - 成功：`output.choices[0].message.content[].image`（每项一个 URL）。
 * - 失败：**顶层** `code` / `message`（不是 OpenAI 的 `error.message`）。
 * - `usage` 的尺寸/张数**两种键名都认**：编辑接口是 `width/height/image_count`，
 *   文生图同步接口是 `output_width/output_height/output_image_count`；取不到置 0 不报错。
 */
object QwenImageParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): QwenImageOutcome {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return QwenImageOutcome.Malformed
        val code = root.stringOf("code")
        val message = root.stringOf("message")
        if (code != null) return QwenImageOutcome.Failure(code, message)

        val urls = runCatching { root.imageUrls() }.getOrNull().orEmpty()
        if (urls.isEmpty()) {
            return QwenImageOutcome.Failure(null, message ?: "服务端未返回图片")
        }
        val usage = runCatching { root["usage"]?.jsonObject }.getOrNull()
        val width = usage.intOf("width", "output_width")
        val height = usage.intOf("height", "output_height")
        val count = usage.intOf("image_count", "output_image_count").takeIf { it > 0 } ?: urls.size
        return QwenImageOutcome.Success(urls, width, height, count)
    }

    private fun JsonObject.imageUrls(): List<String> =
        this["output"]?.jsonObject
            ?.get("choices")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonArray
            .orEmpty()
            .mapNotNull { element ->
                runCatching { element.jsonObject["image"]?.jsonPrimitive?.contentOrNull }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }

    private fun JsonObject.stringOf(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject?.intOf(vararg keys: String): Int {
        if (this == null) return 0
        for (key in keys) {
            val value = runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()
            if (value != null) return value
        }
        return 0
    }
}
