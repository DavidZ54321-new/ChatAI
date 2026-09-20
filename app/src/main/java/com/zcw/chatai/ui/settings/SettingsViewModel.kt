package com.zcw.chatai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zcw.chatai.data.net.ChatApi
import com.zcw.chatai.data.prefs.ChatSettings
import com.zcw.chatai.data.prefs.ImageDetail
import com.zcw.chatai.data.prefs.SettingsRepository
import com.zcw.chatai.data.prefs.ThemeMode
import com.zcw.chatai.data.prefs.toChatConfig
import com.zcw.chatai.data.net.EndpointUrl
import com.zcw.chatai.data.provider.AnthropicBaseLayout
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.provider.ProviderEntry
import com.zcw.chatai.data.provider.ToolModels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val chatApi: ChatApi,
) : ViewModel() {

    data class FormState(
        val providers: Map<String, ProviderEntry> = emptyMap(),
        val activeProviderId: String = ProviderCatalog.DEEPSEEK,
        /** 联网搜索后端；null = 跟随会话。 */
        val searchProviderId: String? = null,
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
        /** 空 = 用该供应商规则从 Chat Base 推导。 */
        val anthropicBaseUrl: String = "",
        val responsesBaseUrl: String = "",
        /** 提示词与生成参数已搬进角色表（角色管理页维护），这里只读激活角色名做提示。 */
        val activePersonaName: String = "",
        val imageDetail: ImageDetail = ImageDetail.FOLLOW_DEFAULT,
        val includeUsage: Boolean = true,
        val includeEnvTime: Boolean = true,
        val historyImageTurns: Int = ChatSettings.DEFAULT_HISTORY_IMAGE_TURNS,
        /** 图搜模型链（逗号分隔的原始输入）；空 = 用内置默认。 */
        val imageSearchModels: String = "",
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val loaded: Boolean = false,
        val models: List<String> = emptyList(),
        val busy: Boolean = false,
        val status: String? = null,
        val error: String? = null,
    ) {
        val imageSearchModelsError: String?
            get() = ToolModels.validate(imageSearchModels)

        val canSave: Boolean
            get() = baseUrl.isNotBlank() && model.isNotBlank() &&
                imageSearchModelsError == null

        val displayedAnthropicBase: String
            get() = anthropicBaseUrl.ifBlank { derivedAnthropicBase(activeProviderId, baseUrl) }

        val displayedResponsesBase: String
            get() = responsesBaseUrl.ifBlank { derivedResponsesBase(baseUrl) }

        fun toEntry(): ProviderEntry {
            val chat = baseUrl.trim()
            return ProviderEntry(
                baseUrl = chat,
                apiKey = apiKey.trim(),
                model = model.trim(),
                anthropicBaseUrl = storedOverride(anthropicBaseUrl, derivedAnthropicBase(activeProviderId, chat)),
                responsesBaseUrl = storedOverride(responsesBaseUrl, derivedResponsesBase(chat)),
            )
        }
    }

    private val form = MutableStateFlow(FormState())

    val state: StateFlow<FormState> = form.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val entry = settings.activeProvider
            form.value = FormState(
                providers = settings.providers,
                activeProviderId = settings.activeProviderId,
                searchProviderId = settings.searchProviderId,
                baseUrl = entry.baseUrl,
                apiKey = entry.apiKey,
                model = entry.model,
                anthropicBaseUrl = entry.anthropicBaseUrl,
                responsesBaseUrl = entry.responsesBaseUrl,
                activePersonaName = settings.activePersona.name,
                imageDetail = settings.imageDetail,
                includeUsage = settings.includeUsage,
                includeEnvTime = settings.includeEnvTime,
                historyImageTurns = settings.historyImageTurns,
                imageSearchModels = settings.imageSearchModelsRaw,
                themeMode = settings.themeMode,
                loaded = true,
            )
        }
    }

    fun update(transform: (FormState) -> FormState) {
        form.value = transform(form.value)
    }

    /**
     * 切换正在编辑的供应商：当前表单值先塞回内存表（未保存也不丢），
     * 再载入目标供应商；真正的落盘发生在 [save]。
     */
    fun selectProvider(id: String) {
        val current = form.value
        if (id == current.activeProviderId) return
        val stash = current.providers + (
            current.activeProviderId to current.toEntry()
            )
        val preset = ProviderCatalog.byId(id)
        val target = stash[id] ?: ProviderEntry(
            baseUrl = preset?.defaultBaseUrl.orEmpty(),
            apiKey = "",
            model = preset?.defaultModel.orEmpty(),
        )
        form.value = current.copy(
            providers = stash + (id to target),
            activeProviderId = id,
            baseUrl = target.baseUrl,
            apiKey = target.apiKey,
            model = target.model,
            anthropicBaseUrl = target.anthropicBaseUrl,
            responsesBaseUrl = target.responsesBaseUrl,
            models = emptyList(),
            status = null,
            error = null,
        )
    }

    fun setThemeMode(mode: ThemeMode) {
        form.value = form.value.copy(themeMode = mode)
        viewModelScope.launch { settingsRepository.updateThemeMode(mode) }
    }

    fun save() {
        val current = form.value
        if (!current.canSave) return
        val entry = current.toEntry()
        // 整表落盘：把当前编辑值写回表里，其它供应商保留（含切走时暂存的未保存修改）。
        // 角色表不在此保存（角色管理页整表落盘），这里只透传当前快照避免覆盖。
        val providers = current.providers + (current.activeProviderId to entry)
        viewModelScope.launch {
            val snapshot = settingsRepository.settings.first()
            settingsRepository.updateConfig(
                providers = providers,
                activeProviderId = current.activeProviderId,
                searchProviderId = current.searchProviderId,
                personas = snapshot.personas,
                activePersonaId = snapshot.resolvedActivePersonaId,
                imageDetail = current.imageDetail,
                includeUsage = current.includeUsage,
                includeEnvTime = current.includeEnvTime,
                historyImageTurns = current.historyImageTurns,
                imageSearchModelsRaw = current.imageSearchModels.trim(),
            )
            form.value = form.value.copy(providers = providers, status = "已保存")
        }
    }

    /** 测试连接 = GET /models（免费，同时能拉回模型列表）。 */
    fun testConnection() {
        val current = form.value
        viewModelScope.launch {
            form.value = form.value.copy(busy = true, status = null, error = null)
            val config = settingsRepository.settings.first()
                .toChatConfig(current.activeProviderId)
                .copy(
                    baseUrl = current.baseUrl.trim(),
                    apiKey = current.apiKey.trim(),
                    model = current.model.trim(),
                )
            runCatching { chatApi.listModels(config) }
                .onSuccess { models ->
                    form.value = form.value.copy(
                        busy = false,
                        models = models,
                        status = if (models.isEmpty()) "连接成功，但服务端没有返回模型列表" else "连接成功，拉取到 ${models.size} 个模型",
                    )
                }
                .onFailure { t ->
                    form.value = form.value.copy(busy = false, error = t.message ?: "连接失败")
                }
        }
    }

    fun dismissMessages() {
        form.value = form.value.copy(status = null, error = null)
    }

    companion object {
        fun factory(
            settingsRepository: SettingsRepository,
            chatApi: ChatApi,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(settingsRepository, chatApi) }
        }

        /** 温度：空串合法（表示服务端默认）；角色编辑器与设置页共用同一条校验。 */
        fun validateTemperature(text: String): String? =
            text.trim().takeIf { it.isNotEmpty() }?.let {
                val value = it.toDoubleOrNull()
                when {
                    value == null -> "请输入数字"
                    value < 0.0 || value > 2.0 -> "取值范围 0 ~ 2"
                    else -> null
                }
            }

        /** 回复长度上限：空串合法（表示服务端默认）；角色编辑器与设置页共用同一条校验。 */
        fun validateMaxTokens(text: String): String? =
            text.trim().takeIf { it.isNotEmpty() }?.let {
                when {
                    it.toIntOrNull() == null -> "请输入整数"
                    it.toInt() <= 0 -> "必须大于 0"
                    else -> null
                }
            }

        /** 附加参数必须是 JSON 对象；空串合法（表示不附加）。 */
        fun derivedAnthropicBase(providerId: String, chatBaseUrl: String): String =
            EndpointUrl.anthropicBase(
                chatBaseUrl,
                ProviderCatalog.byId(providerId)?.anthropicBaseLayout ?: AnthropicBaseLayout.SAME_V1,
            ).orEmpty()

        fun derivedResponsesBase(chatBaseUrl: String): String =
            EndpointUrl.responsesBase(chatBaseUrl).orEmpty()

        fun storedOverride(displayed: String, derived: String): String {
            val trimmed = displayed.trim()
            return if (trimmed.isEmpty() || trimmed == derived) "" else trimmed
        }

        fun validateExtraParams(text: String): String? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val element = try {
                Json.parseToJsonElement(trimmed)
            } catch (t: Exception) {
                return "不是合法的 JSON"
            }
            return if (element is JsonObject) null else "必须是 JSON 对象，例如 {\"top_k\": 20}"
        }
    }
}
