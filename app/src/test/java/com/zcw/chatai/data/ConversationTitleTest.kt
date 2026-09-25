package com.zcw.chatai.data

import com.zcw.chatai.data.model.ConversationTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTitleTest {

    @Test
    fun usesFirstNonBlankLine() {
        assertEquals("第一行", ConversationTitle.fromFirstMessage("\n \n\t\n第一行\n第二行"))
    }

    @Test
    fun collapsesConsecutiveWhitespace() {
        assertEquals("a b c", ConversationTitle.fromFirstMessage("  a \t  b   c  "))
    }

    @Test
    fun truncatesTo24CharactersAndAppendsEllipsis() {
        val title = ConversationTitle.fromFirstMessage("0123456789012345678901234567890")
        assertEquals("012345678901234567890123…", title)
        assertEquals(25, title.length)
    }

    @Test
    fun keepsTitleOfExactly24Characters() {
        val text = "012345678901234567890123"
        assertEquals(text, ConversationTitle.fromFirstMessage(text))
    }

    @Test
    fun truncatesChineseByCharacters() {
        val title = ConversationTitle.fromFirstMessage("汉字".repeat(13))
        assertEquals("汉字".repeat(12) + "…", title)
        assertEquals(25, title.length)
    }

    @Test
    fun blankInputFallsBackToNewConversation() {
        assertEquals("新对话", ConversationTitle.fromFirstMessage(""))
        assertEquals("新对话", ConversationTitle.fromFirstMessage("   \n\t \n"))
        assertEquals("新对话", ConversationTitle.fromFirstMessage(" \u3000 "))
    }

    @Test
    fun collapsesUnicodeSpacesWithoutRegex() {
        // U+00A0 不换行空格 / U+3000 全角空格 都要被折叠（ICU 正则不支持 (?U)，故用 isWhitespace）
        assertEquals("a b c", ConversationTitle.preview("a\u00A0\u3000b\t c"))
    }

    @Test
    fun previewFlattensMultilineText() {
        assertEquals("第一行 第二行", ConversationTitle.preview("第一行\n\n   第二行"))
        assertEquals("", ConversationTitle.preview("   \n  "))
    }

    @Test
    fun previewTruncatesAtEightyCharacters() {
        val preview = ConversationTitle.preview("x".repeat(200))
        assertEquals(81, preview.length)
        assertEquals("x".repeat(80) + "…", preview)
    }

    @Test
    fun branchedAppendsSuffix() {
        assertEquals("方案对比 · 分支", ConversationTitle.branched("方案对比"))
    }

    /** 超长时先截源标题：后缀是「这是分支」的唯一线索，必须完整（总长仍 ≤ 24）。 */
    @Test
    fun branchedTruncatesSourceTitleButKeepsSuffix() {
        val title = ConversationTitle.branched("x".repeat(40))
        assertEquals("x".repeat(19) + " · 分支", title)
        assertEquals(24, title.length)
    }

    @Test
    fun branchedFallsBackWhenSourceTitleIsBlank() {
        assertEquals("新对话 · 分支", ConversationTitle.branched("   "))
        assertEquals("新对话 · 分支", ConversationTitle.branched(""))
    }

    @Test
    fun branchedCollapsesWhitespaceLikeTitles() {
        assertEquals("a b · 分支", ConversationTitle.branched("a\n\t b  "))
    }
}
