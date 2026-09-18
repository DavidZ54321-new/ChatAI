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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 图片能力：AUTO 用启发式表判断，其余为用户手动覆盖。 */
enum class VisionOverride { AUTO, SUPPORTED, UNSUPPORTED }

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

data class ChatSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val temperature: Double?,
    val reasoningEffort: ReasoningEffort,
    val maxTokens: Int?,
    val imageDetail: ImageDetail,
    val includeUsage: Boolean,
    val historyImageLimit: Int,
    val extraParams: String,
    val visionOverride: VisionOverride,
    val themeMode: ThemeMode,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-flash"
        const val DEFAULT_HISTORY_IMAGE_LIMIT = 2

        val Default = ChatSettings(
            baseUrl = DEFAULT_BASE_URL,
            apiKey = "",
            model = DEFAULT_MODEL,
            systemPrompt = "",
            temperature = null,
            reasoningEffort = ReasoningEffort.FOLLOW_DEFAULT,
            maxTokens = null,
            imageDetail = ImageDetail.FOLLOW_DEFAULT,
            includeUsage = true,
            historyImageLimit = DEFAULT_HISTORY_IMAGE_LIMIT,
            extraParams = "",
            visionOverride = VisionOverride.AUTO,
            themeMode = ThemeMode.SYSTEM,
        )
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<ChatSettings> = store.data.map { it.toChatSettings() }

    fun chatConfig(): Flow<ChatConfig> = settings.map { settings ->
        ChatConfig(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = settings.model,
            systemPrompt = settings.systemPrompt,
            temperature = settings.temperature,
            reasoningEffort = settings.reasoningEffort.wire,
            maxTokens = settings.maxTokens,
            imageDetail = settings.imageDetail.wire,
            includeUsage = settings.includeUsage,
            historyImageLimit = settings.historyImageLimit,
            extraParams = settings.extraParams.ifBlank { null },
            webSearchEnabled = false,
        )
    }

    suspend fun updateConfig(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        temperature: Double?,
        reasoningEffort: ReasoningEffort,
        maxTokens: Int?,
        imageDetail: ImageDetail,
        includeUsage: Boolean,
        historyImageLimit: Int,
        extraParams: String,
        visionOverride: VisionOverride,
    ) {
        store.edit { prefs ->
            prefs[KEY_BASE_URL] = baseUrl
            prefs[KEY_API_KEY] = apiKey
            prefs[KEY_MODEL] = model
            prefs[KEY_SYSTEM_PROMPT] = systemPrompt
            if (temperature == null) prefs.remove(KEY_TEMPERATURE) else prefs[KEY_TEMPERATURE] = temperature
            prefs[KEY_REASONING_EFFORT] = reasoningEffort.name
            if (maxTokens == null) prefs.remove(KEY_MAX_TOKENS) else prefs[KEY_MAX_TOKENS] = maxTokens
            prefs[KEY_IMAGE_DETAIL] = imageDetail.name
            prefs[KEY_INCLUDE_USAGE] = includeUsage
            prefs[KEY_HISTORY_IMAGE_LIMIT] = historyImageLimit
            prefs[KEY_EXTRA_PARAMS] = extraParams
            prefs[KEY_VISION_OVERRIDE] = visionOverride.name
        }
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        store.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    private fun Preferences.toChatSettings(): ChatSettings = ChatSettings(
        baseUrl = this[KEY_BASE_URL] ?: ChatSettings.DEFAULT_BASE_URL,
        apiKey = this[KEY_API_KEY].orEmpty(),
        model = this[KEY_MODEL] ?: ChatSettings.DEFAULT_MODEL,
        systemPrompt = this[KEY_SYSTEM_PROMPT].orEmpty(),
        temperature = this[KEY_TEMPERATURE],
        reasoningEffort = this[KEY_REASONING_EFFORT].toEnum(ReasoningEffort.FOLLOW_DEFAULT),
        maxTokens = this[KEY_MAX_TOKENS],
        imageDetail = this[KEY_IMAGE_DETAIL].toEnum(ImageDetail.FOLLOW_DEFAULT),
        includeUsage = this[KEY_INCLUDE_USAGE] ?: true,
        historyImageLimit = this[KEY_HISTORY_IMAGE_LIMIT] ?: ChatSettings.DEFAULT_HISTORY_IMAGE_LIMIT,
        extraParams = this[KEY_EXTRA_PARAMS].orEmpty(),
        visionOverride = this[KEY_VISION_OVERRIDE].toEnum(VisionOverride.AUTO),
        themeMode = this[KEY_THEME_MODE].toEnum(ThemeMode.SYSTEM),
    )

    private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: fallback

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_MODEL = stringPreferencesKey("model")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val KEY_TEMPERATURE = doublePreferencesKey("temperature")
        val KEY_REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val KEY_MAX_TOKENS = intPreferencesKey("max_tokens")
        val KEY_IMAGE_DETAIL = stringPreferencesKey("image_detail")
        val KEY_INCLUDE_USAGE = booleanPreferencesKey("include_usage")
        val KEY_HISTORY_IMAGE_LIMIT = intPreferencesKey("history_image_limit")
        val KEY_EXTRA_PARAMS = stringPreferencesKey("extra_params")
        val KEY_VISION_OVERRIDE = stringPreferencesKey("vision_override")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
