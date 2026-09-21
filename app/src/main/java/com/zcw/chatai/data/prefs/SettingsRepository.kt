package com.zcw.chatai.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.EndpointUrl
import com.zcw.chatai.data.persona.PersonaConfigCodec
import com.zcw.chatai.data.persona.PersonaEntry
import com.zcw.chatai.data.provider.AnthropicBaseLayout
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.provider.ProviderConfigCodec
import com.zcw.chatai.data.provider.ProviderEntry
import com.zcw.chatai.data.provider.ToolModels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 品牌配色族：与 [ThemeMode]（明暗）正交；实际配色表在 `ThemeRegistry`。 */
enum class ThemeFamily { CLAUDE, CHATGPT }

/** 思考强度：标准 `reasoning_effort` 字段；FOLLOW_DEFAULT 表示不发送该字段。 */
enum class ReasoningEffort(val wire: String?) {
    FOLLOW_DEFAULT(null),
    OFF("none"),
    LOW("low"),
    HIGH("high"),
    MAX("max"),
}

/** 图片精度：标准 `image_url.detail`；FOLLOW_DEFAULT 表示不发送。 */
enum class ImageDetail(val wire: String?) {
    FOLLOW_DEFAULT(null),
    LOW("low"),
    HIGH("high"),
}

/**
 * 连接配置按供应商分表（[providers] + [activeProviderId]），提示词与生成参数按角色分表
 * （[personas] + [activePersonaId]，见 `PersonaConfigCodec`）。
 * 旧版本的单套 baseUrl/apiKey/model 会在首次读取时懒迁移进 [providers]，
 * 旧的全局提示词/生成参数会懒迁移成一张默认角色表。
 */
data class ChatSettings(
    val providers: Map<String, ProviderEntry>,
    val activeProviderId: String,
    /** 联网搜索后端（null = 跟随会话，见 `ToolBackendResolver`）。 */
    val searchProviderId: String?,
    /** 角色表（id → 配置）；至少一张，空表时按默认空角色降级（不抛）。 */
    val personas: Map<String, PersonaEntry>,
    /**
     * 激活角色 id：新会话的默认角色；会话内切换角色时会同步更新它，
     * 所以「记住上次选择」不需要额外状态。
     */
    val activePersonaId: String,
    val imageDetail: ImageDetail,
    val includeUsage: Boolean,
    /** 上下文末尾是否追加当前时间尾条（默认开；取值失败时不追加，主流程不受影响）。 */
    val includeEnvTime: Boolean,
    /** 历史图片重发轮次：-1 全部、0 只发当前轮、N = 当前轮 + 最近 N 轮。 */
    val historyImageTurns: Int,
    /** 图搜模型链（原始输入，逗号分隔）；空 = 用 Qwen 预设的默认链。 */
    val imageSearchModelsRaw: String,
    val themeMode: ThemeMode,
    /** 品牌配色族（与 [themeMode] 正交：Claude / ChatGPT …）。 */
    val themeFamily: ThemeFamily,
) {
    /** 生效的图搜模型链：用户覆盖优先，空则用 Qwen 预设默认（27b → max）。 */
    val imageSearchModels: List<String>
        get() = ToolModels.resolve(
            imageSearchModelsRaw,
            ProviderCatalog.byId(ProviderCatalog.QWEN)?.toolModels.orEmpty(),
        )

    /** 激活供应商；表意外为空时给一个安全空条目（请求层会给出可读报错）。 */
    val activeProvider: ProviderEntry
        get() = providers[activeProviderId]
            ?: providers.values.firstOrNull()
            ?: ProviderEntry(baseUrl = "", apiKey = "", model = "")

    /**
     * 激活角色 id（已做存在性归一）：不在表里时顺延到首个，表空时回落默认 id。
     * 会话绑定的空串同样经由这里跟随激活（见 `PersonaConfigCodec.resolveEffective`）。
     */
    val resolvedActivePersonaId: String
        get() = PersonaConfigCodec.resolveActiveId(personas, activePersonaId)

    /** 激活角色；表意外为空时给一个安全的空角色（不断请求链）。 */
    val activePersona: PersonaEntry
        get() = PersonaConfigCodec.resolveEffective(personas, resolvedActivePersonaId, null)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-flash"
        const val DEFAULT_HISTORY_IMAGE_TURNS = 1

        val Default = ChatSettings(
            providers = mapOf(
                ProviderCatalog.DEEPSEEK to ProviderEntry(
                    baseUrl = DEFAULT_BASE_URL,
                    apiKey = "",
                    model = DEFAULT_MODEL,
                ),
            ),
            activeProviderId = ProviderCatalog.DEEPSEEK,
            searchProviderId = null,
            personas = mapOf(
                PersonaConfigCodec.DEFAULT_ID to PersonaEntry(
                    name = PersonaConfigCodec.DEFAULT_NAME,
                ),
            ),
            activePersonaId = PersonaConfigCodec.DEFAULT_ID,
            imageDetail = ImageDetail.FOLLOW_DEFAULT,
            includeUsage = true,
            includeEnvTime = true,
            historyImageTurns = DEFAULT_HISTORY_IMAGE_TURNS,
            imageSearchModelsRaw = "",
            themeMode = ThemeMode.SYSTEM,
            themeFamily = ThemeFamily.CLAUDE,
        )
    }
}

