package com.zcw.chatai.data.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** HTML → 纯文本（Jsoup，纯函数，JVM 可测）。块级元素之间保留换行。 */
object HtmlToText {

    private val DROP = "script,style,noscript,svg,iframe,form,nav,footer,header"
    private val BLOCK = setOf(
        "p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "ul", "ol", "br", "tr", "table", "blockquote", "pre",
    )

    fun toText(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select(DROP).remove()
        val builder = StringBuilder()
        doc.body()?.let { render(it, builder) }
        return collapseWhitespace(builder.toString())
    }

    private fun render(node: Node, builder: StringBuilder) {
        when (node) {
            is TextNode -> builder.append(node.text())
            is Element -> {
                val block = node.tagName() in BLOCK
                if (block) builder.append('\n')
                node.childNodes().forEach { render(it, builder) }
                if (block) builder.append('\n')
            }
        }
    }

    private fun collapseWhitespace(text: String): String {
        val builder = StringBuilder(text.length)
        var pendingSpace = false
        var newlines = 0
        text.forEach { ch ->
            when {
                ch == '\n' -> {
                    newlines++
                    pendingSpace = false
                    if (newlines <= 2) builder.append('\n')
                }
                ch == ' ' || ch == '\t' || ch == '\r' -> pendingSpace = true
                else -> {
                    if (pendingSpace && builder.isNotEmpty() && builder.last() != '\n') builder.append(' ')
                    pendingSpace = false
                    newlines = 0
                    builder.append(ch)
                }
            }
        }
        return builder.toString().trim()
    }
}
