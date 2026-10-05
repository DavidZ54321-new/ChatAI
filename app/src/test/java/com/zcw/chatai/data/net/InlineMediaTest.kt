package com.zcw.chatai.data.net

import com.zcw.chatai.data.media.ImageCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InlineMediaTest {

    @Rule
    @JvmField
    val folder = TemporaryFolder()

    @Test
    fun base64PaddingMatchesEncoderForEveryRemainder() {
        for (size in 1..5) {
            val bytes = ByteArray(size) { (it * 17 + size).toByte() }
            val file = folder.newFile("pad-$size.bin")
            file.writeBytes(bytes)
            val placeholder = InlineMedia.placeholder("pad$size")
            val template = """{"text":"中文","url":"$placeholder"}"""
            val written = write(template, listOf(InlinePart(placeholder, file, "video/mp4")))
            val expect = template.replace(
                placeholder,
                InlineMedia.dataUrlPrefix("video/mp4") + Base64.getEncoder().encodeToString(bytes),
            ).toByteArray(Charsets.UTF_8)
            assertArrayEquals(expect, written)
            assertEquals(expect.size.toLong(), InlineMedia.utf8Length(template, listOf(InlinePart(placeholder, file, "video/mp4"))))
            assertEquals(ImageCodec.base64Length(size.toLong()), Base64.getEncoder().encodeToString(bytes).length.toLong())
        }
    }

    @Test
    fun twoFilesAreSplicedInOrderAndTemplateStaysFreeOfPayload() {
        val first = folder.newFile("a.bin").also { it.writeBytes(ByteArray(4096) { 1 }) }
        val second = folder.newFile("b.bin").also { it.writeBytes(ByteArray(4097) { 2 }) }
        val tokenA = InlineMedia.placeholder("aaaa-bbbb")
        val tokenB = InlineMedia.placeholder("cccc-dddd")
        val template = """[$tokenA,$tokenB]"""
        assertFalse(template.contains("AAAA"))
        val written = write(template, listOf(
            InlinePart(tokenA, first, "video/mp4"),
            InlinePart(tokenB, second, "audio/mpeg"),
        ))
        val text = written.toString(Charsets.UTF_8)
        val baseA = Base64.getEncoder().encodeToString(first.readBytes())
        val baseB = Base64.getEncoder().encodeToString(second.readBytes())
        assertTrue(text.startsWith("[" + InlineMedia.dataUrlPrefix("video/mp4") + baseA))
        assertTrue(text.endsWith(InlineMedia.dataUrlPrefix("audio/mpeg") + baseB + "]"))
        assertEquals(written.size.toLong(), InlineMedia.utf8Length(template, listOf(
            InlinePart(tokenA, first, "video/mp4"),
            InlinePart(tokenB, second, "audio/mpeg"),
        )))
    }

    @Test
    fun missingFileFailsBeforeAnyByteIsWritten() {
        val gone = File(folder.root, "missing.mp4")
        val placeholder = InlineMedia.placeholder("gone")
        val out = ByteArrayOutputStream()
        try {
            InlineMedia.write("""{"url":"$placeholder"}""", listOf(InlinePart(placeholder, gone, "video/mp4")), out)
            fail("expected missing file")
        } catch (t: Exception) {
            assertEquals("媒体文件已丢失，请重新发送", t.message)
        }
        assertEquals(0, out.size())
    }

    @Test
    fun partsOutOfOrderFailBeforeAnyByteIsWritten() {
        val file = folder.newFile("order.bin").also { it.writeBytes(byteArrayOf(9, 8, 7)) }
        val first = InlineMedia.placeholder("first-id")
        val second = InlineMedia.placeholder("second-id")
        val template = "$first-$second"
        val out = ByteArrayOutputStream()
        try {
            InlineMedia.write(template, listOf(
                InlinePart(second, file, "video/mp4"),
                InlinePart(first, file, "video/mp4"),
            ), out)
            fail("expected assemble failure")
        } catch (t: Exception) {
            assertEquals("请求组装失败", t.message)
        }
        assertEquals(0, out.size())
    }

    @Test
    fun outOfMemoryErrorBecomesTheReadableSentence() {
        val oom = OutOfMemoryError(
            "Failed to allocate a 173325792 byte allocation with 50331648 free bytes and 124MB until OOM",
        )
        assertEquals(SEND_OUT_OF_MEMORY, oom.userFacingSendError("请求失败"))
        val wrapped = ChatApiException("网络异常：${oom.message}", oom)
        assertEquals(SEND_OUT_OF_MEMORY, wrapped.userFacingSendError("请求失败"))
        val disguised = IllegalStateException(oom.message)
        assertEquals(disguised.message, disguised.userFacingSendError("请求失败"))
        assertEquals("别的错误", IllegalStateException("别的错误").userFacingSendError("请求失败"))
        assertEquals("请求失败", IllegalStateException().userFacingSendError("请求失败"))
    }

    private fun write(template: String, parts: List<InlinePart>): ByteArray {
        val out = ByteArrayOutputStream()
        InlineMedia.write(template, parts, out)
        return out.toByteArray()
    }
}
