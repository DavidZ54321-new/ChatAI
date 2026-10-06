package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.Role
import kotlin.math.abs
import com.zcw.chatai.ui.md.ContentSegment
import com.zcw.chatai.ui.md.ImageRowSplitter
import com.zcw.chatai.ui.md.LatexSplitter
import com.zcw.chatai.ui.md.MdSegment
import com.zcw.chatai.ui.md.prepareInlineMath

/**
 * 会话内搜索的一处命中。
 *
 * [start] / [end] 是这一段**可高亮正文**里的偏移，不是原始消息字符串。
 * 用户气泡就是原文。助手正文先按图行、块级公式拆开，再经行内公式占位，
 * 偏移跟 [MessageMarkdown] 交给解析器的字符串一致。
 */
data class InChatHit(
    val messageId: String,
    val segment: Int,
    val start: Int,
    val end: Int,
)

/**
 * 当前命中在窗口里的垂直中心。
 * 前三个参数是这条命中的身份，用来丢掉已经划走的那一处还在报的旧坐标。
 */
typealias InChatCenterReport = (messageId: String, segment: Int, start: Int, centerY: Float) -> Unit

/** 代码块上要画的命中。区间是展示出来的代码字符串，不是围栏源码。 */
data class CodePaint(
    val ranges: List<IntRange>,
    val active: IntRange?,
)

/**
 * 会话内关键词。纯函数，不用 Regex（Android ICU 不认 Java 内联标志）。
 * 只扫用户气泡和助手回复的正文，不读思考、工具结果、附件。
 */
object InChatSearch {

    /**
     * 当前命中起点的定位点。每次命中换一个 id，避免复用上一处的布局节点，
     * 拿着旧坐标却报成新命中。
     */
    fun probeId(hit: InChatHit): String = "inchat-active:${hit.segment}:${hit.start}"

    fun find(messages: List<ChatMessageItem>, query: String): List<InChatHit> {
        if (query.isBlank()) return emptyList()
        val hits = mutableListOf<InChatHit>()
        for (message in messages) {
            when (message.role) {
                Role.USER -> addHits(hits, message.id, 0, message.content, query)
                Role.ASSISTANT -> {
                    val pieces = textPieces(message.content)
                    for (index in pieces.indices) {
                        addHits(hits, message.id, index, pieces[index], query)
                    }
                }
                else -> Unit
            }
        }
        return hits
    }

    /**
     * 助手正文里、按阅读顺序、真正画成文字的那些段。
     * 图行和块级公式没有可高亮的正文，不占段号。
     * 段内偏移按行内公式替换之后的字符串算，和 markdown 解析器看到的是同一份。
     */
    fun textPieces(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val pieces = mutableListOf<String>()
        for (segment in ImageRowSplitter.split(content)) {
            if (segment !is ContentSegment.Markdown) continue
            for (sub in LatexSplitter.split(segment.text)) {
                if (sub is MdSegment.Markdown) {
                    pieces += prepareInlineMath(sub.text).text
                }
            }
        }
        return pieces
    }

    fun findRanges(text: String, query: String): List<IntRange> {
        if (query.isBlank() || text.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var from = 0
        val width = query.length
        while (from <= text.length - width) {
            val at = text.indexOf(query, startIndex = from, ignoreCase = true)
            if (at < 0) break
            ranges += at until (at + width)
            from = at + width
        }
        return ranges
    }

    /**
     * 锚点还在就用它，否则退回上次的下标（夹在现有命中里）。
     * 没有锚点时选第一处。空列表返回 -1。
     */
    fun resolveIndex(
        hits: List<InChatHit>,
        messageId: String?,
        segment: Int,
        start: Int,
        fallbackIndex: Int,
    ): Int {
        if (hits.isEmpty()) return -1
        if (messageId == null) return 0
        val found = indexOf(hits, messageId, segment, start)
        if (found >= 0) return found
        return fallbackIndex.coerceIn(0, hits.lastIndex)
    }

    fun indexOf(hits: List<InChatHit>, messageId: String, segment: Int, start: Int): Int =
        hits.indexOfFirst {
            it.messageId == messageId && it.segment == segment && it.start == start
        }

    /** 上一个 / 下一个。到头就停，不循环。 */
    fun step(index: Int, size: Int, delta: Int): Int {
        if (size <= 0) return -1
        return (index + delta).coerceIn(0, size - 1)
    }

    /**
     * 把命中中心送到 [regionTop] 与 [regionBottom] 的中线，需要滚动的像素。
     * 正数是命中偏下，列表要往前滚。滚不滚得动由列表自己夹，这里不补空白。
     */
    fun centerDelta(matchCenterY: Float, regionTop: Float, regionBottom: Float): Float {
        val center = (regionTop + regionBottom) / 2f
        return matchCenterY - center
    }

    /**
     * 距离还大，这一拍却几乎没滚走，而且这个方向已经到头。
     * 暂时滚不动、但那个方向还能滚，不算到头。
     */
    fun scrollBlocked(delta: Float, consumed: Float, canScrollFurther: Boolean): Boolean =
        abs(delta) > 2f && abs(consumed) < 0.5f && !canScrollFurther

    /**
     * 代码块展示串上的高亮。
     * 展示串和源码切片一致时按偏移贴；[replaceIndent] 改过缩进时按命中先后配对。
     */
    fun paintCode(
        sourceStart: Int,
        sourceSlice: String,
        displayed: String,
        query: String,
        hits: List<InChatHit>,
        active: InChatHit?,
    ): CodePaint {
        if (query.isBlank() || displayed.isEmpty()) return CodePaint(emptyList(), null)
        val sourceEnd = sourceStart + sourceSlice.length
        val overlapping = hits.filter { it.start < sourceEnd && it.end > sourceStart }
        if (overlapping.isEmpty()) return CodePaint(emptyList(), null)
        if (sourceSlice == displayed) {
            val painted = overlapping.mapNotNull { hit ->
                val start = (hit.start - sourceStart).coerceAtLeast(0)
                val end = (hit.end - sourceStart).coerceAtMost(displayed.length)
                if (start < end) hit to (start until end) else null
            }
            return CodePaint(painted.map { it.second }, painted.firstOrNull { it.first == active }?.second)
        }
        val displayedRanges = findRanges(displayed, query)
        val ordinal = active?.let { overlapping.indexOf(it) } ?: -1
        return CodePaint(displayedRanges, displayedRanges.getOrNull(ordinal))
    }

    /** 这条消息所在的列表行。有分支横幅时第 0 行是横幅。找不到返回 null。 */
    fun messageLazyIndex(
        groups: List<MessageGroup>,
        messageId: String,
        hasBranchHeader: Boolean,
    ): Int? {
        val index = groups.indexOfFirst { group -> group.items.any { it.id == messageId } }
        if (index < 0) return null
        return index + if (hasBranchHeader) 1 else 0
    }

    private fun addHits(
        hits: MutableList<InChatHit>,
        messageId: String,
        segment: Int,
        text: String,
        query: String,
    ) {
        for (range in findRanges(text, query)) {
            hits += InChatHit(messageId, segment, range.first, range.last + 1)
        }
    }
}
