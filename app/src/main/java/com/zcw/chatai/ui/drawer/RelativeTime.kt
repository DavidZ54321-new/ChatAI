package com.zcw.chatai.ui.drawer

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 会话列表的时间显示（纯函数，JVM 单测覆盖）。 */
object RelativeTime {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("M月d日")

    fun format(timestamp: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (timestamp <= 0L) return ""
        val diff = now - timestamp
        return when {
            diff < MINUTE -> "刚刚"
            diff < HOUR -> "${diff / MINUTE} 分钟前"
            diff < DAY && sameDay(timestamp, now, zone) -> "今天 " + timeOf(timestamp, zone)
            diff < 2 * DAY && sameDay(timestamp, now - DAY, zone) -> "昨天 " + timeOf(timestamp, zone)
            else -> dateOf(timestamp, zone)
        }
    }

    private fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean =
        dateAt(a, zone) == dateAt(b, zone)

    private fun dateAt(timestamp: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    private fun timeOf(timestamp: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalTime().format(timeFormatter)

    private fun dateOf(timestamp: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().format(dateFormatter)

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
}
