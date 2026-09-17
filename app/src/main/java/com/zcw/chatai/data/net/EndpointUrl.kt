package com.zcw.chatai.data.net

object EndpointUrl {
    private const val COMPLETIONS_PATH = "/chat/completions"
    private const val MODELS_PATH = "/models"
    private const val VERSION_PATH = "/v1"

    fun chatCompletions(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return null
        val base = trimmed.trimEnd('/')
        if (base.endsWith(COMPLETIONS_PATH)) return base
        val versioned = if (base.endsWith(VERSION_PATH, ignoreCase = true)) base else base + VERSION_PATH
        return versioned + COMPLETIONS_PATH
    }

    /** 标准 OpenAI 端点，用来「拉取模型列表 / 测试连接」。 */
    fun models(baseUrl: String): String? {
        val completions = chatCompletions(baseUrl) ?: return null
        return completions.removeSuffix(COMPLETIONS_PATH) + MODELS_PATH
    }
}
