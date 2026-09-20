package com.zcw.chatai.data.doc

import org.xmlpull.v1.XmlPullParser

/**
 * pptx 按幻灯片抽取文本（手写 XmlPullParser）。演讲者备注不收；
 * 幻灯片顺序按 presentation.xml，目标按其 rels 解析（不假设 slide1..N）。
 */
internal object PptxDocumentParser {

    fun parse(parts: Map<String, ByteArray>): ParsedDocument {
        val presentation = parts["ppt/presentation.xml"]
            ?: throw DocumentException("这个演示文稿缺少目录，文件可能已损坏")
        val rels = parts["ppt/_rels/presentation.xml.rels"] ?: ByteArray(0)
        val slideTargets = slideTargets(presentation, rels)
        if (slideTargets.isEmpty()) throw DocumentException("这个演示文稿里没有幻灯片")
        val builder = StringBuilder()
        slideTargets.forEachIndexed { index, target ->
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append("【第 ${index + 1} 页，共 ${slideTargets.size} 页】\n")
            val body = parts[target]?.let { OoxmlXml.flowText(OoxmlXml.parser(it)) }.orEmpty()
            builder.append(body.ifBlank { "（本页无文字）" })
        }
        val raw = builder.toString().trim()
        return ParsedDocument(text = raw, meta = "共 ${slideTargets.size} 页")
    }

    /** 纯函数：presentation.xml 的 sldId 顺序 + rels → 幻灯片部件路径。 */
    internal fun slideTargets(presentationXml: ByteArray, relsXml: ByteArray): List<String> {
        val ids = ArrayList<String>()
        val idParser = OoxmlXml.parser(presentationXml)
        var event = idParser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && idParser.name == "sldId") {
                idParser.getAttributeValue(RELS_NAMESPACE, "id")?.let { ids += it }
            }
            event = idParser.next()
        }
        if (ids.isEmpty()) return emptyList()
        val targets = relationshipTargets(relsXml)
        return ids.mapNotNull { targets[it]?.let { resolve("ppt", it) } }
    }

    internal const val RELS_NAMESPACE =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** 纯函数：rels XML → Id → Target。 */
    internal fun relationshipTargets(relsXml: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val relParser = OoxmlXml.parser(relsXml)
        var event = relParser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && relParser.name == "Relationship") {
                val id = relParser.getAttributeValue(null, "Id")
                val target = relParser.getAttributeValue(null, "Target")
                if (!id.isNullOrEmpty() && !target.isNullOrEmpty()) result[id] = target
            }
            event = relParser.next()
        }
        return result
    }

    /** 相对目标按部件所在目录解析（"ppt" + "slides/slide1.xml"）。 */
    internal fun resolve(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        if (target.contains("://")) return target
        val cleaned = target.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
        return "$baseDir/$cleaned"
    }
}
