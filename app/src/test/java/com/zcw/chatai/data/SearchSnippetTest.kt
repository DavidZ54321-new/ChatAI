package com.zcw.chatai.data

import com.zcw.chatai.data.model.SearchSnippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSnippetTest {

    @Test
    fun highlightsOnlyTheFirstMatch() {
        val snippet = SearchSnippet.present("A老黄B老黄C", "老黄", leadTrimmed = false, tailTrimmed = false)!!
        assertEquals("A老黄B老黄C", snippet.text)
        assertEquals(1, snippet.matchStart)
        assertEquals(3, snippet.matchEnd)
        assertEquals("老黄", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
    }

    @Test
    fun collapsesWhitespaceAndStillHighlights() {
        val snippet = SearchSnippet.present("甲\n\n乙  老黄。", "老黄", leadTrimmed = false, tailTrimmed = false)!!
        assertEquals("甲 乙 老黄。", snippet.text)
        assertEquals(snippet.text.indexOf("老黄"), snippet.matchStart)
        assertEquals(snippet.matchStart + 2, snippet.matchEnd)
    }

    @Test
    fun matchAtStartHasNoEllipsis() {
        val snippet = SearchSnippet.present("老黄在开头" + "后".repeat(5), "老黄", leadTrimmed = false, tailTrimmed = false)!!
        assertFalse(snippet.text.startsWith("…"))
        assertFalse(snippet.text.endsWith("…"))
        assertEquals(0, snippet.matchStart)
    }

    @Test
    fun trimmedFlagsAddEllipsisWithoutGuessingFromTheIndex() {
        val window = "前".repeat(SearchSnippet.PAD) + "老黄"
        val kept = SearchSnippet.present(window, "老黄", leadTrimmed = false, tailTrimmed = false)!!
        assertFalse(kept.text.startsWith("…"))
        assertFalse(kept.text.endsWith("…"))
        val trimmed = SearchSnippet.present(window, "老黄", leadTrimmed = true, tailTrimmed = true)!!
        assertTrue(trimmed.text.startsWith("…"))
        assertTrue(trimmed.text.endsWith("…"))
        assertEquals("老黄", trimmed.text.substring(trimmed.matchStart, trimmed.matchEnd))
    }

    @Test
    fun trimFollowsTheRealWindowBounds() {
        val span = 98
        assertFalse(SearchSnippet.isLeadTrimmed(1))
        assertTrue(SearchSnippet.isLeadTrimmed(2))
        assertFalse(SearchSnippet.isTailTrimmed(snippetStart = 1, fullLength = span, span = span))
        assertTrue(SearchSnippet.isTailTrimmed(snippetStart = 1, fullLength = span + 1, span = span))
        assertFalse(SearchSnippet.isTailTrimmed(snippetStart = 100, fullLength = 150, span = span))
        assertTrue(SearchSnippet.isTailTrimmed(snippetStart = 100, fullLength = 300, span = span))
    }

    @Test
    fun asciiCaseFoldKeepsOriginalLetters() {
        val snippet = SearchSnippet.present("see NVIDIA now", "nvidia", leadTrimmed = false, tailTrimmed = false)!!
        assertEquals("NVIDIA", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
    }

    @Test
    fun chineseDoesNotFuzzyMatch() {
        assertNull(SearchSnippet.present("老王来了", "老黄", leadTrimmed = false, tailTrimmed = false))
    }

    @Test
    fun missingOrEmptyQueryReturnsNull() {
        assertNull(SearchSnippet.present("没有这个词", "老黄", leadTrimmed = false, tailTrimmed = false))
        assertNull(SearchSnippet.present("老黄", "", leadTrimmed = false, tailTrimmed = false))
    }

    @Test
    fun shortLineStaysLeftAligned() {
        assertEquals(0, SearchSnippet.matchShiftPx(200, 80, 0f, 20f))
    }

    @Test
    fun matchNearStartDoesNotLeaveAGap() {
        assertEquals(0, SearchSnippet.matchShiftPx(100, 300, 0f, 10f))
    }

    @Test
    fun matchInTheMiddleMovesToTheViewportCenter() {
        assertEquals(-100, SearchSnippet.matchShiftPx(100, 300, 140f, 160f))
    }

    @Test
    fun matchNearEndPinsToTheEnd() {
        assertEquals(-200, SearchSnippet.matchShiftPx(100, 300, 290f, 300f))
    }
}
