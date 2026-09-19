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
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.provider.ProviderConfigCodec
import com.zcw.chatai.data.provider.ProviderEntry
import com.zcw.chatai.data.provider.ToolModels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

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
 * 连接配置按供应商分表（[providers] + [activeProviderId]），生成参数保持全局。
 * 旧版本的单套 baseUrl/apiKey/model 会在首次读取时懒迁移进 [providers]。
 */
data class ChatSettings(
    val providers: Map<String, ProviderEntry>,
    val activeProviderId: String,
    /** 联网搜索后端（null = 跟随会话，见 `ToolBackendResolver`）。 */
    val searchProviderId: String?,
    val systemPrompt: String,
    val temperature: Double?,
    val reasoningEffort: ReasoningEffort,
    val maxTokens: Int?,
    val imageDetail: ImageDetail,
    val includeUsage: Boolean,
    /** 历史图片重发轮次：-1 全部、0 只发当前轮、N = 当前轮 + 最近 N 轮。 */
    val historyImageTurns: Int,
    /** 图搜模型链（原始输入，逗号分隔）；空 = 用 Qwen 预设的默认链。 */
    val imageSearchModelsRaw: String,
    val extraParams: String,
    val themeMode: ThemeMode,
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
            systemPrompt = "",
            temperature = null,
            reasoningEffort = ReasoningEffort.FOLLOW_DEFAULT,
            maxTokens = null,
            imageDetail = ImageDetail.FOLLOW_DEFAULT,
            includeUsage = true,
            historyImageTurns = DEFAULT_HISTORY_IMAGE_TURNS,
            imageSearchModelsRaw = "",
            extraParams = "",
            themeMode = ThemeMode.SYSTEM,
        )
    }
}

/**
 * 按供应商解析出一次请求的配置；[providerId] 不在表里时回退到激活供应商
 * （会话绑定了已删除的供应商时，由调用方先做存在性检查并给出可读错误）。
 */
fun ChatSettings.toChatConfig(providerId: String = activeProviderId): ChatConfig {
    val entry = providers[providerId] ?: activeProvider
    return ChatConfig(
        baseUrl = entry.baseUrl,
        apiKey = entry.apiKey,
        model = entry.model,
        systemPrompt = systemPrompt,
        temperature = temperature,
        reasoningEffort = reasoningEffort.wire,
        maxTokens = maxTokens,
        imageDetail = imageDetail.wire,
        includeUsage = includeUsage,
        historyImageTurns = historyImageTurns,
        extraParams = extraParams.ifBlank { null },
        providerId = providerId,
        sendSessionHeader = ProviderCatalog.byId(providerId)?.sendSessionHeader == true,
        webSearchEnabled = false,
    )
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<ChatSettings> = store.data.map { it.toChatSettings() }

    fun chatConfig(providerId: String? = null): Flow<ChatConfig> = settings.map { current ->
        current.toChatConfig(providerId ?: current.activeProviderId)
    }

    /**
     * 保存整张供应商配置表 + 全部生成参数；[activeProviderId] 同时被设为激活。
     * 整表落盘：设置页里改过但当前没在编辑的供应商条目也一并保住（不再只存选中的那一条）。
     * 一次 edit 落盘，避免半保存状态。
     */
    suspend fun updateConfig(
        providers: Map<String, ProviderEntry>,
        activeProviderId: String,
        searchProviderId: String?,
        systemPrompt: String,
        temperature: Double?,
        reasoningEffort: ReasoningEffort,
        maxTokens: Int?,
        imageDetail: ImageDetail,
        includeUsage: Boolean,
        historyImageTurns: Int,
        imageSearchModelsRaw: String,
        extraParams: String,
    ) {
        store.edit { prefs ->
            prefs[KEY_PROVIDERS] = ProviderConfigCodec.encode(providers)
            prefs[KEY_ACTIVE_PROVIDER] = activeProviderId
            if (searchProviderId.isNullOrBlank()) prefs.remove(KEY_SEARCH_PROVIDER) else prefs[KEY_SEARCH_PROVIDER] = searchProviderId
            prefs[KEY_SYSTEM_PROMPT] = systemPrompt
            if (temperature == null) prefs.remove(KEY_TEMPERATURE) else prefs[KEY_TEMPERATURE] = temperature
            prefs[KEY_REASONING_EFFORT] = reasoningEffort.name
            if (maxTokens == null) prefs.remove(KEY_MAX_TOKENS) else prefs[KEY_MAX_TOKENS] = maxTokens
            prefs[KEY_IMAGE_DETAIL] = imageDetail.name
            prefs[KEY_INCLUDE_USAGE] = includeUsage
            prefs[KEY_HISTORY_IMAGE_TURNS] = historyImageTurns
            if (imageSearchModelsRaw.isBlank()) prefs.remove(KEY_IMAGE_SEARCH_MODELS) else prefs[KEY_IMAGE_SEARCH_MODELS] = imageSearchModelsRaw
            prefs[KEY_EXTRA_PARAMS] = extraParams
        }
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        store.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    /** 懒迁移：`providers_json` 缺失/非法时，用旧平铺 key 合成一张初始表（不主动回写）。 */
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
        return ChatSettings(
            providers = providers,
            activeProviderId = active,
            searchProviderId = this[KEY_SEARCH_PROVIDER]?.takeIf { it.isNotBlank() },
            systemPrompt = this[KEY_SYSTEM_PROMPT].orEmpty(),
            temperature = this[KEY_TEMPERATURE],
            reasoningEffort = this[KEY_REASONING_EFFORT].toEnum(ReasoningEffort.FOLLOW_DEFAULT),
            maxTokens = this[KEY_MAX_TOKENS],
            imageDetail = this[KEY_IMAGE_DETAIL].toEnum(ImageDetail.FOLLOW_DEFAULT),
            includeUsage = this[KEY_INCLUDE_USAGE] ?: true,
            // 轮次单位是 v6 引入的；旧 key（消息条数）按约定统一归一到默认 1 轮。
            historyImageTurns = this[KEY_HISTORY_IMAGE_TURNS] ?: ChatSettings.DEFAULT_HISTORY_IMAGE_TURNS,
            imageSearchModelsRaw = this[KEY_IMAGE_SEARCH_MODELS].orEmpty(),
            extraParams = this[KEY_EXTRA_PARAMS].orEmpty(),
            themeMode = this[KEY_THEME_MODE].toEnum(ThemeMode.SYSTEM),
        )
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: fallback

    private companion object {
        val KEY_PROVIDERS = stringPreferencesKey("providers_json")
        val KEY_ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
        val KEY_SEARCH_PROVIDER = stringPreferencesKey("search_provider")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val KEY_TEMPERATURE = doublePreferencesKey("temperature")
        val KEY_REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val KEY_MAX_TOKENS = intPreferencesKey("max_tokens")
        val KEY_IMAGE_DETAIL = stringPreferencesKey("image_detail")
        val KEY_INCLUDE_USAGE = booleanPreferencesKey("include_usage")
        /** 轮次单位（v6）：历史图片重发轮次。旧 key `history_image_limit` 已废弃。 */
        val KEY_HISTORY_IMAGE_TURNS = intPreferencesKey("history_image_turns")
        /** 图搜模型链覆盖（空 = 用 Qwen 预设默认）。 */
        val KEY_IMAGE_SEARCH_MODELS = stringPreferencesKey("image_search_models")
        val KEY_EXTRA_PARAMS = stringPreferencesKey("extra_params")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

        /** 旧版本单配置 key：只在懒迁移时读，不再写入。 */
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_MODEL = stringPreferencesKey("model")
    }
}
