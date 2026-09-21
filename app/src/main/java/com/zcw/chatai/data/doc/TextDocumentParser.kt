package com.zcw.chatai.data.doc

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 纯文本解码：UTF-8 严格优先，失败退 GBK（中文用户从微信/QQ 收到的 txt 常是 GBK）。
 * 代码类文件必须原样保留，只做换行归一 + 去 BOM，不折叠空行。
 */
internal object TextDocumentParser {

    fun parse(fileBytes: ByteArray): ParsedDocument {
        val decoded = decodeStrictUtf8(fileBytes) ?: decodeGbK(fileBytes)
        val normalized = normalize(decoded)
        if (normalized.isBlank()) throw DocumentException("这个文本文件是空的")
        return ParsedDocument(text = normalized)
    }

    internal fun decodeStrictUtf8(bytes: ByteArray): String? {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (t: CharacterCodingException) {
            null
        }
    }

    internal fun decodeGbK(bytes: ByteArray): String = String(bytes, Charset.forName("GBK"))

    /** 纯函数：去 BOM + 换行归一（JVM 单测覆盖）。 */
    internal fun normalize(text: String): String {
        val withoutBom = if (text.startsWith("\uFEFF")) text.substring(1) else text
        val builder = StringBuilder(withoutBom.length)
        var index = 0
        while (index < withoutBom.length) {
            val ch = withoutBom[index]
            if (ch == '\r') {
                builder.append('\n')
                if (index + 1 < withoutBom.length && withoutBom[index + 1] == '\n') index++
            } else {
                builder.append(ch)
            }
            index++
        }
        return builder.toString().trim { it == '\n' || it == ' ' || it == '\t' }
    }
}
