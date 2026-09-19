package com.zcw.chatai.data.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * `providers_json` 的编解码与旧单配置懒迁移（纯函数，JVM 可测）。
 *
 * 容错策略：整串非法 → 空表（由调用方回退到旧 key 或默认值），绝不让设置项整体不可读。
 */
object ProviderConfigCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serializer = MapSerializer(String.serializer(), ProviderEntryDto.serializer())

    fun encode(providers: Map<String, ProviderEntry>): String =
        json.encodeToString(serializer, providers.mapValues { it.value.toDto() })

    fun decode(raw: String?): Map<String, ProviderEntry> {
        if (raw.isNullOrBlank()) return emptyMap()
        val decoded = try {
            json.decodeFromString(serializer, raw)
        } catch (t: Exception) {
            return emptyMap()
        }
        return decoded.mapValues { it.value.toModel() }
    }

    /**
     * 旧的一套 baseUrl/apiKey/model → 供应商表；按基址归位，命中不了进 custom。
     * 基址为空（**全新安装**没有旧 key）→ 落回 DeepSeek 默认，不能给一个空的 custom 条目。
     */
    fun fromLegacy(baseUrl: String, apiKey: String, model: String): Map<String, ProviderEntry> {
        val trimmedUrl = baseUrl.trim()
        val id = if (trimmedUrl.isEmpty()) {
            ProviderCatalog.DEEPSEEK
        } else {
            ProviderCatalog.matchByBaseUrl(trimmedUrl)
        }
        val preset = ProviderCatalog.byId(id)
        return mapOf(
            id to ProviderEntry(
                baseUrl = trimmedUrl.ifEmpty { preset?.defaultBaseUrl.orEmpty() },
                apiKey = apiKey,
                model = model.trim().ifEmpty { preset?.defaultModel.orEmpty() },
            ),
        )
    }

    private fun ProviderEntry.toDto() = ProviderEntryDto(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        anthropicBaseUrl = anthropicBaseUrl,
        responsesBaseUrl = responsesBaseUrl,
    )

    private fun ProviderEntryDto.toModel() = ProviderEntry(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        anthropicBaseUrl = anthropicBaseUrl,
        responsesBaseUrl = responsesBaseUrl,
    )
}

@Serializable
private data class ProviderEntryDto(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val anthropicBaseUrl: String = "",
    val responsesBaseUrl: String = "",
)
