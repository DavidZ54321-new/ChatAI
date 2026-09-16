package com.zcw.chatai.ui.md

sealed interface MdSegment {
    data class Markdown(val text: String) : MdSegment
    data class BlockMath(val latex: String) : MdSegment
}

object LatexSplitter {

    private const val FENCE_MIN_LENGTH = 3
    private const val MATH_DELIMITER = "$$"

    fun split(text: String): List<MdSegment> {
        if (text.isEmpty()) return emptyList()

        val segments = mutableListOf<MdSegment>()
        val markdown = StringBuilder()
        var index = 0
        var fenceChar: Char? = null

        fun flushMarkdown() {
            if (markdown.isNotEmpty()) {
                segments += MdSegment.Markdown(markdown.toString())
                markdown.clear()
            }
        }

        while (index < text.length) {
            val atLineStart = index == 0 || text[index - 1] == '\n'
            if (atLineStart) {
                val fence = lineFence(text, index)
                if (fence != null) {
                    if (fenceChar == null) {
                        fenceChar = fence.first
                    } else if (fenceChar == fence.first) {
                        fenceChar = null
                    }
                    markdown.append(text, index, index + fence.second)
                    index += fence.second
                    continue
                }
            }

            if (fenceChar == null && text.startsWith(MATH_DELIMITER, index)) {
                val contentStart = index + MATH_DELIMITER.length
                val close = text.indexOf(MATH_DELIMITER, contentStart)
                val inner = if (close < 0) {
                    text.substring(contentStart)
                } else {
                    text.substring(contentStart, close)
                }
                val latex = inner.trim()
                if (latex.isNotEmpty()) {
                    flushMarkdown()
                    segments += MdSegment.BlockMath(latex)
                }
                index = if (close < 0) text.length else close + MATH_DELIMITER.length
                continue
            }

            markdown.append(text[index])
            index++
        }
        flushMarkdown()

        if (segments.none { it is MdSegment.BlockMath }) {
            return listOf(MdSegment.Markdown(text))
        }
        return segments
    }

    private fun lineFence(text: String, start: Int): Pair<Char, Int>? {
        var cursor = start
        while (cursor < text.length && (text[cursor] == ' ' || text[cursor] == '\t')) {
            cursor++
        }
        if (cursor >= text.length) return null
        val char = text[cursor]
        if (char != '`' && char != '~') return null
        var end = cursor
        while (end < text.length && text[end] == char) {
            end++
        }
        if (end - cursor < FENCE_MIN_LENGTH) return null
        return char to (end - start)
    }
}
