package com.zcw.chatai.data.net

import com.zcw.chatai.data.media.ImageCodec
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * 一段要在写出请求时流式编进 JSON 的本地文件。
 * [placeholder] 事先写进 JSON 模板，必须按模板中的出现顺序排列。
 */
data class InlinePart(
    val placeholder: String,
    val file: File,
    val mime: String,
)

/**
 * 把内联视频/音频的 base64 留到写 socket 时再生，避免整段 data URL 进 UTF-16 字符数组后再翻倍。
 * 占位符只含字母、数字和连字符，JSON 字符串不会把它转义。
 */
object InlineMedia {

    fun placeholder(id: String): String = "chataiinline-$id"

    fun dataUrlPrefix(mime: String): String = "data:$mime;base64,"

    /** 模板换成真实 data URL 之后的 UTF-8 字节数。占位符缺失或文件不可读时抛 [IOException]。 */
    fun utf8Length(template: String, parts: List<InlinePart>): Long {
        requireParts(template, parts)
        var total = template.toByteArray(Charsets.UTF_8).size.toLong()
        for (part in parts) {
            total -= part.placeholder.toByteArray(Charsets.UTF_8).size
            total += dataUrlPrefix(part.mime).toByteArray(Charsets.UTF_8).size
            total += ImageCodec.base64Length(part.file.length())
        }
        return total
    }

    /** 按占位符顺序把模板写成 UTF-8，文件内容以 base64 流式插入。 */
    fun write(template: String, parts: List<InlinePart>, out: OutputStream) {
        val starts = requireParts(template, parts)
        var cursor = 0
        parts.forEachIndexed { index, part ->
            val at = starts[index]
            writeUtf8(out, template.substring(cursor, at))
            writeUtf8(out, dataUrlPrefix(part.mime))
            writeBase64(part.file, out)
            cursor = at + part.placeholder.length
        }
        writeUtf8(out, template.substring(cursor))
        out.flush()
    }

    private fun requireParts(template: String, parts: List<InlinePart>): List<Int> {
        for (part in parts) {
            if (!part.file.isFile || part.file.length() <= 0L) {
                throw IOException("媒体文件已丢失，请重新发送")
            }
        }
        var from = 0
        return parts.map { part ->
            val at = template.indexOf(part.placeholder, from)
            if (at < 0) throw IOException("请求组装失败")
            from = at + part.placeholder.length
            at
        }
    }

    private fun writeUtf8(out: OutputStream, text: String) {
        if (text.isEmpty()) return
        out.write(text.toByteArray(Charsets.UTF_8))
    }

    private fun writeBase64(file: File, out: OutputStream) {
        // Encoder.wrap 的 close 会关掉底层流；这里只刷出 padding，不动 Okio 的 sink。
        val encoded = Base64.getEncoder().wrap(object : FilterOutputStream(out) {
            override fun close() {
                flush()
            }
        })
        encoded.use { base64 ->
            file.inputStream().use { input -> input.copyTo(base64) }
        }
    }
}

/** JSON 请求体：模板里的占位符在 [writeTo] 时换成文件的 data URL。 */
class InlineMediaBody(
    private val template: String,
    private val parts: List<InlinePart>,
) : RequestBody() {

    private val byteLength: Long = InlineMedia.utf8Length(template, parts)

    override fun contentType(): MediaType = JSON

    override fun contentLength(): Long = byteLength

    override fun writeTo(sink: BufferedSink) {
        InlineMedia.write(template, parts, sink.outputStream())
        sink.flush()
    }

    private companion object {
        val JSON: MediaType = "application/json; charset=utf-8".toMediaType()
    }
}
