package com.zcw.chatai.data.net

object EndpointUrl {
    private const val COMPLETIONS_PATH = "/chat/completions"
    private const val MODELS_PATH = "/models"
    private const val RESPONSES_PATH = "/responses"
    private const val VERSION_PATH = "/v1"
    private const val ANTHROPIC_PATH = "/anthropic"
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

    /** DeepSeek 的 Anthropic 兼容面：`{origin}/anthropic/v1/messages`。 */
    fun anthropicMessages(baseUrl: String): String? {
        val base = baseUrl.trim().trimEnd('/').removeSuffix(COMPLETIONS_PATH)
        if (base.isEmpty()) return null
        var root = base
        if (root.endsWith(VERSION_PATH, ignoreCase = true)) root = root.dropLast(VERSION_PATH.length)
        if (root.endsWith(ANTHROPIC_PATH, ignoreCase = true)) root = root.dropLast(ANTHROPIC_PATH.length)
        root = root.trimEnd('/')
        if (root.isEmpty()) return null
        return root + ANTHROPIC_PATH + VERSION_PATH + MESSAGES_PATH
    }
}
