package com.zcw.chatai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zcw.chatai.data.net.ChatApi
import com.zcw.chatai.data.prefs.ChatSettings
import com.zcw.chatai.data.prefs.ImageDetail
import com.zcw.chatai.data.prefs.ReasoningEffort
import com.zcw.chatai.data.prefs.SettingsRepository
import com.zcw.chatai.data.prefs.ThemeMode
import com.zcw.chatai.data.prefs.toChatConfig
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.provider.ProviderEntry
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
        val systemPrompt: String = "",
        val temperature: String = "",
        val reasoningEffort: ReasoningEffort = ReasoningEffort.FOLLOW_DEFAULT,
        val maxTokens: String = "",
        val imageDetail: ImageDetail = ImageDetail.FOLLOW_DEFAULT,
        val includeUsage: Boolean = true,
        val historyImageLimit: Int = ChatSettings.DEFAULT_HISTORY_IMAGE_LIMIT,
        val extraParams: String = "",
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val loaded: Boolean = false,
        val models: List<String> = emptyList(),
        val busy: Boolean = false,
        val status: String? = null,
        val error: String? = null,
    ) {
        val extraParamsError: String?
            get() = validateExtraParams(extraParams)

        val temperatureError: String?
            get() = temperature.trim().takeIf { it.isNotEmpty() }?.let {
                val value = it.toDoubleOrNull()
                when {
                    value == null -> "请输入数字"
                    value < 0.0 || value > 2.0 -> "取值范围 0 ~ 2"
                    else -> null
                }
            }

        val maxTokensError: String?
            get() = maxTokens.trim().takeIf { it.isNotEmpty() }?.let {
                when {
                    it.toIntOrNull() == null -> "请输入整数"
                    it.toInt() <= 0 -> "必须大于 0"
                    else -> null
                }
            }

        val canSave: Boolean
            get() = baseUrl.isNotBlank() && model.isNotBlank() &&
                extraParamsError == null && temperatureError == null && maxTokensError == null
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
                systemPrompt = settings.systemPrompt,
                temperature = settings.temperature?.toString().orEmpty(),
                reasoningEffort = settings.reasoningEffort,
                maxTokens = settings.maxTokens?.toString().orEmpty(),
                imageDetail = settings.imageDetail,
                includeUsage = settings.includeUsage,
                historyImageLimit = settings.historyImageLimit,
                extraParams = settings.extraParams,
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
            current.activeProviderId to ProviderEntry(
                baseUrl = current.baseUrl.trim(),
                apiKey = current.apiKey.trim(),
                model = current.model.trim(),
            )
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
        val entry = ProviderEntry(
            baseUrl = current.baseUrl.trim(),
            apiKey = current.apiKey.trim(),
            model = current.model.trim(),
        )
        // 整表落盘：把当前编辑值写回表里，其它供应商保留（含切走时暂存的未保存修改）。
        val providers = current.providers + (current.activeProviderId to entry)
        viewModelScope.launch {
            settingsRepository.updateConfig(
                providers = providers,
                activeProviderId = current.activeProviderId,
                searchProviderId = current.searchProviderId,
                systemPrompt = current.systemPrompt,
                temperature = current.temperature.trim().toDoubleOrNull(),
                reasoningEffort = current.reasoningEffort,
                maxTokens = current.maxTokens.trim().toIntOrNull(),
                imageDetail = current.imageDetail,
                includeUsage = current.includeUsage,
                historyImageLimit = current.historyImageLimit,
                extraParams = current.extraParams.trim(),
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

        /** 附加参数必须是 JSON 对象；空串合法（表示不附加）。 */
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
