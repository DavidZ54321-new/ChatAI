package com.zcw.chatai.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zcw.chatai.data.image.ImageModels
import com.zcw.chatai.data.image.openai.OpenAiImageOptions
import com.zcw.chatai.data.image.qwen.QwenImageOptions
import com.zcw.chatai.data.video.VideoModels
import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.ChatApiException
import com.zcw.chatai.data.net.EndpointUrl
import com.zcw.chatai.data.persona.PersonaConfigCodec
import com.zcw.chatai.data.persona.PersonaEntry
import com.zcw.chatai.data.provider.AnthropicBaseLayout
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.provider.ProviderConfigCodec
import com.zcw.chatai.data.provider.ProviderEntry
import com.zcw.chatai.data.provider.ResponsesRequestWire
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
    /** 流式正文吐字时轻震（默认开）。只影响界面，不进请求。 */
    val streamHaptic: Boolean = true,
    /** 历史图片重发轮次：-1 全部、0 只发当前轮、N = 当前轮 + 最近 N 轮。 */
    val historyImageTurns: Int,
    /** 图搜模型链（原始输入，逗号分隔）；空 = 用 Qwen 预设的默认链。 */
    val imageSearchModelsRaw: String,
    /** 生图/改图模型覆盖；空 = 用内置默认（`ImageModels.DEFAULT`）。 */
    val imageGenModelRaw: String = "",
    /** 是否让服务端做提示词智能改写（Qwen `prompt_extend`，默认开）。 */
    val imagePromptExtend: Boolean = true,
    /** OpenAI 生图的质量、尺寸、背景、格式、张数。空字段不下发。 */
    val openAiImage: OpenAiImageOptions = OpenAiImageOptions(),
    /** 千问生图的尺寸、张数、反向提示词、种子、水印和改写方式。 */
    val qwenImage: QwenImageOptions = QwenImageOptions(),
    /** 视频生成模型覆盖；空 = 用内置默认（`VideoModels.DEFAULT`）。 */
    val videoGenModelRaw: String = "",
    /** 视频提示词智能改写（DashScope `prompt_extend`，默认开）。 */
    val videoPromptExtend: Boolean = true,
    /** 图像工作区。空 = 还没选过，回落到千问和 [imageGenModel]。 */
    val imageWorkspace: WorkspaceMemory = WorkspaceMemory(),
    /** 视频工作区。空 = 还没选过，回落到千问和 [videoGenModel]。 */
    val videoWorkspace: WorkspaceMemory = WorkspaceMemory(),
    /** 文本模型面板最后一次选择。空 = 用激活供应商和它的模型栏。 */
    val textWorkspace: WorkspaceMemory = WorkspaceMemory(),
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

    /** 生效的生图模型：用户覆盖优先，空则内置默认。 */
    val imageGenModel: String
        get() = ImageModels.resolve(imageGenModelRaw)

    /** 生效的视频生成模型：用户覆盖优先，空则内置默认。 */
    val videoGenModel: String
        get() = VideoModels.resolve(videoGenModelRaw)

    /** 图像工作区当前配置。空白页、新图像、还没建会话时用它，不看文本区供应商。 */
    val resolvedImageWorkspaceProvider: String
        get() = imageWorkspace.providerId.trim().ifBlank { ProviderCatalog.QWEN }

    val resolvedImageWorkspaceModel: String
        get() = imageWorkspace.model.trim().ifBlank { imageGenModel }

    /** 视频工作区当前配置。生视频用这里的模型，不看文本区。 */
    val resolvedVideoWorkspaceProvider: String
        get() = videoWorkspace.providerId.trim().ifBlank { ProviderCatalog.QWEN }

    val resolvedVideoWorkspaceModel: String
        get() = videoWorkspace.model.trim().ifBlank { videoGenModel }

    /** 文本区最后一次在模型面板里选的供应商。空则用激活供应商。 */
    val resolvedTextWorkspaceProvider: String
        get() = textWorkspace.providerId.trim().ifBlank { activeProviderId }

    /** 文本区最后一次选的对话模型。空则用该供应商设置里的模型栏。 */
    val resolvedTextWorkspaceModel: String
        get() {
            val chosen = textWorkspace.model.trim()
            if (chosen.isNotEmpty()) return chosen
            return providers[resolvedTextWorkspaceProvider]?.model?.trim().orEmpty()
        }

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
            streamHaptic = true,
            historyImageTurns = DEFAULT_HISTORY_IMAGE_TURNS,
            imageSearchModelsRaw = "",
            imageGenModelRaw = "",
            imagePromptExtend = true,
            videoGenModelRaw = "",
            videoPromptExtend = true,
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
    val preset = ProviderCatalog.byId(providerId)
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
        sendSessionHeader = preset?.sendSessionHeader == true,
        webSearchEnabled = false,
        thinkingWire = ProviderCatalog.thinkingWireFor(providerId),
        supportsVideo = ProviderCatalog.supportsVideo(providerId, entry),
        supportsAudio = ProviderCatalog.supportsAudio(providerId, entry),
        responsesWire = preset?.responsesWire ?: ResponsesRequestWire.OMIT_REASONING,
        hostedWebSearch = preset?.hostedWebSearch == true,
        responsesPrimary = preset?.responsesPrimary == true,
        anthropicBaseUrl = EndpointUrl.anthropicBase(
            entry.baseUrl,
            ProviderCatalog.byId(providerId)?.anthropicBaseLayout
                ?: AnthropicBaseLayout.SAME_V1,
            entry.anthropicBaseUrl,
        ).orEmpty(),
        responsesBaseUrl = EndpointUrl.responsesBase(entry.baseUrl, entry.responsesBaseUrl).orEmpty(),
    )
}

