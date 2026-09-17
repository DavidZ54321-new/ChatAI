package com.zcw.chatai.data.model

object ConversationTitle {

    private const val MAX_LENGTH = 24
    const val FALLBACK = "新对话"

    private const val PREVIEW_LENGTH = 80

    /**
     * 折叠所有空白（含全角空格 U+3000、不换行空格 U+00A0）为单个半角空格，并去掉首尾空白。
     *
     * **这里刻意不用正则**：Android 用的是 ICU 正则引擎，不支持 Java 专有的内联标志
     * （如 `(?U)`），一旦写进 `Regex(...)` 就会在**类初始化**时抛
     * `ExceptionInInitializerError`，且 JVM 单测发现不了（JVM 引擎认识该语法）。
     * `Char.isWhitespace()` 基于 `Character.isWhitespace || Character.isSpaceChar`，
     * 两个平台行为一致。
     */
    private fun collapse(text: String): String {
        val builder = StringBuilder(text.length)
        var pendingSpace = false
        for (char in text) {
            if (char.isWhitespace()) {
                if (builder.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    builder.append(' ')
                    pendingSpace = false
                }
                builder.append(char)
            }
        }
        return builder.toString()
    }

    /** 会话列表里的一行摘要：折叠空白、单行、截断。 */
    fun preview(text: String, maxLength: Int = PREVIEW_LENGTH): String {
        val collapsed = collapse(text)
        return if (collapsed.length > maxLength) collapsed.take(maxLength) + "…" else collapsed
    }

    fun fromFirstMessage(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val collapsed = collapse(line)
        if (collapsed.isEmpty()) return FALLBACK
        return if (collapsed.length > MAX_LENGTH) {
            collapsed.take(MAX_LENGTH) + "…"
        } else {
            collapsed
        }
    }
}
