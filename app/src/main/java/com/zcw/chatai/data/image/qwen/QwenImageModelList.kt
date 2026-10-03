package com.zcw.chatai.data.image.qwen

import com.zcw.chatai.data.image.ImageModelOption
import com.zcw.chatai.data.net.EndpointUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 百炼 `GET /api/v1/models` 的一页。 */
sealed interface QwenModelListOutcome {
    data class Page(val total: Int, val models: List<ImageModelOption>) : QwenModelListOutcome

    data class Failure(val message: String?) : QwenModelListOutcome

    data object Malformed : QwenModelListOutcome
}

/**
 * 百炼模型列表：地址推导与 JSON 解析（纯函数）。
 *
 * 查询固定 `capabilities=IG`（图片生成）和 `providers=qwen`。
 * 北京文档地址要业务空间域名；这里一律用 Chat Base 的 origin，
 * 经典 `dashscope.aliyuncs.com` 失败时由调用方退回内置名单。
 */
object QwenImageModelList {

    const val PAGE_SIZE = 100
    const val MAX_PAGES = 10

    private val json = Json { ignoreUnknownKeys = true }

    fun url(baseUrl: String, pageNo: Int, pageSize: Int = PAGE_SIZE): String? {
        if (pageNo < 1 || pageSize < 1) return null
        val origin = EndpointUrl.originOf(baseUrl) ?: return null
        return "$origin/api/v1/models?capabilities=IG&providers=qwen&page_no=$pageNo&page_size=$pageSize"
    }

    fun parse(raw: String): QwenModelListOutcome {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return QwenModelListOutcome.Malformed
        val code = root.stringOf("code")
        if (code != null) return QwenModelListOutcome.Failure(root.stringOf("message"))
        val success = runCatching { root["success"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
        if (success == false) return QwenModelListOutcome.Failure(root.stringOf("message"))

        val output = runCatching { root["output"]?.jsonObject }.getOrNull()
            ?: return QwenModelListOutcome.Malformed
        val models = runCatching { output.models() }.getOrNull()
            ?: return QwenModelListOutcome.Malformed
        val declared = output.intOf("total")
        val total = if (declared > 0) declared else models.size
        return QwenModelListOutcome.Page(total, models)
    }

    private fun JsonObject.models(): List<ImageModelOption> =
        this["models"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = item.stringOf("model") ?: return@mapNotNull null
            val capabilities = runCatching { item["capabilities"]?.jsonArray }.getOrNull()
            if (capabilities != null && capabilities.none { cap ->
                    cap.jsonPrimitive.contentOrNull.equals("IG", ignoreCase = true)
                }
            ) {
                return@mapNotNull null
            }
            val label = item.stringOf("name") ?: id
            ImageModelOption(
                id = id,
                label = label,
                acceptsImageInput = acceptsImage(item),
            )
        }

    /** 没有 `request_modality` 时不把参考图关掉。有该字段则必须含 Image。 */
    private fun acceptsImage(item: JsonObject): Boolean {
        val modalities = runCatching {
            item["inference_metadata"]?.jsonObject?.get("request_modality")?.jsonArray
        }.getOrNull() ?: return true
        return modalities.any { element ->
            element.jsonPrimitive.contentOrNull.equals("Image", ignoreCase = true)
        }
    }

    private fun JsonObject.stringOf(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject.intOf(key: String): Int =
        runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0
}
