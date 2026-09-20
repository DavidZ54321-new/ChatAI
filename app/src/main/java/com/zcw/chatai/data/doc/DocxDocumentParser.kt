package com.zcw.chatai.data.doc

/**
 * docx 正文 + 表格抽取（手写 XmlPullParser，不过 POI）。
 * 页眉/页脚/脚注/批注不收；docx 是流式排版，没有页数。
 */
internal object DocxDocumentParser {

    fun parse(parts: Map<String, ByteArray>): ParsedDocument {
        val document = parts["word/document.xml"]
            ?: throw DocumentException("这个 Word 文档缺少正文，文件可能已损坏")
        val text = OoxmlXml.flowText(OoxmlXml.parser(document))
        if (text.isEmpty()) throw DocumentException("这个 Word 文档里没有可提取的文字")
        return ParsedDocument(text = text)
    }
}
