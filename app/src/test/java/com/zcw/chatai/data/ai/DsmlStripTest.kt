package com.zcw.chatai.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DsmlStripTest {

    private val bar = '\uFF5C'

    private fun open() = "<$bar${bar}DSML$bar${bar}tool_calls>"

    private fun close() = "<$bar${bar}/DSML$bar${bar}tool_calls>"

    @Test
    fun removesPairedBlockBetweenText() {
        val input = "前 ${open()}{\"a\":1}${close()} 后"
        val result = DsmlStrip.strip(input)
        assertFalse(result.contains("DSML"))
        assertTrue(result.contains("前"))
        assertTrue(result.contains("后"))
    }

    @Test
    fun removesMultipleBlocks() {
        val input = "A ${open()}x${close()} B ${open()}y${close()} C"
        val result = DsmlStrip.strip(input)
        assertFalse(result.contains("DSML"))
        assertEquals("A  B  C", result)
    }

    @Test
    fun removesLoneMarkersWithoutPair() {
        val openOnly = "前 ${open()} 后"
        val closeOnly = "前 ${close()} 后"
        assertFalse(DsmlStrip.strip(openOnly).contains("DSML"))
        assertFalse(DsmlStrip.strip(closeOnly).contains("DSML"))
        assertTrue(DsmlStrip.strip(openOnly).contains("前"))
        assertTrue(DsmlStrip.strip(closeOnly).contains("后"))
    }

    @Test
    fun keepsTextUntouchedWhenNoMarkup() {
        assertEquals("普通正文，没有标记。", DsmlStrip.strip("普通正文，没有标记。"))
    }
}
