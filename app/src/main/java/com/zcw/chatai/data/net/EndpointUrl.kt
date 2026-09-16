package com.zcw.chatai.data.net

object EndpointUrl {
    private const val COMPLETIONS_PATH = "/chat/completions"
    private const val VERSION_PATH = "/v1"

    fun chatCompletions(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return null
        val base = trimmed.trimEnd('/')
        if (base.endsWith(COMPLETIONS_PATH)) return base
        val versioned = if (base.endsWith(VERSION_PATH, ignoreCase = true)) base else base + VERSION_PATH
        return versioned + COMPLETIONS_PATH
    }
}
