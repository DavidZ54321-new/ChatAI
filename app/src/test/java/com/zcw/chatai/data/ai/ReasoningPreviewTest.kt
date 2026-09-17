package com.zcw.chatai.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningPreviewTest {

    @Test
    fun blankReasoningHasNoPreview() {
        assertNull(reasoningPreview(""))
        assertNull(reasoningPreview("   \n\n \t "))
    }

    @Test
    fun skipsLeadingBlankLinesAndCollapsesNewlines() {
        assertEquals("先看用户给的图。 第二段", reasoningPreview("\n\n  先看用户给的图。  \n第二段"))
    }

    @Test
    fun collapsesWhitespaceRunsIntoOneSpace() {
        assertEquals("a b c", reasoningPreview("a \t  b\nc"))
    }

    /**
     * 回归：曾经只取「第一段非空行」，遇到逐字换行的推理（每个字后面一个 \n）
     * 预览就只剩一个字。
     */
    @Test
    fun perCharacterNewlinesStillGiveUsefulPreview() {
        assertEquals("用 户 发 来 多 张 图", reasoningPreview("用\n户\n发\n来\n多\n张\n图"))
    }

    @Test
    fun truncatesLongLineWithEllipsis() {
        val preview = reasoningPreview("甲".repeat(100), maxChars = 10)
        assertEquals("甲".repeat(10) + "…", preview)
    }

    @Test
    fun truncationDoesNotLeaveTrailingSpace() {
        // 截断正好落在空格上：空格要被去掉，不能出现 "abc …"
        assertEquals("abc…", reasoningPreview("abc     defgh", maxChars = 4))
        // 截断落在词中间：保留词内空格
        assertEquals("abc de…", reasoningPreview("abc     defgh", maxChars = 6))
    }

    @Test
    fun shortTextIsReturnedAsIs() {
        assertEquals("想一下", reasoningPreview("想一下"))
    }
}
