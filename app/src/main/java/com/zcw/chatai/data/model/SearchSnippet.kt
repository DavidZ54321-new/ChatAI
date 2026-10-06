package com.zcw.chatai.data.model

import kotlin.math.roundToInt

/**
 * 搜索列表第二行要画的那一小段。
 *
 * 数据库只交回命中点附近的原始小窗（见 [PAD] / [spanFor]）。这里折叠空白、
 * 标出第一次出现的位置，并在小窗被截断时补省略号。不用正则。
 */
data class MatchedSnippet(
    val text: String,
    val matchStart: Int,
    val matchEnd: Int,
)

object SearchSnippet {

    /** 命中点左右各留的字符数。和查询参数 `:pad` 是同一个数。 */
    const val PAD = 48

    /** 小窗最长字符数。查询本身更长时放宽到能装下查询。 */
    const val MAX_SPAN = 160

    private const val ELLIPSIS = "…"

    fun spanFor(query: String): Int {
        if (query.isEmpty()) return MAX_SPAN
        return (PAD * 2 + query.length).coerceAtMost(MAX_SPAN.coerceAtLeast(query.length))
    }

    /** 小窗起点大于 1，说明正文在命中点之前被裁掉了。 */
    fun isLeadTrimmed(snippetStart: Int): Boolean = snippetStart > 1

    /**
     * 小窗终点还没到正文末尾。起点和全文长度都是 SQLite 的字符下标（从 1 计），
     * [span] 是 `substr` 的长度上限。
     */
    fun isTailTrimmed(snippetStart: Int, fullLength: Int, span: Int): Boolean =
        snippetStart.toLong() + span - 1 < fullLength

    /**
     * 把原始小窗收成一行可画的片段。
     * [leadTrimmed] / [tailTrimmed] 来自取窗时的真实下标，不靠窗口里的命中位置来猜。
     * 只标第一次命中。找不到返回 null，调用方退回最后一条摘要。
     */
    fun present(
        window: String,
        query: String,
        leadTrimmed: Boolean,
        tailTrimmed: Boolean,
    ): MatchedSnippet? {
        if (query.isEmpty()) return null
        if (findFirst(window, query) < 0) return null
        val collapsed = ConversationTitle.collapse(window)
        val start = findFirst(collapsed, query)
        if (start < 0) return null
        val prefix = if (leadTrimmed) ELLIPSIS else ""
        val suffix = if (tailTrimmed) ELLIPSIS else ""
        return MatchedSnippet(
            text = prefix + collapsed + suffix,
            matchStart = prefix.length + start,
            matchEnd = prefix.length + start + query.length,
        )
    }

    /**
     * 第一次出现的下标；没有则 -1。
     * 只把 ASCII 大写折成小写，和 SQLite `lower()` 的默认行为一致，中文原样比较。
     * 这种折叠不改变字符数，所以命中长度等于 [query] 的长度。
     */
    fun findFirst(text: String, query: String): Int {
        if (query.isEmpty() || query.length > text.length) return -1
        val limit = text.length - query.length
        var index = 0
        while (index <= limit) {
            if (matchesAt(text, index, query)) return index
            index++
        }
        return -1
    }

    /**
     * 把命中区间的中心挪到视口中心，再夹紧。
     * 片段短于一行、或命中贴在开头时不往右留空，返回 0。贴在末尾时停在末尾，不露出空白。
     */
    fun matchShiftPx(viewportPx: Int, textWidthPx: Int, matchLeftPx: Float, matchRightPx: Float): Int {
        if (viewportPx <= 0 || textWidthPx <= viewportPx) return 0
        val minShift = (viewportPx - textWidthPx).toFloat()
        val center = (matchLeftPx + matchRightPx) / 2f
        return (viewportPx / 2f - center).coerceIn(minShift, 0f).roundToInt()
    }

    private fun matchesAt(text: String, start: Int, query: String): Boolean {
        for (index in query.indices) {
            if (asciiLower(text[start + index]) != asciiLower(query[index])) return false
        }
        return true
    }

    private fun asciiLower(char: Char): Char = if (char in 'A'..'Z') char + 32 else char
}
