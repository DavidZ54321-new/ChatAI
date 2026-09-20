package com.zcw.chatai.data.persona

import com.zcw.chatai.data.prefs.ReasoningEffort
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * `personas_json` 的编解码与旧全局提示词懒迁移（纯函数，JVM 可测）。
 *
 * 容错策略与 `ProviderConfigCodec` 一致：整串非法 → 空表（由调用方回退到
 * 旧 key 合成默认角色），绝不让设置项整体不可读。
 */
object PersonaConfigCodec {

    const val DEFAULT_ID = "default"
    const val DEFAULT_NAME = "默认"

    /** 角色名长度上限（与会话标题同一量级，列表里一行能放下）。 */
    const val MAX_NAME_LENGTH = 24

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serializer = MapSerializer(String.serializer(), PersonaEntryDto.serializer())

    fun encode(personas: Map<String, PersonaEntry>): String =
        json.encodeToString(serializer, personas.mapValues { it.value.toDto() })

    fun decode(raw: String?): Map<String, PersonaEntry> {
        if (raw.isNullOrBlank()) return emptyMap()
        val decoded = try {
            json.decodeFromString(serializer, raw)
        } catch (t: Exception) {
            return emptyMap()
        }
        return decoded.mapValues { it.value.toModel() }
    }

    /**
     * 旧的全局提示词 + 生成参数 → 单张默认角色表。
     * 全空（全新安装）同样给一张默认表：调用方永远有至少一个角色可用。
     */
    fun fromLegacy(
        systemPrompt: String,
        temperature: Double?,
        reasoningEffort: ReasoningEffort,
        maxTokens: Int?,
        extraParams: String,
    ): Map<String, PersonaEntry> = mapOf(
        DEFAULT_ID to PersonaEntry(
            name = DEFAULT_NAME,
            systemPrompt = systemPrompt,
            temperature = temperature,
            reasoningEffort = reasoningEffort,
            maxTokens = maxTokens,
            extraParams = extraParams,
        ),
    )

    /** 解析激活角色 id：不在表里时顺延到首个，表空时回落默认 id。 */
    fun resolveActiveId(personas: Map<String, PersonaEntry>, activeId: String?): String =
        when {
            activeId != null && activeId in personas -> activeId
            else -> personas.keys.firstOrNull() ?: DEFAULT_ID
        }

    /** 解析出生效角色：会话绑定优先，空串 = 跟随激活（与 `provider_id` 同语义）。 */
    fun resolveEffective(
        personas: Map<String, PersonaEntry>,
        activeId: String,
        boundId: String?,
    ): PersonaEntry {
        val bound = boundId?.takeIf { it.isNotBlank() }?.let { personas[it] }
        if (bound != null) return bound
        return personas[activeId] ?: personas.values.firstOrNull() ?: PersonaEntry(name = DEFAULT_NAME)
    }

    /** 角色名校验：非空、不超长；空串合法性由调用方（新增/重命名）决定是否接受。 */
    fun validateName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "请输入角色名称"
        if (trimmed.length > MAX_NAME_LENGTH) return "角色名称最多 $MAX_NAME_LENGTH 个字"
        return null
    }

    private fun PersonaEntry.toDto() = PersonaEntryDto(
        name = name,
        systemPrompt = systemPrompt,
        temperature = temperature,
        reasoningEffort = reasoningEffort.name,
        maxTokens = maxTokens,
        extraParams = extraParams,
    )

    private fun PersonaEntryDto.toModel() = PersonaEntry(
        name = name,
        systemPrompt = systemPrompt,
        temperature = temperature,
        reasoningEffort = enumValues<ReasoningEffort>().firstOrNull { it.name == reasoningEffort }
            ?: ReasoningEffort.FOLLOW_DEFAULT,
        maxTokens = maxTokens,
        extraParams = extraParams,
    )
}

@Serializable
private data class PersonaEntryDto(
    val name: String = "",
    val systemPrompt: String = "",
    val temperature: Double? = null,
    val reasoningEffort: String = "FOLLOW_DEFAULT",
    val maxTokens: Int? = null,
    val extraParams: String = "",
)
