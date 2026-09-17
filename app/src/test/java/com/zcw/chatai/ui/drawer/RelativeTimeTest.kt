package com.zcw.chatai.ui.drawer

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun recentTimestampsAreRelative() {
        val now = at(2026, 9, 17, 20, 0)
        assertEquals("刚刚", RelativeTime.format(now - 30_000, now, zone))
        assertEquals("5 分钟前", RelativeTime.format(now - 5 * 60_000, now, zone))
        assertEquals("59 分钟前", RelativeTime.format(now - 59 * 60_000, now, zone))
    }

    @Test
    fun sameDayShowsClockTime() {
        val now = at(2026, 9, 17, 20, 0)
        assertEquals("今天 09:30", RelativeTime.format(at(2026, 9, 17, 9, 30), now, zone))
    }

    @Test
    fun yesterdayIsLabelled() {
        val now = at(2026, 9, 17, 1, 0)
        assertEquals("昨天 23:00", RelativeTime.format(at(2026, 9, 16, 23, 0), now, zone))
    }

    @Test
    fun olderShowsMonthAndDay() {
        val now = at(2026, 9, 17, 20, 0)
        assertEquals("9月1日", RelativeTime.format(at(2026, 9, 1, 12, 0), now, zone))
        assertEquals("8月30日", RelativeTime.format(at(2026, 8, 30, 12, 0), now, zone))
    }

    @Test
    fun missingTimestampIsBlank() {
        assertEquals("", RelativeTime.format(0L, at(2026, 9, 17, 20, 0), zone))
    }

    @Test
    fun labelsPreviousDayEvenWhenLessThanADayAgo() {
        val now = LocalDate.of(2026, 9, 17).atTime(0, 5).atZone(zone).toInstant().toEpochMilli()
        val justBefore = LocalDate.of(2026, 9, 16).atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("昨天 22:00", RelativeTime.format(justBefore, now, zone))
    }

    @Test
    fun veryFreshTimestampWinsOverDayBoundary() {
        val now = LocalDate.of(2026, 9, 17).atTime(0, 5).atZone(zone).toInstant().toEpochMilli()
        val justBefore = LocalDate.of(2026, 9, 16).atTime(23, 55).atZone(zone).toInstant().toEpochMilli()
        assertEquals("10 分钟前", RelativeTime.format(justBefore, now, zone))
    }
}
