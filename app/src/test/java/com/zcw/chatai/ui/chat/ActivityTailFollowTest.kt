package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTailFollowTest {

    @Test
    fun firstMeasureNeverScrollsAndPinsOnlyWhenAlreadyAtBottom() {
        val fits = ActivityTailFollow.Pin()
        assertNull(fits.onMeasured(value = 0, max = 0, following = true))
        assertTrue(fits.pinned)

        val tall = ActivityTailFollow.Pin()
        assertNull(tall.onMeasured(value = 0, max = 4000, following = true))
        assertFalse(tall.pinned)
    }

    @Test
    fun growthWhilePinnedScrollsToTheNewBottom() {
        val pin = ActivityTailFollow.Pin()
        pin.onMeasured(value = 0, max = 0, following = true)
        assertEquals(180, pin.onMeasured(value = 0, max = 180, following = true))
        assertTrue(pin.pinned)
    }

    @Test
    fun growthWhileReadingEarlierTextDoesNotScroll() {
        val pin = ActivityTailFollow.Pin()
        pin.onMeasured(value = 0, max = 4000, following = true)
        assertNull(pin.onMeasured(value = 0, max = 4200, following = true))
        assertFalse(pin.pinned)
    }

    @Test
    fun scrollingBackToTheBottomFollowsTheNextGrowth() {
        val pin = ActivityTailFollow.Pin()
        pin.onMeasured(value = 0, max = 4000, following = true)
        pin.onUserScroll(value = 4000, max = 4000, following = true)
        assertTrue(pin.pinned)
        assertEquals(4300, pin.onMeasured(value = 4000, max = 4300, following = true))
    }

    @Test
    fun scrollingUpReleasesThePin() {
        val pin = ActivityTailFollow.Pin()
        pin.onMeasured(value = 0, max = 0, following = true)
        pin.onMeasured(value = 0, max = 200, following = true)
        pin.onUserScroll(value = 40, max = 200, following = true)
        assertFalse(pin.pinned)
        assertNull(pin.onMeasured(value = 40, max = 260, following = true))
    }

    @Test
    fun sheetGrowingUntilTheTextFitsAllowsTheNextGrowthToFollow() {
        val pin = ActivityTailFollow.Pin()
        assertNull(pin.onMeasured(value = 0, max = 900, following = true))
        assertFalse(pin.pinned)
        assertNull(pin.onMeasured(value = 0, max = 0, following = true))
        assertTrue(pin.pinned)
        assertEquals(40, pin.onMeasured(value = 0, max = 40, following = true))
    }

    @Test
    fun finishedStepNeverScrolls() {
        val pin = ActivityTailFollow.Pin()
        assertNull(pin.onMeasured(value = 0, max = 0, following = false))
        assertFalse(pin.pinned)
        assertNull(pin.onMeasured(value = 0, max = 300, following = false))
        pin.onUserScroll(value = 300, max = 300, following = false)
        assertFalse(pin.pinned)
    }

    @Test
    fun followsTailOnlyWhileThatStepIsStillGrowing() {
        assertTrue(
            ActivityLog.followsTail(
                ActivityStep.Thinking("a", "想", reasoningMs = null, streaming = true, answerStarted = false),
            ),
        )
        assertFalse(
            ActivityLog.followsTail(
                ActivityStep.Thinking("a", "想", reasoningMs = 25_000, streaming = true, answerStarted = true),
            ),
        )
        assertFalse(
            ActivityLog.followsTail(
                ActivityStep.Thinking("a", "想", reasoningMs = 25_000, streaming = false, answerStarted = false),
            ),
        )
        assertTrue(
            ActivityLog.followsTail(
                ActivityStep.Tool("t", ToolResult(status = ToolStatus.RUNNING, detail = "查询")),
            ),
        )
        assertFalse(
            ActivityLog.followsTail(
                ActivityStep.Tool("t", ToolResult(status = ToolStatus.OK, detail = "查询")),
            ),
        )
    }
}
