package com.zcw.chatai.data.doc

import java.io.ByteArrayInputStream
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/**
 * 流式 XML 基础设施（kxml2，直接实例化实现类，不走 JAXP 查找）。
 *
 * 真机教训：Android 的 JAXP 无视系统属性与 service 文件、永远返回自带
 * Expat，而 Expat 不支持 xmlbeans 必设的 declaration-handler——POI 整栈
 * 因此在真机上无解。kxml2 与 Android 自带 Xml 系出同源但纯 Java 实现，
 * JVM 单测与真机行为一致。
 */
internal object OoxmlXml {

    fun parser(bytes: ByteArray): XmlPullParser =
        KXmlParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(ByteArrayInputStream(bytes), null)
        }

    /**
     * 块流文本抽取：docx 正文与 pptx 幻灯片共用（两处标签局部名相同）。
     * - `p` 结束 → 一行；`t` → 文本；`tab` → 制表；`br`/`cr` → 换行；
     * - 表格行拼成一行（单元格制表分隔，行末空格裁掉），表后空行分隔。
     * 只认局部名：transitional 与 strict 命名空间都能处理。
     */
    fun flowText(parser: XmlPullParser): String {
        val out = StringBuilder()
        val line = StringBuilder()
        val rowCells = ArrayList<String>()
        var tableDepth = 0
        fun emit(text: String) {
            if (text.isEmpty()) return
            if (out.isNotEmpty()) out.append('\n')
            out.append(text)
        }
        fun flushLine() {
            val text = line.toString().trim()
            line.clear()
            if (text.isEmpty()) return
            if (tableDepth > 0) {
                rowCells += text
            } else {
                emit(text)
            }
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "t" -> line.append(parser.nextText())
                    "tab" -> line.append('\t')
                    "br", "cr" -> if (tableDepth > 0) line.append(' ') else flushLine()
                    "tbl" -> tableDepth++
                    else -> Unit
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "p" -> flushLine()
                    "tr" -> {
                        flushLine()
                        if (tableDepth > 0 && rowCells.isNotEmpty()) {
                            emit(rowCells.joinToString("\t").trimEnd())
                            rowCells.clear()
                        }
                    }
                    "tbl" -> {
                        flushLine()
                        if (tableDepth > 0) {
                            tableDepth--
                            if (rowCells.isNotEmpty()) {
                                emit(rowCells.joinToString("\t").trimEnd())
                                rowCells.clear()
                            }
                            out.append('\n')
                        }
                    }
                    else -> Unit
                }
                else -> Unit
            }
            event = parser.next()
        }
        flushLine()
        return collapseBlankLines(out.toString()).trim()
    }
}

/** 跳过当前元素整个子树（含嵌套）。调用时必须停在 START_TAG 上。 */
internal fun XmlPullParser.skipSubtree() {
    if (eventType != XmlPullParser.START_TAG) return
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
            else -> Unit
        }
    }
}

/** 收集当前元素内的全部文本直至其结束标签（含嵌套子元素里的文本）。 */
internal fun XmlPullParser.innerText(): String {
    if (eventType != XmlPullParser.START_TAG) return ""
    val builder = StringBuilder()
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.TEXT -> builder.append(text)
            else -> Unit
        }
    }
    return builder.toString()
}
