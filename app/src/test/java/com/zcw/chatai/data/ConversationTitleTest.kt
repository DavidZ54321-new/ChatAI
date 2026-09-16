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
}
