package com.zcw.chatai.ui.md

internal const val PLACEHOLDER_OPEN = '\uE000'
internal const val PLACEHOLDER_CLOSE = '\uE001'

sealed interface InlinePiece {
    data class Text(val text: String) : InlinePiece
    data class Formula(val index: Int) : InlinePiece
}

data class PreparedMarkdown(val text: String, val formulas: List<String>)

fun prepareInlineMath(markdown: String): PreparedMarkdown {
    val formulas = mutableListOf<String>()
    val out = StringBuilder(markdown.length)
    var inFence = false
    var i = 0
    while (i < markdown.length) {
        val atLineStart = i == 0 || markdown[i - 1] == '\n'
        if (atLineStart && isFenceStart(markdown, i)) {
            val lineEnd = markdown.indexOf('\n', i)
            val stop = if (lineEnd < 0) markdown.length else lineEnd + 1
            out.append(markdown, i, stop)
            i = stop
            inFence = !inFence
            continue
        }
        val c = markdown[i]
        if (inFence) {
            out.append(c)
            i++
            continue
        }
        if (c == '`') {
            val close = markdown.indexOf('`', i + 1)
            val stop = if (close < 0) markdown.length else close + 1
            out.append(markdown, i, stop)
            i = stop
            continue
        }
        if (c == '$' && markdown.startsWith("$$", i)) {
            val close = markdown.indexOf("$$", i + 2)
            val stop = if (close < 0) markdown.length else close + 2
            out.append(markdown, i, stop)
            i = stop
            continue
        }
        if (c == '$') {
            val end = findInlineMathEnd(markdown, i)
            if (end > 0) {
                val latex = markdown.substring(i + 1, end).trim()
                if (latex.isNotEmpty()) {
                    formulas += latex
                    out.append(PLACEHOLDER_OPEN).append(formulas.lastIndex).append(PLACEHOLDER_CLOSE)
                    i = end + 1
                    continue
                }
            }
        }
        // 模型常用的 LaTeX 原生行内写法 \( … \)（与 $ … $ 等价）
        if (c == '\\' && markdown.getOrNull(i + 1) == '(') {
            val close = markdown.indexOf("\\)", i + 2)
            if (close > 0) {
                val latex = markdown.substring(i + 2, close).trim()
                if (latex.isNotEmpty()) {
                    formulas += latex
                    out.append(PLACEHOLDER_OPEN).append(formulas.lastIndex).append(PLACEHOLDER_CLOSE)
                    i = close + 2
                    continue
                }
            }
        }
        out.append(c)
        i++
    }
    return PreparedMarkdown(out.toString(), formulas)
}

fun splitPlaceholders(text: String): List<InlinePiece> {
    val pieces = mutableListOf<InlinePiece>()
    var index = 0
    var last = 0
    while (index < text.length) {
        if (text[index] == PLACEHOLDER_OPEN) {
            val close = text.indexOf(PLACEHOLDER_CLOSE, index + 1)
            if (close > 0) {
                val formulaIndex = text.substring(index + 1, close).toIntOrNull()
                if (formulaIndex != null) {
                    if (index > last) pieces += InlinePiece.Text(text.substring(last, index))
                    pieces += InlinePiece.Formula(formulaIndex)
                    index = close + 1
                    last = index
                    continue
                }
            }
        }
        index++
    }
    if (last < text.length) pieces += InlinePiece.Text(text.substring(last))
    return pieces
}

private fun findInlineMathEnd(text: String, start: Int): Int {
    if (start + 1 >= text.length) return -1
    if (text[start + 1].isWhitespace()) return -1
    var i = start + 1
    while (i < text.length) {
        val c = text[i]
        if (c == '\n') return -1
        if (c == '\\') {
            i += 2
            continue
        }
        if (c == '$' && !text[i - 1].isWhitespace()) {
            if (text.startsWith("$$", i)) {
                i += 2
                continue
            }
            return if (text.getOrNull(i + 1)?.isDigit() == true) -1 else i
        }
        i++
    }
    return -1
}

private fun isFenceStart(text: String, lineStart: Int): Boolean {
    var cursor = lineStart
    while (cursor < text.length && (text[cursor] == ' ' || text[cursor] == '\t')) cursor++
    if (cursor >= text.length) return false
    val fence = text[cursor]
    if (fence != '`' && fence != '~') return false
    var end = cursor
    while (end < text.length && text[end] == fence) end++
    return end - cursor >= 3
}
