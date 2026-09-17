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
import com.zcw.chatai.data.prefs.VisionOverride
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
        val visionOverride: VisionOverride = VisionOverride.AUTO,
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
            form.value = FormState(
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                model = settings.model,
                systemPrompt = settings.systemPrompt,
                temperature = settings.temperature?.toString().orEmpty(),
                reasoningEffort = settings.reasoningEffort,
                maxTokens = settings.maxTokens?.toString().orEmpty(),
                imageDetail = settings.imageDetail,
                includeUsage = settings.includeUsage,
                historyImageLimit = settings.historyImageLimit,
                extraParams = settings.extraParams,
                visionOverride = settings.visionOverride,
                themeMode = settings.themeMode,
                loaded = true,
            )
        }
    }

    fun update(transform: (FormState) -> FormState) {
        form.value = transform(form.value)
    }

    fun setThemeMode(mode: ThemeMode) {
        form.value = form.value.copy(themeMode = mode)
        viewModelScope.launch { settingsRepository.updateThemeMode(mode) }
    }

    fun save() {
        val current = form.value
        if (!current.canSave) return
        viewModelScope.launch {
            settingsRepository.updateConfig(
                baseUrl = current.baseUrl.trim(),
                apiKey = current.apiKey.trim(),
                model = current.model.trim(),
                systemPrompt = current.systemPrompt,
                temperature = current.temperature.trim().toDoubleOrNull(),
                reasoningEffort = current.reasoningEffort,
                maxTokens = current.maxTokens.trim().toIntOrNull(),
                imageDetail = current.imageDetail,
                includeUsage = current.includeUsage,
                historyImageLimit = current.historyImageLimit,
                extraParams = current.extraParams.trim(),
                visionOverride = current.visionOverride,
            )
            form.value = form.value.copy(status = "已保存")
        }
    }

    /** 测试连接 = GET /models（免费，同时能拉回模型列表）。 */
    fun testConnection() {
        val current = form.value
        viewModelScope.launch {
            form.value = form.value.copy(busy = true, status = null, error = null)
            val config = settingsRepository.chatConfig().first().copy(
                baseUrl = current.baseUrl.trim(),
                apiKey = current.apiKey.trim(),
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
