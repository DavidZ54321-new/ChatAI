package com.zcw.chatai.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningDurationTest {

    @Test
    fun unknownDurationHasNoNumber() {
        assertNull(formatReasoningDuration(null))
    }

    /** 不足 1 秒不给数字：宁可只显示「已深度思考」，也不要「已深度思考 0s」。 */
    @Test
    fun subSecondDurationsAreHidden() {
        assertNull(formatReasoningDuration(0L))
        assertNull(formatReasoningDuration(999L))
    }

    @Test
    fun secondsAreTruncated() {
        assertEquals("1s", formatReasoningDuration(1_000L))
        assertEquals("9s", formatReasoningDuration(9_400L))
        assertEquals("59s", formatReasoningDuration(59_999L))
    }

    @Test
    fun minutesAreSpelledOut() {
        assertEquals("1分", formatReasoningDuration(60_000L))
        assertEquals("1分12秒", formatReasoningDuration(72_400L))
        assertEquals("90分", formatReasoningDuration(90 * 60_000L))
    }
}
