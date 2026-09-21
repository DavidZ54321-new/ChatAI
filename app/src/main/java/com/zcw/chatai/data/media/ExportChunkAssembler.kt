package com.zcw.chatai.data.media

import java.util.Base64

/**
 * 把 JS 分片回传的 base64 按序拼接、一次性解码（WebView bridge 单次传输有上限，PNG 必须切片）。
 * 纯逻辑，JVM 单测覆盖。
 */
class ExportChunkAssembler(
    private val maxBase64Chars: Int = DEFAULT_MAX_BASE64_CHARS,
) {

    private val builder = StringBuilder()

    /** 因超过 [maxBase64Chars] 丢弃过内容：调用方应放弃本次导出。 */
    var overflowed: Boolean = false
        private set

    val length: Int get() = builder.length

    /** 追加一片；超过上限时丢弃并返回 false（防 JS 失控打爆内存）。 */
    fun append(chunk: String): Boolean {
        if (builder.length + chunk.length > maxBase64Chars) {
            overflowed = true
            return false
        }
        builder.append(chunk)
        return true
    }

    fun reset() {
        builder.setLength(0)
        overflowed = false
    }

    /** 拼接结果解码；空内容或非法 base64 返回 null。空白字符（换行/空格）忽略。 */
    fun decode(): ByteArray? {
        if (overflowed) return null
        if (builder.isEmpty()) return null
        val compact = stripWhitespace(builder)
        if (compact.isEmpty()) return null
        return try {
            Base64.getDecoder().decode(compact)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun stripWhitespace(text: CharSequence): String {
        val out = StringBuilder(text.length)
        for (char in text) {
            if (!char.isWhitespace()) out.append(char)
        }
        return out.toString()
    }

    companion object {
        /** base64 字符上限 ~48MB（对应 ~36MB 二进制），兜住失控的 JS 循环。 */
        const val DEFAULT_MAX_BASE64_CHARS = 48 * 1024 * 1024
    }
}
