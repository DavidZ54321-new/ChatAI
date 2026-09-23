package com.zcw.chatai.ui.chat

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeGreetingTest {

    @Test
    fun dayIsSplitIntoFiveGreetings() {
        assertEquals("夜深了", TimeGreeting.of(0, 0))
        assertEquals("夜深了", TimeGreeting.of(5, 59))
        assertEquals("早上好", TimeGreeting.of(6, 0))
        assertEquals("早上好", TimeGreeting.of(10, 59))
        assertEquals("中午好", TimeGreeting.of(11, 0))
        assertEquals("中午好", TimeGreeting.of(13, 59))
        assertEquals("下午好", TimeGreeting.of(14, 0))
        assertEquals("下午好", TimeGreeting.of(17, 59))
        assertEquals("晚上好", TimeGreeting.of(18, 0))
        assertEquals("晚上好", TimeGreeting.of(21, 59))
        assertEquals("夜深了", TimeGreeting.of(22, 0))
        assertEquals("夜深了", TimeGreeting.of(23, 59))
    }

    @Test
    fun nextBoundaryIsTheFollowingGreetingChange() {
        assertEquals(60_000L, TimeGreeting.millisUntilNextBoundary(LocalTime.of(10, 59, 0)))
        assertEquals(1_000L, TimeGreeting.millisUntilNextBoundary(LocalTime.of(5, 59, 59)))
        assertEquals(5L * 60 * 60 * 1000, TimeGreeting.millisUntilNextBoundary(LocalTime.of(6, 0, 0)))
        assertEquals(8L * 60 * 60 * 1000, TimeGreeting.millisUntilNextBoundary(LocalTime.of(22, 0, 0)))
    }
}