/**
 * 图像/视频「优化」只借用文本供应商的地址、密钥和模型名。
 * 角色的系统提示词、温度、最大长度、附加参数都不跟过去。
 * 思考固定关掉。改写任务自己的系统说明在请求消息里，不在这里。
 */
fun ChatSettings.requireTextRewriteConfig(): ChatConfig {
    val providerId = resolvedTextWorkspaceProvider
    val name = ProviderCatalog.displayName(providerId)
    val entry = providers[providerId]
    if (entry == null || entry.apiKey.isBlank()) {
        throw ChatApiException("提示词优化需要文本供应商「$name」的 API Key，请到设置里配置")
    }
    val model = resolvedTextWorkspaceModel
    if (model.isBlank()) {
        throw ChatApiException("提示词优化需要文本供应商「$name」的对话模型，请到设置里填写")
    }
    return toChatConfig(providerId).copy(
        model = model,
        systemPrompt = "",
        temperature = null,
        reasoningEffort = ReasoningEffort.OFF.wire,
        maxTokens = null,
        extraParams = null,
        enabledTools = emptyList(),
        webSearchEnabled = false,
        includeEnvTime = false,
        includeUsage = false,
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
        imageGenModelRaw: String,
        imagePromptExtend: Boolean,
        videoGenModelRaw: String,
        videoPromptExtend: Boolean,
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
            if (imageGenModelRaw.isBlank()) prefs.remove(KEY_IMAGE_GEN_MODEL) else prefs[KEY_IMAGE_GEN_MODEL] = imageGenModelRaw
            prefs[KEY_IMAGE_PROMPT_EXTEND] = imagePromptExtend
            if (videoGenModelRaw.isBlank()) prefs.remove(KEY_VIDEO_GEN_MODEL) else prefs[KEY_VIDEO_GEN_MODEL] = videoGenModelRaw
            prefs[KEY_VIDEO_PROMPT_EXTEND] = videoPromptExtend
        }
    }

    /** 会话内切换角色：只改激活角色（新会话记住上次选择），角色表不动。 */
    suspend fun updateActivePersona(personaId: String) {
        store.edit { prefs ->
            prefs[KEY_ACTIVE_PERSONA] = personaId
        }
    }

    /**
     * 备份还原专用：一次 edit 写全**所有**设置（含主题）。
     * 与 [updateConfig] 的区别是它把主题也一起写，且不看设置页的表单状态——
     * 导入之后界面立刻按新主题与供应商渲染，不需要用户再点一次保存。
     * 合并导入时先用 `BackupCodec.mergeSettings` 算出结果再传进来。
     */
    suspend fun replaceAll(settings: ChatSettings) {
        store.edit { prefs ->
            prefs[KEY_PROVIDERS] = ProviderConfigCodec.encode(settings.providers)
            prefs[KEY_ACTIVE_PROVIDER] = settings.activeProviderId
            if (settings.searchProviderId.isNullOrBlank()) {
                prefs.remove(KEY_SEARCH_PROVIDER)
            } else {
                prefs[KEY_SEARCH_PROVIDER] = settings.searchProviderId
            }
            prefs[KEY_PERSONAS] = PersonaConfigCodec.encode(settings.personas)
            prefs[KEY_ACTIVE_PERSONA] = settings.activePersonaId
            prefs[KEY_IMAGE_DETAIL] = settings.imageDetail.name
            prefs[KEY_INCLUDE_USAGE] = settings.includeUsage
            prefs[KEY_INCLUDE_ENV_TIME] = settings.includeEnvTime
            prefs[KEY_STREAM_HAPTIC] = settings.streamHaptic
            prefs[KEY_HISTORY_IMAGE_TURNS] = settings.historyImageTurns
            if (settings.imageSearchModelsRaw.isBlank()) {
                prefs.remove(KEY_IMAGE_SEARCH_MODELS)
            } else {
                prefs[KEY_IMAGE_SEARCH_MODELS] = settings.imageSearchModelsRaw
            }
            if (settings.imageGenModelRaw.isBlank()) {
                prefs.remove(KEY_IMAGE_GEN_MODEL)
            } else {
                prefs[KEY_IMAGE_GEN_MODEL] = settings.imageGenModelRaw
            }
            prefs[KEY_IMAGE_PROMPT_EXTEND] = settings.imagePromptExtend
            prefs.writeOpenAiImage(settings.openAiImage)
            prefs.writeQwenImage(settings.qwenImage)
            if (settings.videoGenModelRaw.isBlank()) {
                prefs.remove(KEY_VIDEO_GEN_MODEL)
            } else {
                prefs[KEY_VIDEO_GEN_MODEL] = settings.videoGenModelRaw
            }
            prefs[KEY_VIDEO_PROMPT_EXTEND] = settings.videoPromptExtend
            prefs.writeWorkspace(WorkspaceSlot.Image, settings.imageWorkspace)
            prefs.writeWorkspace(WorkspaceSlot.Video, settings.videoWorkspace)
            prefs.writeWorkspace(WorkspaceSlot.Text, settings.textWorkspace)
            prefs[KEY_THEME_MODE] = settings.themeMode.name
            prefs[KEY_THEME_FAMILY] = settings.themeFamily.name
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

    /**
     * 只改一张角色的思考强度。读改写都在同一次 [edit] 里，不写激活角色 id，
     * 所以和「切换角色」叠在一起时不会把激活 id 盖回去。
     */
    suspend fun updatePersonaReasoning(personaId: String, effort: ReasoningEffort) {
        store.edit { prefs ->
            val current = PersonaConfigCodec.decode(prefs[KEY_PERSONAS])
            val updated = PersonaConfigCodec.replacingReasoning(current, personaId, effort)
                ?: return@edit
            if (updated === current) return@edit
            prefs[KEY_PERSONAS] = PersonaConfigCodec.encode(updated)
        }
    }

    /** 只改 OpenAI 生图参数，不碰供应商表。取值先收干净再落盘。 */
    suspend fun updateOpenAiImage(options: OpenAiImageOptions) {
        val clean = options.sanitized()
        store.edit { prefs -> prefs.writeOpenAiImage(clean) }
    }

    /** 只改千问生图参数，不碰供应商表。 */
    suspend fun updateQwenImage(options: QwenImageOptions) {
        val clean = options.sanitized()
        store.edit { prefs -> prefs.writeQwenImage(clean) }
    }

    /** 记下某个工作区的供应商和模型。空值不写。不改另一区，也不改激活供应商。 */
    suspend fun updateWorkspace(slot: WorkspaceSlot, providerId: String, model: String) {
        if (providerId.isBlank() || model.isBlank()) return
        store.edit { prefs ->
            prefs.writeWorkspace(slot, WorkspaceMemory(providerId, model))
        }
    }

    /** 图像面板里的智能改写开关，和设置页是同一个 key。 */
    suspend fun updateImagePromptExtend(enabled: Boolean) {
        store.edit { prefs -> prefs[KEY_IMAGE_PROMPT_EXTEND] = enabled }
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

    /** 流式轻震开关：单独落盘，不跟供应商表的「保存」绑在一起。 */
    suspend fun updateStreamHaptic(enabled: Boolean) {
        store.edit { prefs ->
            prefs[KEY_STREAM_HAPTIC] = enabled
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
            streamHaptic = this[KEY_STREAM_HAPTIC] ?: true,
            // 轮次单位是 v6 引入的；旧 key（消息条数）按约定统一归一到默认 1 轮。
            historyImageTurns = this[KEY_HISTORY_IMAGE_TURNS] ?: ChatSettings.DEFAULT_HISTORY_IMAGE_TURNS,
            imageSearchModelsRaw = this[KEY_IMAGE_SEARCH_MODELS].orEmpty(),
            imageGenModelRaw = this[KEY_IMAGE_GEN_MODEL].orEmpty(),
            imagePromptExtend = this[KEY_IMAGE_PROMPT_EXTEND] ?: true,
            openAiImage = OpenAiImageOptions(
                quality = this[KEY_OPENAI_IMAGE_QUALITY].orEmpty(),
                size = this[KEY_OPENAI_IMAGE_SIZE].orEmpty(),
                background = this[KEY_OPENAI_IMAGE_BACKGROUND].orEmpty(),
                outputFormat = this[KEY_OPENAI_IMAGE_FORMAT].orEmpty(),
                outputCompression = this[KEY_OPENAI_IMAGE_COMPRESSION],
                count = this[KEY_OPENAI_IMAGE_COUNT] ?: 1,
            ).sanitized(),
            qwenImage = QwenImageOptions(
                size = this[KEY_QWEN_IMAGE_SIZE].orEmpty(),
                count = this[KEY_QWEN_IMAGE_COUNT] ?: 1,
                negativePrompt = this[KEY_QWEN_IMAGE_NEGATIVE].orEmpty(),
                seed = this[KEY_QWEN_IMAGE_SEED],
                watermark = this[KEY_QWEN_IMAGE_WATERMARK] ?: false,
                promptExtendMode = this[KEY_QWEN_IMAGE_EXTEND_MODE].orEmpty(),
                enableThinking = when (this[KEY_QWEN_IMAGE_THINKING]) {
                    "true" -> true
                    "false" -> false
                    else -> null
                },
            ).sanitized(),
            videoGenModelRaw = this[KEY_VIDEO_GEN_MODEL].orEmpty(),
            imageWorkspace = readWorkspace(WorkspaceSlot.Image),
            videoWorkspace = readWorkspace(WorkspaceSlot.Video),
            textWorkspace = readWorkspace(WorkspaceSlot.Text),
            videoPromptExtend = this[KEY_VIDEO_PROMPT_EXTEND] ?: true,
            themeMode = this[KEY_THEME_MODE].toEnum(ThemeMode.SYSTEM),
            themeFamily = this[KEY_THEME_FAMILY].toEnum(ThemeFamily.CLAUDE),
        )
    }

    private fun Preferences.readWorkspace(slot: WorkspaceSlot): WorkspaceMemory {
        val (providerKey, modelKey) = slot.keys()
        return WorkspaceMemory(
            providerId = this[providerKey].orEmpty(),
            model = this[modelKey].orEmpty(),
        )
    }

    private fun MutablePreferences.writeWorkspace(slot: WorkspaceSlot, memory: WorkspaceMemory) {
        val (providerKey, modelKey) = slot.keys()
        fun putOrRemove(key: Preferences.Key<String>, value: String) {
            if (value.isBlank()) remove(key) else this[key] = value
        }
        putOrRemove(providerKey, memory.providerId)
        putOrRemove(modelKey, memory.model)
    }

    private fun WorkspaceSlot.keys(): Pair<Preferences.Key<String>, Preferences.Key<String>> = when (this) {
        WorkspaceSlot.Image -> KEY_IMAGE_WORKSPACE_PROVIDER to KEY_IMAGE_WORKSPACE_MODEL
        WorkspaceSlot.Video -> KEY_VIDEO_WORKSPACE_PROVIDER to KEY_VIDEO_WORKSPACE_MODEL
        WorkspaceSlot.Text -> KEY_TEXT_WORKSPACE_PROVIDER to KEY_TEXT_WORKSPACE_MODEL
    }

    private fun MutablePreferences.writeOpenAiImage(options: OpenAiImageOptions) {
        val clean = options.sanitized()
        fun putOrRemove(key: Preferences.Key<String>, value: String) {
            if (value.isBlank()) remove(key) else this[key] = value
        }
        putOrRemove(KEY_OPENAI_IMAGE_QUALITY, clean.quality)
        putOrRemove(KEY_OPENAI_IMAGE_SIZE, clean.size)
        putOrRemove(KEY_OPENAI_IMAGE_BACKGROUND, clean.background)
        putOrRemove(KEY_OPENAI_IMAGE_FORMAT, clean.outputFormat)
        val compression = clean.outputCompression
        if (compression == null) remove(KEY_OPENAI_IMAGE_COMPRESSION) else this[KEY_OPENAI_IMAGE_COMPRESSION] = compression
        this[KEY_OPENAI_IMAGE_COUNT] = clean.count
    }

    private fun MutablePreferences.writeQwenImage(options: QwenImageOptions) {
        val clean = options.sanitized()
        fun putOrRemove(key: Preferences.Key<String>, value: String) {
            if (value.isBlank()) remove(key) else this[key] = value
        }
        putOrRemove(KEY_QWEN_IMAGE_SIZE, clean.size)
        this[KEY_QWEN_IMAGE_COUNT] = clean.count
        putOrRemove(KEY_QWEN_IMAGE_NEGATIVE, clean.negativePrompt)
        val seed = clean.seed
        if (seed == null) remove(KEY_QWEN_IMAGE_SEED) else this[KEY_QWEN_IMAGE_SEED] = seed
        this[KEY_QWEN_IMAGE_WATERMARK] = clean.watermark
        putOrRemove(KEY_QWEN_IMAGE_EXTEND_MODE, clean.promptExtendMode)
        when (clean.enableThinking) {
            true -> this[KEY_QWEN_IMAGE_THINKING] = "true"
            false -> this[KEY_QWEN_IMAGE_THINKING] = "false"
            null -> remove(KEY_QWEN_IMAGE_THINKING)
        }
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
        /** 流式正文轻震（默认开；老版本无此 key 时回落 true）。 */
        val KEY_STREAM_HAPTIC = booleanPreferencesKey("stream_haptic")
        /** 轮次单位（v6）：历史图片重发轮次。旧 key `history_image_limit` 已废弃。 */
        val KEY_HISTORY_IMAGE_TURNS = intPreferencesKey("history_image_turns")
        /** 图搜模型链覆盖（空 = 用 Qwen 预设默认）。 */
        val KEY_IMAGE_SEARCH_MODELS = stringPreferencesKey("image_search_models")
        /** 生图/改图模型覆盖（空 = 内置默认）。 */
        val KEY_IMAGE_GEN_MODEL = stringPreferencesKey("image_gen_model")
        /** 生图提示词智能改写（Qwen prompt_extend，默认开）。 */
        val KEY_IMAGE_PROMPT_EXTEND = booleanPreferencesKey("image_prompt_extend")
        val KEY_OPENAI_IMAGE_QUALITY = stringPreferencesKey("openai_image_quality")
        val KEY_OPENAI_IMAGE_SIZE = stringPreferencesKey("openai_image_size")
        val KEY_OPENAI_IMAGE_BACKGROUND = stringPreferencesKey("openai_image_background")
        val KEY_OPENAI_IMAGE_FORMAT = stringPreferencesKey("openai_image_format")
        val KEY_OPENAI_IMAGE_COMPRESSION = intPreferencesKey("openai_image_compression")
        val KEY_OPENAI_IMAGE_COUNT = intPreferencesKey("openai_image_count")
        val KEY_QWEN_IMAGE_SIZE = stringPreferencesKey("qwen_image_size")
        val KEY_QWEN_IMAGE_COUNT = intPreferencesKey("qwen_image_count")
        val KEY_QWEN_IMAGE_NEGATIVE = stringPreferencesKey("qwen_image_negative")
        val KEY_QWEN_IMAGE_SEED = intPreferencesKey("qwen_image_seed")
        val KEY_QWEN_IMAGE_WATERMARK = booleanPreferencesKey("qwen_image_watermark")
        val KEY_QWEN_IMAGE_EXTEND_MODE = stringPreferencesKey("qwen_image_extend_mode")
        val KEY_QWEN_IMAGE_THINKING = stringPreferencesKey("qwen_image_thinking")
        /** 视频生成模型覆盖（空 = 内置默认 wan3.0-video）。 */
        val KEY_VIDEO_GEN_MODEL = stringPreferencesKey("video_gen_model")
        /** 视频提示词智能改写（DashScope prompt_extend，默认开）。 */
        val KEY_VIDEO_PROMPT_EXTEND = booleanPreferencesKey("video_prompt_extend")
        val KEY_IMAGE_WORKSPACE_PROVIDER = stringPreferencesKey("image_workspace_provider")
        val KEY_IMAGE_WORKSPACE_MODEL = stringPreferencesKey("image_workspace_model")
        val KEY_VIDEO_WORKSPACE_PROVIDER = stringPreferencesKey("video_workspace_provider")
        val KEY_VIDEO_WORKSPACE_MODEL = stringPreferencesKey("video_workspace_model")
        val KEY_TEXT_WORKSPACE_PROVIDER = stringPreferencesKey("text_workspace_provider")
        val KEY_TEXT_WORKSPACE_MODEL = stringPreferencesKey("text_workspace_model")
        val KEY_EXTRA_PARAMS = stringPreferencesKey("extra_params")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_THEME_FAMILY = stringPreferencesKey("theme_family")

        /** 旧版本单配置 key：只在懒迁移时读，不再写入。 */
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_MODEL = stringPreferencesKey("model")
    }
}
