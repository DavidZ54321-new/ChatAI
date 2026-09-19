package com.zcw.chatai.data.net

import com.zcw.chatai.data.provider.AnthropicBaseLayout
import com.zcw.chatai.data.provider.ProviderCatalog

object EndpointUrl {
    private const val COMPLETIONS_PATH = "/chat/completions"
    private const val MODELS_PATH = "/models"
    private const val RESPONSES_PATH = "/responses"
    private const val VERSION_PATH = "/v1"
    private const val ANTHROPIC_PATH = "/anthropic"
    private const val APPS_ANTHROPIC_PATH = "/apps/anthropic"
    private const val MESSAGES_PATH = "/messages"

    fun chatCompletions(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return null
        val base = trimmed.trimEnd('/')
        if (base.endsWith(COMPLETIONS_PATH)) return base
        val versioned = if (base.endsWith(VERSION_PATH, ignoreCase = true)) base else base + VERSION_PATH
        return versioned + COMPLETIONS_PATH
    }

    /** OpenAI 兼容的 Responses API（Qwen 的原生工具只活在这里）。 */
    fun responses(baseUrl: String): String? {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        if (trimmed.endsWith(RESPONSES_PATH)) return trimmed
        val completions = chatCompletions(trimmed) ?: return null
        return completions.removeSuffix(COMPLETIONS_PATH) + RESPONSES_PATH
    }

    /** DashScope 临时文件上传凭证接口：固定挂在域名根的 `/api/v1/uploads`。 */
    fun dashScopeUploads(baseUrl: String): String? {
        val root = originOf(baseUrl) ?: return null
        return root + "/api/v1/uploads"
    }

    /** 从基址里取 scheme://host[:port]，取不到返回 null（用于推导同源的旁路端点）。 */
    fun originOf(baseUrl: String): String? {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val pathStart = trimmed.indexOf('/', schemeEnd + 3)
        return if (pathStart == -1) trimmed else trimmed.substring(0, pathStart)
    }

    /** 标准 OpenAI 端点，用来「拉取模型列表 / 测试连接」。 */
    fun models(baseUrl: String): String? {
        val completions = chatCompletions(baseUrl) ?: return null
        return completions.removeSuffix(COMPLETIONS_PATH) + MODELS_PATH
    }

    /** Chat 兼容面的 `/v1` 根（去掉 `/chat/completions`）。 */
    fun compatV1Root(baseUrl: String): String? {
        val completions = chatCompletions(baseUrl) ?: return null
        return completions.removeSuffix(COMPLETIONS_PATH)
    }

    /**
     * Anthropic 工具面的 v1 基址（不含 `/messages`）。
     * [override] 非空时原样采用（可带或不带 `/messages`）。
     */
    fun anthropicBase(
        chatBaseUrl: String,
        layout: AnthropicBaseLayout,
        override: String = "",
    ): String? {
        val custom = override.trim().trimEnd('/').removeSuffix(MESSAGES_PATH).trimEnd('/')
        if (custom.isNotEmpty()) return custom
        val origin = originOf(chatBaseUrl) ?: return null
        return when (layout) {
            AnthropicBaseLayout.ORIGIN_ANTHROPIC -> origin + ANTHROPIC_PATH + VERSION_PATH
            AnthropicBaseLayout.ORIGIN_APPS_ANTHROPIC -> origin + APPS_ANTHROPIC_PATH + VERSION_PATH
            AnthropicBaseLayout.SAME_V1 -> compatV1Root(chatBaseUrl)
        }
    }

    fun anthropicMessages(
        chatBaseUrl: String,
        layout: AnthropicBaseLayout = AnthropicBaseLayout.ORIGIN_ANTHROPIC,
        override: String = "",
    ): String? {
        val base = anthropicBase(chatBaseUrl, layout, override) ?: return null
        return if (base.endsWith(MESSAGES_PATH)) base else base + MESSAGES_PATH
    }

    fun anthropicMessagesFor(
        providerId: String,
        chatBaseUrl: String,
        override: String = "",
    ): String? {
        val layout = ProviderCatalog.byId(providerId)?.anthropicBaseLayout
            ?: AnthropicBaseLayout.SAME_V1
        return anthropicMessages(chatBaseUrl, layout, override)
    }

    fun responsesBase(chatBaseUrl: String, override: String = ""): String? {
        val custom = override.trim()
        if (custom.isNotEmpty()) return compatV1Root(custom) ?: custom.trim().trimEnd('/')
        return compatV1Root(chatBaseUrl)
    }
}
