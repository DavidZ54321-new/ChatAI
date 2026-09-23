package com.zcw.chatai.ui.chat

import java.time.LocalTime

/**
 * 空会话标题按本地钟点切换。区间左闭右开，接成一整天：
 * 06:00 早上好，11:00 中午好，14:00 下午好，18:00 晚上好，22:00 到次日 06:00 夜深了。
 */
object TimeGreeting {

    fun of(hour: Int, minute: Int): String {
        val minutes = hour * 60 + minute
        return when {
            minutes < 6 * 60 -> "夜深了"
            minutes < 11 * 60 -> "早上好"
            minutes < 14 * 60 -> "中午好"
            minutes < 18 * 60 -> "下午好"
            minutes < 22 * 60 -> "晚上好"
            else -> "夜深了"
        }
    }

    /** 距离下一次文案变化的毫秒数；正好卡在边界上则等到下一个边界。至少 1ms。 */
    fun millisUntilNextBoundary(time: LocalTime): Long {
        val nanos = time.toNanoOfDay()
        val dayNanos = 24L * 60 * 60 * 1_000_000_000L
        val hourNanos = 3_600_000_000_000L
        val marks = longArrayOf(6L * hourNanos, 11L * hourNanos, 14L * hourNanos, 18L * hourNanos, 22L * hourNanos)
        val next = marks.firstOrNull { it > nanos }
        val delta = if (next != null) next - nanos else dayNanos - nanos + marks[0]
        return (delta / 1_000_000L).coerceAtLeast(1L)
    }
}
