package com.zcw.chatai.data.media

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GifEncoderTest {

    private fun solidFrame(width: Int, height: Int, argb: Int, delayMs: Int = 300): GifEncoder.Frame =
        GifEncoder.Frame(IntArray(width * height) { argb }, delayMs)

    /**
     * 真解码回归：结构错位的 GIF 我们自己造的字段断言看不出来，交给 JDK 的 GIF reader
     * （曾经把全局调色表写在 Netscape 扩展之后，ImageIO 直接报 "Unexpected block type"）。
     */
    @Test
    fun outputIsDecodableByStandardReader() {
        val bytes = GifEncoder.encode(
            16, 16,
            listOf(solidFrame(16, 16, 0xFFFF0000.toInt()), solidFrame(16, 16, 0xFF0000FF.toInt())),
        )!!
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertNotNull("标准 GIF 解码器读不出来", image)
        assertEquals(16, image.width)
        assertEquals(16, image.height)
    }

    @Test
    fun decodesAtCommonSizes() {
        for (size in listOf(8, 96, 240)) {
            for (count in 1..3) {
                val frames = (0 until count).map { i ->
                    solidFrame(
                        size, size,
                        (0xFF shl 24) or (i * 60 shl 16) or (i * 30 shl 8) or 0x40,
                    )
                }
                val bytes = GifEncoder.encode(size, size, frames)!!
                val image = ImageIO.read(ByteArrayInputStream(bytes))
                assertNotNull("${size}x$size ${count}帧 解码失败", image)
                assertEquals(size, image.width)
                assertEquals(size, image.height)
            }
        }
    }

    @Test
    fun encodesValidGif89aStructure() {
        val bytes = GifEncoder.encode(
            16, 16,
            listOf(solidFrame(16, 16, 0xFFFF0000.toInt()), solidFrame(16, 16, 0xFF0000FF.toInt())),
        )
        assertNotNull(bytes)
        bytes!!
        // Header。
        assertEquals("GIF89a", String(bytes.copyOfRange(0, 6), Charsets.US_ASCII))
        // 逻辑屏幕宽高（小端）。
        assertEquals(16, (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8))
        assertEquals(16, (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8))
        // 全局调色表标志位（packed 字节 bit7）。
        assertTrue((bytes[10].toInt() and 0x80) != 0)
        // Trailer。
        assertEquals(0x3B.toByte(), bytes.last())
        // Netscape 循环块。
        assertTrue(String(bytes, Charsets.US_ASCII).contains("NETSCAPE2.0"))
    }

    @Test
    fun paletteFollowsLogicalScreenDescriptor() {
        // 全局调色表必须在偏移 13（6 字节头 + 7 字节描述符）处紧接着出现。
        val bytes = GifEncoder.encode(8, 8, listOf(solidFrame(8, 8, 0xFFFF0000.toInt())))!!
        var hasRed = false
        for (i in 0 until 256) {
            val r = bytes[13 + i * 3].toInt() and 0xFF
            val g = bytes[13 + i * 3 + 1].toInt() and 0xFF
            val b = bytes[13 + i * 3 + 2].toInt() and 0xFF
            if (r > 200 && g < 60 && b < 60) {
                hasRed = true
                break
            }
        }
        assertTrue("纯红帧量化后全局调色表应含近红项", hasRed)
    }

    @Test
    fun rejectsInvalidInput() {
        assertNull(GifEncoder.encode(0, 8, listOf(solidFrame(8, 8, 0xFF000000.toInt()))))
        assertNull(GifEncoder.encode(8, 8, emptyList()))
        // 帧尺寸与声明不一致。
        assertNull(
            GifEncoder.encode(
                8, 8,
                listOf(GifEncoder.Frame(IntArray(4 * 4) { 0 }, 100)),
            ),
        )
    }

    @Test
    fun twoFramesAreLargerThanOne() {
        val one = GifEncoder.encode(16, 16, listOf(solidFrame(16, 16, 0xFFFF0000.toInt())))!!
        val two = GifEncoder.encode(
            16, 16,
            listOf(solidFrame(16, 16, 0xFFFF0000.toInt()), solidFrame(16, 16, 0xFF00FF00.toInt())),
        )!!
        // 第二帧自带局部调色表 + 图像数据，必然更大。
        assertTrue(two.size > one.size)
    }
}
