package com.zcw.chatai.ui.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationListMemoryTest {

    @Test
    fun blankQueryIsTheBrowseList() {
        assertEquals(ListPane.BROWSE, listPane(""))
        assertEquals(ListPane.BROWSE, listPane("   "))
        assertEquals(ListPane.FILTERED, listPane("猫"))
        assertEquals(ListPane.FILTERED, listPane(" 猫"))
    }

    @Test
    fun filteredScrollResetsOnlyWhenTheQueryChanges() {
        assertTrue(shouldResetFilteredScroll("", "猫"))
        assertFalse(shouldResetFilteredScroll("猫", "猫"))
        assertTrue(shouldResetFilteredScroll("猫", "猫咪"))
        // 清空或纯空白切回未筛选列表，筛选那份滚动留着。
        assertFalse(shouldResetFilteredScroll("猫", ""))
        assertFalse(shouldResetFilteredScroll("猫", "   "))
    }

    @Test
    fun searchFieldStaysWhenTheQueryIsStillThere() {
        assertFalse(searchFieldVisible("", expanded = false))
        assertTrue(searchFieldVisible("", expanded = true))
        assertFalse(searchFieldVisible("   ", expanded = false))
        assertTrue(searchFieldVisible("   ", expanded = true))
        assertTrue(searchFieldVisible("猫", expanded = false))
        assertTrue(searchFieldVisible("猫", expanded = true))
    }
}
