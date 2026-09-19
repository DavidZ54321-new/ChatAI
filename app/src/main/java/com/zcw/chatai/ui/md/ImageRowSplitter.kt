package com.zcw.chatai.ui.md

/** 正文里的一张 markdown 网图。 */
data class MarkdownImageRef(val alt: String?, val url: String)

sealed interface ContentSegment {
    data class Markdown(val text: String) : ContentSegment

    /** 独占一行的网图（单张或连续多张）：多张渲染成一行横向滑动，单张按视窗比例铺开。 */
    data class ImageRow(val images: List<MarkdownImageRef>) : ContentSegment
}

/**
 * 把「整行只有一张网图」的图片段落拆出来交给自有渲染（纯函数，JVM 可测）。
 *
 * - 单张也拆：mikepenz 的行内图占位在部分设备上不撑行高，会压住前后文字/相邻图
 *   （仓库实测三星 S23 上整段打堆），所以图片一律不再留给 markdown 行内渲染；
 * - 空行**不**打断连续图：模型常常每张图之间空一行（实测 Qwen 的图搜回答就是这样）；
 * - 标题/正文/列表等非空非图行会打断；
 * - 围栏代码块（``` / ~~~）与缩进代码块里的 `![...]()` 是示例文本，不拆。
 */
object ImageRowSplitter {

    fun split(content: String): List<ContentSegment> {
        if (content.isEmpty()) return emptyList()
        val lines = content.split('\n')
        val segments = mutableListOf<ContentSegment>()
        val text = StringBuilder()
        var index = 0
        var fence: String? = null

        fun flushText() {
            // 纯空白块（图行之间的空行残留）不产出片段；没有图行的场景走下面的原样返回。
            if (text.isNotBlank()) {
                segments += ContentSegment.Markdown(text.toString())
            }
            text.clear()
        }

        while (index < lines.size) {
            val line = lines[index]
            val fenceLine = fenceLine(line)
            val openFence = fence
            if (openFence == null) {
                // 围栏内的所有行都是代码，原样留给 markdown。
                if (fenceLine != null) fence = fenceLine.marker
            } else if (
                fenceLine != null &&
                fenceLine.marker[0] == openFence[0] &&
                fenceLine.marker.length >= openFence.length &&
                fenceLine.rest.isBlank()
            ) {
                fence = null
            }
            if (fence != null || openFence != null) {
                text.append(line).append('\n')
                index++
                continue
            }
            val first = imageOnlyLine(line)
            if (first != null) {
                val images = mutableListOf(first)
                var cursor = index + 1
                var lastImage = index
                while (cursor < lines.size) {
                    val next = imageOnlyLine(lines[cursor])
                    if (next != null) {
                        images += next
                        lastImage = cursor
                        cursor++
                    } else if (lines[cursor].isBlank()) {
                        cursor++
                    } else {
                        break
                    }
                }
                flushText()
                segments += ContentSegment.ImageRow(images)
                index = lastImage + 1
                continue
            }
            text.append(line).append('\n')
            index++
        }
        flushText()

        // 没有图行 → 原样返回，既有渲染路径与文本零变化。
        if (segments.none { it is ContentSegment.ImageRow }) {
            return listOf(ContentSegment.Markdown(content))
        }
        return segments
    }

    /** 围栏行：``` / ~~~（允许最多 3 空格缩进）；[rest] 是围栏串之后的内容（info string 或空）。 */
    private class FenceLine(val marker: String, val rest: String)

    private fun fenceLine(line: String): FenceLine? {
        val indent = line.indexOfFirst { it != ' ' }
        if (indent < 0 || indent > 3) return null
        val trimmed = line.substring(indent)
        val ch = trimmed[0]
        if (ch != '`' && ch != '~') return null
        val run = trimmed.takeWhile { it == ch }
        if (run.length < 3) return null
        return FenceLine(run, trimmed.substring(run.length))
    }

    /**
     * 判断「整行只有一张 http(s) 图片」：`![alt](url)`。
     * 缩进 ≥4 空格是缩进代码块，内容原样留给 markdown。
     * 不用正则以避开 ICU 引擎差异（仓库约定）。
     */
    private fun imageOnlyLine(line: String): MarkdownImageRef? {
        if (line.startsWith("    ") || line.startsWith("\t")) return null
        val trimmed = line.trim()
        if (!trimmed.startsWith("![")) return null
        if (!trimmed.endsWith(")")) return null
        val altEnd = trimmed.indexOf("](", 2)
        if (altEnd < 0) return null
        val alt = trimmed.substring(2, altEnd).ifBlank { null }
        val url = trimmed.substring(altEnd + 2, trimmed.length - 1).trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        if (url.any { it.isWhitespace() }) return null
        return MarkdownImageRef(alt = alt, url = url)
    }
}
