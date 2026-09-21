package com.zcw.chatai.data.media

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportChunkAssemblerTest {

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun chunksReassembleToOriginalBytes() {
        val original = ByteArray(1000) { (it * 7).toByte() }
        val encoded = base64(original)
        val assembler = ExportChunkAssembler()
        var index = 0
        while (index < encoded.length) {
            val end = minOf(index + 7, encoded.length)
            assertTrue(assembler.append(encoded.substring(index, end)))
            index = end
        }
        assertArrayEquals(original, assembler.decode())
    }

    @Test
    fun whitespaceInChunksIsIgnored() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = base64(original)
        val assembler = ExportChunkAssembler()
        assertTrue(assembler.append(encoded.substring(0, 4) + "\n"))
        assertTrue(assembler.append(" " + encoded.substring(4)))
        assertArrayEquals(original, assembler.decode())
    }

    @Test
    fun emptyAssemblerDecodesToNull() {
        assertNull(ExportChunkAssembler().decode())
    }

    @Test
    fun invalidBase64DecodesToNull() {
        val assembler = ExportChunkAssembler()
        assembler.append("!!!not base64!!!")
        assertNull(assembler.decode())
    }

    @Test
    fun appendingBeyondLimitIsRejected() {
        val assembler = ExportChunkAssembler(maxBase64Chars = 8)
        assertTrue(assembler.append("12345678"))
        assertFalse(assembler.append("9"))
        assertTrue(assembler.overflowed)
    }

    @Test
    fun resetClearsAccumulatedChunks() {
        val assembler = ExportChunkAssembler()
        assembler.append("AAAA")
        assembler.reset()
        assertEquals(0, assembler.length)
        assertNull(assembler.decode())
    }
}
