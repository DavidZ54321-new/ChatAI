package com.zcw.chatai.ui.md

/** 可预览的围栏语言（v1 只认这三种；未知语言一律留给 markdown 原样显示代码）。 */
enum class PreviewLanguage { MERMAID, SVG, HTML }

sealed interface PreviewSegment {
    data class Markdown(val text: String) : PreviewSegment

    /** 可预览的围栏块：[code] 是去掉围栏行后的图源码。 */
    data class Preview(val language: PreviewLanguage, val code: String) : PreviewSegment
}

/**
 * 把 ` ```mermaid / ```svg / ```html ` 围栏块拆出来交给预览渲染（纯函数，JVM 可测）。
 *
 * - 只有**闭合**的围栏才成块：流式中途的未闭合围栏留在 markdown 里，不提前挂渲染按钮；
 * - 空代码块不成块（没有可渲染的内容）；
 * - info string 只取首个 token、大小写不敏感（`Mermaid`、`html run` 都认）；
 * - 4 空格缩进是缩进代码块，不拆；
 * - 围栏字符 ``` / ~~~ 的开关配对规则与 [ImageRowSplitter] 一致。
 */
object PreviewBlockSplitter {

    private val LANGUAGES = mapOf(
        "mermaid" to PreviewLanguage.MERMAID,
        "svg" to PreviewLanguage.SVG,
        "html" to PreviewLanguage.HTML,
    )

    fun split(content: String): List<PreviewSegment> {
        if (content.isEmpty()) return emptyList()
        val lines = content.split('\n')
        val segments = mutableListOf<PreviewSegment>()
        val text = StringBuilder()

        fun flushText() {
            if (text.isNotEmpty()) {
                segments += PreviewSegment.Markdown(text.toString())
                text.clear()
            }
        }

        var index = 0
        while (index < lines.size) {
            val fence = fenceLine(lines[index])
            val language = fence?.let { LANGUAGES[firstToken(it.rest)] }
            if (fence == null || language == null) {
                // 非目标围栏行：可能是普通文本，也可能是别的语言的代码块——整块原样保留，
                // 不能只跳过 opening 一行（否则会把别人的代码块撕开）。
                if (fence != null) {
                    val end = findClosing(lines, index + 1, fence.marker)
                    val stop = if (end < 0) lines.size else end + 1
                    for (i in index until stop) text.append(lines[i]).append('\n')
                    index = stop
                } else {
                    text.append(lines[index]).append('\n')
                    index++
                }
                continue
            }
            val end = findClosing(lines, index + 1, fence.marker)
            if (end < 0) {
                // 未闭合（流式中途）：后面全部按 markdown 留着，不挂渲染按钮。
                for (i in index until lines.size) text.append(lines[i]).append('\n')
                break
            }
            val code = lines.subList(index + 1, end).joinToString("\n")
            if (code.isBlank()) {
                // 空块不值得给预览入口：围栏原样留在 markdown 里。
                for (i in index..end) text.append(lines[i]).append('\n')
            } else {
                flushText()
                segments += PreviewSegment.Preview(language, code)
            }
            index = end + 1
        }
        flushText()

        if (segments.none { it is PreviewSegment.Preview }) {
            return listOf(PreviewSegment.Markdown(content))
        }
        return segments
    }

    private class Fence(val marker: String, val rest: String)

    private fun fenceLine(line: String): Fence? {
        val indent = line.indexOfFirst { it != ' ' }
        if (indent < 0 || indent > 3) return null
        if (line.startsWith("    ") || line.startsWith("\t")) return null
        val trimmed = line.substring(indent)
        val ch = trimmed[0]
        if (ch != '`' && ch != '~') return null
        val run = trimmed.takeWhile { it == ch }
        if (run.length < 3) return null
        return Fence(run, trimmed.substring(run.length))
    }

    private fun findClosing(lines: List<String>, from: Int, marker: String): Int {
        var i = from
        while (i < lines.size) {
            val fence = fenceLine(lines[i])
            if (fence != null && fence.marker[0] == marker[0] &&
                fence.marker.length >= marker.length && fence.rest.isBlank()
            ) {
                return i
            }
            i++
        }
        return -1
    }

    private fun firstToken(info: String): String {
        val trimmed = info.trim()
        if (trimmed.isEmpty()) return ""
        val end = trimmed.indexOfFirst { it.isWhitespace() }.takeIf { it >= 0 } ?: trimmed.length
        return trimmed.substring(0, end).lowercase()
    }
}
