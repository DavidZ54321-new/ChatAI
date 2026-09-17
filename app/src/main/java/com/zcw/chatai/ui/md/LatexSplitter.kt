package com.zcw.chatai.ui.md

sealed interface MdSegment {
    data class Markdown(val text: String) : MdSegment
    data class BlockMath(val latex: String) : MdSegment
}

object LatexSplitter {

    private const val FENCE_MIN_LENGTH = 3
    private const val DOLLAR = "$$"
    private const val BRACKET_OPEN = "\\["
    private const val BRACKET_CLOSE = "\\]"

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

            if (fenceChar == null) {
                val block = blockMathAt(text, index)
                if (block != null) {
                    // 空公式（如 `$$ $$`）整体吞掉但不产出片段；若全文再无公式，
                    // 下面的兜底会把原文整段交回 Markdown。
                    if (block.latex.isNotEmpty()) {
                        flushMarkdown()
                        segments += MdSegment.BlockMath(block.latex)
                    }
                    index = block.nextIndex
                    continue
                }
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

    private data class Block(val latex: String, val nextIndex: Int)

    /**
     * 块级公式：`$$…$$`（常见于手写 Markdown）与 `\[…\]`（模型更爱用这种 LaTeX 原生写法，
     * 实测 DeepSeek 直接输出 `\[ … \]`，不识别的话用户会看到一堆反斜杠源码）。
     * 未闭合时按「到文本末尾」吞掉，流式渲染中途也不会闪烁。
     */
    private fun blockMathAt(text: String, index: Int): Block? {
        val close = when {
            text.startsWith(DOLLAR, index) -> DOLLAR
            text.startsWith(BRACKET_OPEN, index) -> BRACKET_CLOSE
            else -> return null
        }
        val open = if (close == DOLLAR) DOLLAR else BRACKET_OPEN
        val contentStart = index + open.length
        val closeIndex = text.indexOf(close, contentStart)
        val inner = if (closeIndex < 0) {
            text.substring(contentStart)
        } else {
            text.substring(contentStart, closeIndex)
        }
        val latex = inner.trim()
        val nextIndex = if (closeIndex < 0) text.length else closeIndex + close.length
        return Block(latex, nextIndex)
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
