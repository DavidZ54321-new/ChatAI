package com.zcw.chatai.data.ai

import java.time.Instant
import java.time.ZoneId

/**
 * 上下文尾条的环境注记（纯函数，JVM 可测）。
 *
 * 报本机时间（年月日、星期、时分秒，24 小时制，设备时区）与设备形态（移动端）。
 * 位置以后再加——届时把参数升级为结构即可，调用形式不变。
 *
 * 星期手写中文映射：`DayOfWeek.getDisplayName` 在 Android ICU 与 JVM 间行为有差，
 * 确定性映射两边一致（仓库里日期/星期一律手写，见 `ConversationTitle` 的教训）。
 */
object EnvNote {

    private val WEEKDAYS = arrayOf(
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
    )

    /**
     * @param nowMs 墙钟毫秒（`System.currentTimeMillis()`；这里要的就是“现在几点”，
     *   与 `reasoning_ms` 的单调钟口径不同，别混用）。
     */
    fun format(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDateTime()
        val weekday = WEEKDAYS[local.dayOfWeek.value - 1]
        return "当前时间：" + local.year + "年" + local.monthValue + "月" + local.dayOfMonth + "日" +
            " " + weekday + " " + pad(local.hour) + ":" + pad(local.minute) + ":" + pad(local.second) +
            "；当前设备：移动端（Android 手机，触屏竖屏为主）"
    }

    private fun pad(value: Int): String = if (value < 10) "0$value" else value.toString()
}
