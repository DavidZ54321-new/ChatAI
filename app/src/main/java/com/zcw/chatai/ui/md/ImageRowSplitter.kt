package com.zcw.chatai.ui.md

/** 正文里的一张 markdown 网图。 */
data class MarkdownImageRef(val alt: String?, val url: String)

sealed interface ContentSegment {
    data class Markdown(val text: String) : ContentSegment

    /** 连续紧挨（允许空行）的多张图片：渲染成一行、超出横向滑动，像块级公式那样。 */
    data class ImageRow(val images: List<MarkdownImageRef>) : ContentSegment
}

/**
 * 把「整行只有一张网图」的连续段落拆出来合成图行（纯函数，JVM 可测）。
 *
 * - 空行**不**打断行：模型常常每张图之间空一行（实测 Qwen 的图搜回答就是这样）；
 * - 标题/正文/列表等非空非图行会打断；
 * - 只有单张的段落保持原样交给 Markdown 渲染（不改变既有观感）。
 */
object ImageRowSplitter {

    fun split(content: String): List<ContentSegment> {
        if (content.isEmpty()) return emptyList()
        val lines = content.split('\n')
        val segments = mutableListOf<ContentSegment>()
        val text = StringBuilder()
        var index = 0

        fun flushText() {
            // 纯空白块（图行之间的空行残留）不产出片段；没有图行的场景走下面的原样返回。
            if (text.isNotBlank()) {
                segments += ContentSegment.Markdown(text.toString())
            }
            text.clear()
        }

        while (index < lines.size) {
            val first = imageOnlyLine(lines[index])
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
                if (images.size >= 2) {
                    flushText()
                    segments += ContentSegment.ImageRow(images)
                    index = lastImage + 1
                    continue
                }
            }
            text.append(lines[index]).append('\n')
            index++
        }
        flushText()

        // 没有可合并的图行 → 原样返回，既有渲染路径与文本零变化。
        if (segments.none { it is ContentSegment.ImageRow }) {
            return listOf(ContentSegment.Markdown(content))
        }
        return segments
    }

    /**
     * 判断「整行只有一张 http(s) 图片」：`![alt](url)`。
     * 不用正则以避开 ICU 引擎差异（仓库约定）。
     */
    private fun imageOnlyLine(line: String): MarkdownImageRef? {
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
