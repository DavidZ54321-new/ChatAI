package com.zcw.chatai.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBudgetTest {

    @Test
    fun positiveRemainingMentionsTheRoundCount() {
        assertTrue(ToolBudget.note(3).contains("3 more tool round(s) available this turn."))
    }

    @Test
    fun zeroRemainingForbidsToolMarkupAndAsksForAnHonestFinalAnswer() {
        val note = ToolBudget.note(0)
        assertTrue(note.contains("final tool round"))
        assertTrue(note.contains("Do not output any tool-call markup"))
        assertTrue(note.contains("unresolved"))
    }

    @Test
    fun mergeKeepsToolHintAndBudgetNoteInOrder() {
        assertEquals("hint\n\nbudget", ToolBudget.merge("hint", "budget"))
        assertEquals("hint", ToolBudget.merge("hint", null, ""))
        assertNull(ToolBudget.merge(null, " ", ""))
    }
}
