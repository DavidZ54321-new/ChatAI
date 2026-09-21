package com.zcw.chatai.data.ai

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvNoteTest {

    // 2026-09-20 14:32:05 +08:00（星期日）。
    private val nowMs = 1_789_885_925_000L
    private val shanghai = ZoneId.of("Asia/Shanghai")

    private val deviceSuffix = "；当前设备：移动端（Android 手机，触屏竖屏为主）"

    @Test
    fun formatsDateWeekdayAndTime() {
        assertEquals("当前时间：2026年9月20日 星期日 14:32:05$deviceSuffix", EnvNote.format(nowMs, shanghai))
    }

    @Test
    fun convertsToGivenZone() {
        // 同一瞬间的 UTC：2026-09-20 06:32:05（星期日）。
        val utc = ZoneId.of("UTC")
        assertEquals("当前时间：2026年9月20日 星期日 06:32:05$deviceSuffix", EnvNote.format(nowMs, utc))
    }

    @Test
    fun padsSingleDigitTimeFields() {
        // 2026-09-20 06:05:09 UTC。
        assertEquals(
            "当前时间：2026年9月20日 星期日 06:05:09$deviceSuffix",
            EnvNote.format(1_789_884_309_000L, ZoneId.of("UTC")),
        )
    }

    @Test
    fun sameInstantFallsOnDifferentDateAcrossZones() {
        // 纽约仍是 9 月 20 日凌晨，日期相同但时间不同：锁定“按设备时区换算”。
        val newYork = ZoneId.of("America/New_York")
        assertEquals("当前时间：2026年9月20日 星期日 02:32:05$deviceSuffix", EnvNote.format(nowMs, newYork))
    }

    @Test
    fun mondayMapsCorrectly() {
        // 2026-09-21 00:00:00 +08:00（星期一）。
        assertEquals("当前时间：2026年9月21日 星期一 00:00:00$deviceSuffix", EnvNote.format(1_789_920_000_000L, shanghai))
    }

    @Test
    fun alwaysMentionsMobileDevice() {
        assertTrue(EnvNote.format(nowMs, shanghai).endsWith(deviceSuffix))
    }
}
