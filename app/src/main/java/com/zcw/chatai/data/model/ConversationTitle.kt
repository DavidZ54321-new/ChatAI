package com.zcw.chatai.data.model

object ConversationTitle {

    private const val MAX_LENGTH = 24
    private const val FALLBACK = "新对话"

    private val WHITESPACE = Regex("(?U)\\s+")

    fun fromFirstMessage(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val collapsed = line.replace(WHITESPACE, " ").trim()
        if (collapsed.isEmpty()) return FALLBACK
        return if (collapsed.length > MAX_LENGTH) {
            collapsed.take(MAX_LENGTH) + "…"
        } else {
            collapsed
        }
    }
}
