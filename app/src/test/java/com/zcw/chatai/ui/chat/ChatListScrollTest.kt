package com.zcw.chatai.ui.chat

import org.junit.Assert.assertEquals
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

    @Test
    fun bottomOverflowIsZeroWhenLastItemFitsInsideViewport() {
        assertEquals(0, bottomOverflow(lastOffset = 100, lastSize = 200, viewportEndOffset = 400))
        assertEquals(0, bottomOverflow(lastOffset = 200, lastSize = 200, viewportEndOffset = 400))
    }

    @Test
    fun bottomOverflowIsThePixelAmountBelowTheViewport() {
        assertEquals(120, bottomOverflow(lastOffset = 320, lastSize = 200, viewportEndOffset = 400))
    }

    @Test
    fun followScrollAllowedOnlyNearEndAndWithMoreToScroll() {
        // 贴底跟滑（末项/倒数第二项可见）且还有前向余量 → 可以补滚。
        assertTrue(canFollowScroll(lastVisibleIndex = 4, totalItems = 5, canScrollForward = true))
        assertTrue(canFollowScroll(lastVisibleIndex = 3, totalItems = 5, canScrollForward = true))
        // 已贴底（无前向余量）→ 不动。
        assertFalse(canFollowScroll(lastVisibleIndex = 4, totalItems = 5, canScrollForward = false))
    }

    @Test
    fun followScrollForbiddenWhileReadingHistory() {
        // 可见末项离末尾还差两项以上 = 用户在读内容，绝不动。
        assertFalse(canFollowScroll(lastVisibleIndex = 2, totalItems = 5, canScrollForward = true))
        assertFalse(canFollowScroll(lastVisibleIndex = 0, totalItems = 5, canScrollForward = true))
        assertFalse(canFollowScroll(lastVisibleIndex = null, totalItems = 5, canScrollForward = true))
        assertFalse(canFollowScroll(lastVisibleIndex = 0, totalItems = 0, canScrollForward = true))
    }

    @Test
    fun anchorJumpOnlyWhenLastItemIsNotYetVisible() {
        assertTrue(needsScrollToLastItem(lastVisibleIndex = null, totalItems = 5))
        assertTrue(needsScrollToLastItem(lastVisibleIndex = 2, totalItems = 5))
        assertFalse(needsScrollToLastItem(lastVisibleIndex = 4, totalItems = 5))
        assertFalse(needsScrollToLastItem(lastVisibleIndex = null, totalItems = 0))
    }
}
