package com.zcw.chatai.data.ai

/**
 * Strips DeepSeek native tool-call markup that leaks into assistant text.
 * Pure string handling (no Regex, JVM-testable).
 *
 * The leaked block looks like an open marker (fullwidth vertical bars around
 * the letters DSML) followed by invoke/parameter tags, closed by a marker with
 * a slash before DSML. Any leftover unpaired open/close tag is removed too.
 */
object DsmlStrip {

    private const val FULLWIDTH_BAR = '\uFF5C'

    private val OPEN: String =
        "<" + FULLWIDTH_BAR + FULLWIDTH_BAR + "DSML" + FULLWIDTH_BAR + FULLWIDTH_BAR

    private val CLOSE: String =
        "<" + FULLWIDTH_BAR + FULLWIDTH_BAR + "/DSML" + FULLWIDTH_BAR + FULLWIDTH_BAR

    /** Returns [text] with every DSML block / stray tag removed and blank lines collapsed. */
    fun strip(text: String): String {
        // 快速通道：没有全角竖线就不可能含 DSML 标记，原样返回（流式每个增量都会调用，
        // 顺便避免把正常正文的连续空行也折叠掉）。
        if (text.indexOf(FULLWIDTH_BAR) < 0) return text
        var result = text
        while (true) {
            val start = result.indexOf(OPEN)
            if (start < 0) break
            val closeAt = result.indexOf(CLOSE, start)
            if (closeAt < 0) break
            val end = result.indexOf('>', closeAt)
            if (end < 0) break
            result = result.removeRange(start, end + 1)
        }
        var cleaned = removeUnpaired(result, OPEN)
        cleaned = removeUnpaired(cleaned, CLOSE)
        return collapseBlankLines(cleaned).trim()
    }

    /** Removes an unpaired marker: start position through the next '>'. */
    private fun removeUnpaired(text: String, marker: String): String {
        var result = text
        while (true) {
            val start = result.indexOf(marker)
            if (start < 0) break
            val end = result.indexOf('>', start)
            if (end < 0) break
            result = result.removeRange(start, end + 1)
        }
        return result
    }

    private fun collapseBlankLines(text: String): String {
        val builder = StringBuilder()
        var newlineCount = 0
        text.forEach { ch ->
            if (ch == '\n') {
                newlineCount++
                if (newlineCount <= 2) builder.append(ch)
            } else {
                newlineCount = 0
                builder.append(ch)
            }
        }
        return builder.toString()
    }
}
