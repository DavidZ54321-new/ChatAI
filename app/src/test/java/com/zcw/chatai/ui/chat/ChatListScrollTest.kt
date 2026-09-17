package com.zcw.chatai.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListScrollTest {

    @Test
    fun emptyListCountsAsAtBottom() {
        assertTrue(isChatListAtBottom(lastVisibleIndex = null, totalItems = 0, canScrollForward = false))
    }

    @Test
    fun lastItemNotComposedIsNotAtBottomEvenIfCannotScroll() {
        // 懒加载假到底：下面还有 item，但高度还没量，canScrollForward 已经是 false。
        assertFalse(isChatListAtBottom(lastVisibleIndex = 2, totalItems = 5, canScrollForward = false))
        assertFalse(isChatListAtBottom(lastVisibleIndex = 2, totalItems = 5, canScrollForward = true))
    }

    @Test
    fun lastItemVisibleButCanStillScrollIsNotAtBottom() {
        assertFalse(isChatListAtBottom(lastVisibleIndex = 4, totalItems = 5, canScrollForward = true))
    }

    @Test
    fun lastItemVisibleAndCannotScrollIsAtBottom() {
        assertTrue(isChatListAtBottom(lastVisibleIndex = 4, totalItems = 5, canScrollForward = false))
    }
}
