package com.zcw.chatai.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class LikePatternTest {

    @Test
    fun plainTextIsUnchanged() {
        assertEquals("hello 世界", LikePattern.escape("hello 世界"))
    }

    @Test
    fun wildcardsAreEscaped() {
        assertEquals("100\\%", LikePattern.escape("100%"))
        assertEquals("a\\_b", LikePattern.escape("a_b"))
    }

    @Test
    fun backslashIsEscapedFirst() {
        assertEquals("\\\\", LikePattern.escape("\\"))
        // 输入 `\%` → `\\` + `\%` = 三个反斜杠 + `%`。
        assertEquals("\\\\\\%", LikePattern.escape("\\%"))
    }

    @Test
    fun containsWrapsWithWildcards() {
        assertEquals("%cat%", LikePattern.contains("cat"))
        assertEquals("%100\\%%", LikePattern.contains("100%"))
    }

    @Test
    fun emptyQueryMatchesEverything() {
        assertEquals("%%", LikePattern.contains(""))
    }
}