/**
 * 按供应商 + 角色解析出一次请求的配置；[providerId] 不在表里时回退到激活供应商
 * （会话绑定了已删除的供应商时，由调用方先做存在性检查并给出可读错误）。
 * [personaId] 是会话绑定的角色 id，空/已删除时跟随激活角色
 * （见 `PersonaConfigCodec.resolveEffective`）。
 */
fun ChatSettings.toChatConfig(
    providerId: String = activeProviderId,
    personaId: String? = null,
): ChatConfig {
    val entry = providers[providerId] ?: activeProvider
    val persona = PersonaConfigCodec.resolveEffective(personas, resolvedActivePersonaId, personaId)
    return ChatConfig(
        baseUrl = entry.baseUrl,
        apiKey = entry.apiKey,
        model = entry.model,
        systemPrompt = persona.systemPrompt,
        temperature = persona.temperature,
        reasoningEffort = persona.reasoningEffort.wire,
        maxTokens = persona.maxTokens,
        imageDetail = imageDetail.wire,
        includeUsage = includeUsage,
        includeEnvTime = includeEnvTime,
        historyImageTurns = historyImageTurns,
        extraParams = persona.extraParams.ifBlank { null },
        providerId = providerId,
        sendSessionHeader = ProviderCatalog.byId(providerId)?.sendSessionHeader == true,
        webSearchEnabled = false,
        anthropicBaseUrl = EndpointUrl.anthropicBase(
            entry.baseUrl,
            ProviderCatalog.byId(providerId)?.anthropicBaseLayout
                ?: AnthropicBaseLayout.SAME_V1,
            entry.anthropicBaseUrl,
        ).orEmpty(),
        responsesBaseUrl = EndpointUrl.responsesBase(entry.baseUrl, entry.responsesBaseUrl).orEmpty(),
    )
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<ChatSettings> = store.data.map { it.toChatSettings() }

    fun chatConfig(providerId: String? = null, personaId: String? = null): Flow<ChatConfig> =
        settings.map { current ->
            current.toChatConfig(providerId ?: current.activeProviderId, personaId)
        }

    /**
     * 保存整张供应商配置表 + 整张角色表 + 其余全局参数；[activeProviderId] /
     * [activePersonaId] 同时被设为激活。
     * 整表落盘：设置页里改过但当前没在编辑的条目也一并保住（不再只存选中的那一条）。
     * 一次 edit 落盘，避免半保存状态。
     */
    suspend fun updateConfig(
        providers: Map<String, ProviderEntry>,
        activeProviderId: String,
        searchProviderId: String?,
        personas: Map<String, PersonaEntry>,
        activePersonaId: String,
        imageDetail: ImageDetail,
        includeUsage: Boolean,
        includeEnvTime: Boolean,
        historyImageTurns: Int,
        imageSearchModelsRaw: String,
    ) {
        store.edit { prefs ->
            prefs[KEY_PROVIDERS] = ProviderConfigCodec.encode(providers)
            prefs[KEY_ACTIVE_PROVIDER] = activeProviderId
            if (searchProviderId.isNullOrBlank()) prefs.remove(KEY_SEARCH_PROVIDER) else prefs[KEY_SEARCH_PROVIDER] = searchProviderId
            prefs[KEY_PERSONAS] = PersonaConfigCodec.encode(personas)
            prefs[KEY_ACTIVE_PERSONA] = activePersonaId
            prefs[KEY_IMAGE_DETAIL] = imageDetail.name
            prefs[KEY_INCLUDE_USAGE] = includeUsage
            prefs[KEY_INCLUDE_ENV_TIME] = includeEnvTime
            prefs[KEY_HISTORY_IMAGE_TURNS] = historyImageTurns
            if (imageSearchModelsRaw.isBlank()) prefs.remove(KEY_IMAGE_SEARCH_MODELS) else prefs[KEY_IMAGE_SEARCH_MODELS] = imageSearchModelsRaw
        }
    }

    /** 会话内切换角色：只改激活角色（新会话记住上次选择），角色表不动。 */
    suspend fun updateActivePersona(personaId: String) {
        store.edit { prefs ->
            prefs[KEY_ACTIVE_PERSONA] = personaId
        }
    }

    /**
     * 保存整张角色表 + 激活角色（角色管理页用）。
     * 删到空表时不合法：调用方必须至少保留一张（UI 侧拦截）。
     */
    suspend fun updatePersonas(personas: Map<String, PersonaEntry>, activePersonaId: String) {
        store.edit { prefs ->
            prefs[KEY_PERSONAS] = PersonaConfigCodec.encode(personas)
            prefs[KEY_ACTIVE_PERSONA] = activePersonaId
        }
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        store.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    suspend fun updateThemeFamily(family: ThemeFamily) {
        store.edit { prefs ->
            prefs[KEY_THEME_FAMILY] = family.name
        }
    }

    /**
     * 懒迁移（不主动回写）：
     * - `providers_json` 缺失/非法时，用旧平铺 key 合成一张初始表；
     * - `personas_json` 缺失/非法时，用旧全局提示词 + 生成参数合成一张默认角色表。
     */
    private fun Preferences.toChatSettings(): ChatSettings {
        val providers = ProviderConfigCodec.decode(this[KEY_PROVIDERS]).ifEmpty {
            ProviderConfigCodec.fromLegacy(
                baseUrl = this[KEY_BASE_URL].orEmpty(),
                apiKey = this[KEY_API_KEY].orEmpty(),
                model = this[KEY_MODEL].orEmpty(),
            )
        }
        val active = this[KEY_ACTIVE_PROVIDER]
            ?.takeIf { id -> id in providers }
            ?: providers.keys.firstOrNull()
            ?: ProviderCatalog.DEEPSEEK
        val personas = PersonaConfigCodec.decode(this[KEY_PERSONAS]).ifEmpty {
            PersonaConfigCodec.fromLegacy(
                systemPrompt = this[KEY_SYSTEM_PROMPT].orEmpty(),
                temperature = this[KEY_TEMPERATURE],
                reasoningEffort = this[KEY_REASONING_EFFORT].toEnum(ReasoningEffort.FOLLOW_DEFAULT),
                maxTokens = this[KEY_MAX_TOKENS],
                extraParams = this[KEY_EXTRA_PARAMS].orEmpty(),
            )
        }
        val activePersona = PersonaConfigCodec.resolveActiveId(personas, this[KEY_ACTIVE_PERSONA])
        return ChatSettings(
            providers = providers,
            activeProviderId = active,
            searchProviderId = this[KEY_SEARCH_PROVIDER]?.takeIf { it.isNotBlank() },
            personas = personas,
            activePersonaId = activePersona,
            imageDetail = this[KEY_IMAGE_DETAIL].toEnum(ImageDetail.FOLLOW_DEFAULT),
            includeUsage = this[KEY_INCLUDE_USAGE] ?: true,
            includeEnvTime = this[KEY_INCLUDE_ENV_TIME] ?: true,
            // 轮次单位是 v6 引入的；旧 key（消息条数）按约定统一归一到默认 1 轮。
            historyImageTurns = this[KEY_HISTORY_IMAGE_TURNS] ?: ChatSettings.DEFAULT_HISTORY_IMAGE_TURNS,
            imageSearchModelsRaw = this[KEY_IMAGE_SEARCH_MODELS].orEmpty(),
            themeMode = this[KEY_THEME_MODE].toEnum(ThemeMode.SYSTEM),
            themeFamily = this[KEY_THEME_FAMILY].toEnum(ThemeFamily.CLAUDE),
        )
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: fallback

    private companion object {
        val KEY_PROVIDERS = stringPreferencesKey("providers_json")
        val KEY_ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
        val KEY_SEARCH_PROVIDER = stringPreferencesKey("search_provider")
        val KEY_PERSONAS = stringPreferencesKey("personas_json")
        val KEY_ACTIVE_PERSONA = stringPreferencesKey("active_persona")
        /** 旧全局提示词 key：只在懒迁移成默认角色时读，不再写入。 */
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val KEY_TEMPERATURE = doublePreferencesKey("temperature")
        val KEY_REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val KEY_MAX_TOKENS = intPreferencesKey("max_tokens")
        val KEY_IMAGE_DETAIL = stringPreferencesKey("image_detail")
        val KEY_INCLUDE_USAGE = booleanPreferencesKey("include_usage")
        /** 上下文末尾追加当前时间（默认开；老版本无此 key 时回落 true）。 */
        val KEY_INCLUDE_ENV_TIME = booleanPreferencesKey("include_env_time")
        /** 轮次单位（v6）：历史图片重发轮次。旧 key `history_image_limit` 已废弃。 */
        val KEY_HISTORY_IMAGE_TURNS = intPreferencesKey("history_image_turns")
        /** 图搜模型链覆盖（空 = 用 Qwen 预设默认）。 */
        val KEY_IMAGE_SEARCH_MODELS = stringPreferencesKey("image_search_models")
        val KEY_EXTRA_PARAMS = stringPreferencesKey("extra_params")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_THEME_FAMILY = stringPreferencesKey("theme_family")

        /** 旧版本单配置 key：只在懒迁移时读，不再写入。 */
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_MODEL = stringPreferencesKey("model")
    }
}
