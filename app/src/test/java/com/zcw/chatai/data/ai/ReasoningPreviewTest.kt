package com.zcw.chatai.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningPreviewTest {

    @Test
    fun blankReasoningHasNoPreview() {
        assertNull(collapseReasoningWhitespace(""))
        assertNull(collapseReasoningWhitespace("   \n\n \t "))
    }

    @Test
    fun skipsLeadingBlankLinesAndCollapsesNewlines() {
        assertEquals("先看用户给的图。 第二段", collapseReasoningWhitespace("\n\n  先看用户给的图。  \n第二段"))
    }

    @Test
    fun collapsesWhitespaceRunsIntoOneSpace() {
        assertEquals("a b c", collapseReasoningWhitespace("a \t  b\nc"))
    }

    /**
     * 回归：曾经只取「第一段非空行」，遇到逐字换行的推理（每个字后面一个 \n）
     * 预览就只剩一个字。
     */
    @Test
    fun perCharacterNewlinesStillGiveUsefulPreview() {
        assertEquals("用 户 发 来 多 张 图", collapseReasoningWhitespace("用\n户\n发\n来\n多\n张\n图"))
    }

    @Test
    fun shortTextIsReturnedAsIs() {
        assertEquals("想一下", collapseReasoningWhitespace("想一下"))
    }

    @Test
    fun collapseDoesNotTruncate() {
        val long = "甲".repeat(100)
        assertEquals(long, collapseReasoningWhitespace(long))
    }

    @Test
    fun collapseStopsAtMaxCharsWithoutScanningTheRest() {
        val collapsed = collapseReasoningWhitespace("甲".repeat(100), maxChars = 4)
        assertEquals("甲".repeat(4), collapsed)
        // 换行折成空格时，空格后面放不下一个字就停，不留悬空空格。
        assertEquals("甲 乙", collapseReasoningWhitespace("甲\n乙\n丙\n丁", maxChars = 4))
        assertTrue(collapseReasoningWhitespace("甲\n乙\n丙", maxChars = 0) == null)
    }

    @Test
    fun tickerTakesTailWhileFollowingAndHeadWhenIdle() {
        assertEquals("cdef", reasoningTickerText("abcdef", followEnd = true, maxChars = 4))
        assertEquals("abcd", reasoningTickerText("abcdef", followEnd = false, maxChars = 4))
        assertEquals("短", reasoningTickerText("短", followEnd = true, maxChars = 4))
    }
}
