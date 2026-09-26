package com.zcw.chatai.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamHapticTest {

    private val clock = StreamHapticClock()

    @Test
    fun emptyShellDoesNotTickAndFollowingTextDoes() {
        assertFalse(clock.onFrame("m1", "", nowMs = 0))
        assertTrue(clock.onFrame("m1", "你", nowMs = 0))
    }

    @Test
    fun unchangedContentDoesNotTick() {
        clock.onFrame("m1", "", nowMs = 0)
        clock.onFrame("m1", "你好", nowMs = 0)
        // 思考增量会再次发布，但正文长度没变。
        assertFalse(clock.onFrame("m1", "你好", nowMs = 80))
    }

    @Test
    fun joiningMidStreamBaselinesWithoutATick() {
        assertFalse(clock.onFrame("m1", "已经写了一半", nowMs = 0))
        assertTrue(clock.onFrame("m1", "已经写了一半。", nowMs = 80))
    }

    @Test
    fun growthInsideTheIntervalTicksOnlyOnce() {
        clock.onFrame("m1", "", nowMs = 0)
        assertTrue(clock.onFrame("m1", "a", nowMs = 0))
        assertFalse(clock.onFrame("m1", "ab", nowMs = 50))
        assertTrue(clock.onFrame("m1", "abc", nowMs = 80))
    }

    @Test
    fun switchingMessageBaselinesTheFirstFrame() {
        clock.onFrame("m1", "", nowMs = 0)
        assertTrue(clock.onFrame("m1", "hi", nowMs = 0))
        assertFalse(clock.onFrame("m2", "已经有字", nowMs = 100))
        assertTrue(clock.onFrame("m2", "已经有字了", nowMs = 100))
    }

    @Test
    fun shorterContentDoesNotTick() {
        clock.onFrame("m1", "", nowMs = 0)
        assertTrue(clock.onFrame("m1", "hello", nowMs = 0))
        assertFalse(clock.onFrame("m1", "hell", nowMs = 80))
        assertTrue(clock.onFrame("m1", "hello", nowMs = 160))
    }

    @Test
    fun streamEndResetsSoTheNextMessageBaselines() {
        clock.onFrame("m1", "", nowMs = 0)
        assertTrue(clock.onFrame("m1", "hi", nowMs = 0))
        assertFalse(clock.onFrame(null, "", nowMs = 200))
        assertFalse(clock.onFrame("m2", "下一条已经有字", nowMs = 200))
        assertTrue(clock.onFrame("m2", "下一条已经有字。", nowMs = 280))
    }

    @Test
    fun unarmedFrameMakesTheNextArmedFrameABaseline() {
        clock.onFrame("m1", "", nowMs = 0)
        assertTrue(clock.onFrame("m1", "a", nowMs = 0))
        assertFalse(clock.onFrame("m1", "abcdef", nowMs = 200, armed = false))
        assertFalse(clock.onFrame("m1", "abcdefghij", nowMs = 500))
        assertTrue(clock.onFrame("m1", "abcdefghijk", nowMs = 580))
    }
}
