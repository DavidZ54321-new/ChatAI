package com.zcw.chatai.data.doc

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * xlsx 单元格格式（纯逻辑，JVM 单测覆盖）。
 *
 * 日期渲染是「近似 Excel 显示」：把 numFmt 代码转成 SimpleDateFormat 模式。
 * 秒后小数、1900 年闰 bug 这类边角直接近似（聊天里看日期，够用即可）。
 */
internal object XlsxFormats {

    /** 内建日期/时间 numFmtId（49=@ 文本除外）。 */
    private val BUILTIN_DATE_IDS = setOf(
        14, 15, 16, 17, 18, 19, 20, 21, 22,
        27, 28, 29, 30, 31, 32, 33, 34, 35, 36,
        45, 46, 47, 50, 57,
    )

    fun isDateFormat(numFmtId: Int, code: String?): Boolean {
        if (numFmtId in BUILTIN_DATE_IDS) return true
        if (code.isNullOrBlank() || code.equals("General", ignoreCase = true)) return false
        val cleaned = stripLiterals(code)
        // 引号/方括号剥掉后还剩 ymdh s 任一日期时间 token 即判日期。
        return cleaned.any { it == 'y' || it == 'Y' || it == 'm' || it == 'M' ||
            it == 'd' || it == 'D' || it == 'h' || it == 'H' || it == 's' || it == 'S' }
    }

    /** Excel 序列数 → 可读日期时间；转不出返回 null（调用方回退纯数字）。 */
    fun formatDate(serial: Double, code: String?, use1904: Boolean): String? {
        if (serial.isNaN() || serial.isInfinite() || serial < 0) return null
        // 离谱序列数直接放弃：floor().toInt() 会溢出回绕，虽不崩但日期荒谬。
        if (serial > 2958465.0) return null
        return try {
            val pattern = toJavaPattern(code) ?: "yyyy-MM-dd HH:mm:ss"
            val format = SimpleDateFormat(pattern, Locale.getDefault())
            format.isLenient = false
            format.format(serialToCalendar(serial, use1904).time)
        } catch (t: Exception) {
            null
        }
    }

    /** 纯函数：numFmt 代码 → SimpleDateFormat 模式（转不出返回 null）。 */
    internal fun toJavaPattern(code: String?): String? {
        if (code.isNullOrBlank()) return null
        val hasAmPm = code.contains("AM/PM", ignoreCase = true) || code.contains("A/P", ignoreCase = true)
        // Excel 的 m 既当月又当分：紧邻 h（前）或 s（后）的是分，其余是月。
        // 先扫一遍引号/方括号之外的日期时间字母定上下文。
        val letters = significantLetters(code)
        fun isMinutesAt(pos: Int): Boolean {
            val at = letters.indexOfFirst { it.first == pos }.takeIf { it >= 0 } ?: return false
            val prev = letters.getOrNull(at - 1)?.second
            val next = letters.getOrNull(at + 1)?.second
            return prev == 'h' || next == 's'
        }
        val builder = StringBuilder()
        var index = 0
        while (index < code.length) {
            val ch = code[index]
            when {
                ch == '"' -> {
                    val end = code.indexOf('"', index + 1).takeIf { it >= 0 } ?: code.length
                    builder.append('\'')
                    builder.append(code.substring(index + 1, end).replace("'", "''"))
                    builder.append('\'')
                    index = if (end < code.length) end + 1 else end
                }
                ch == '[' -> {
                    val end = code.indexOf(']', index + 1).takeIf { it >= 0 } ?: code.length
                    index = if (end < code.length) end + 1 else end
                }
                ch == '\\' && index + 1 < code.length -> {
                    builder.append('\'').append(code[index + 1]).append('\'')
                    index += 2
                }
                ch == '_' || ch == '*' -> index += 2 // 占位/填充：连带下个字符一起丢
                ch == '@' -> index++
                ch == 'y' || ch == 'Y' -> {
                    builder.append('y')
                    index++
                }
                ch == 'm' || ch == 'M' -> {
                    // 整段 m 连写共享上下文（首字母定）：前邻 h 或后邻 s 是一整段分。
                    var end = index
                    while (end < code.length && (code[end] == 'm' || code[end] == 'M')) end++
                    val minutes = isMinutesAt(index)
                    repeat(end - index) { builder.append(if (minutes) 'm' else 'M') }
                    index = end
                }
                ch == 'd' || ch == 'D' -> {
                    builder.append('d')
                    index++
                }
                ch == 'h' || ch == 'H' -> {
                    builder.append(if (hasAmPm) 'h' else 'H')
                    index++
                }
                ch == 's' || ch == 'S' -> {
                    builder.append('s')
                    index++
                    // 秒后小数（.00）直接丢掉，只留秒精度。
                    if (index < code.length && code[index] == '.') {
                        index++
                        while (index < code.length && (code[index] == '0' || code[index] == '#' || code[index] == '?')) {
                            index++
                        }
                    }
                }
                ch == 'A' || ch == 'a' -> {
                    if (code.regionMatches(index, "AM/PM", 0, 5, ignoreCase = true) ||
                        code.regionMatches(index, "A/P", 0, 3, ignoreCase = true)
                    ) {
                        builder.append('a')
                        index += if (code[index + 1] == '/') 3 else 5
                    } else {
                        index++
                    }
                }
                ch == '-' || ch == '/' || ch == ':' || ch == ',' || ch == '.' || ch == ' ' -> {
                    builder.append(ch)
                    index++
                }
                else -> index++ // 其余字母一律丢掉：SimpleDateFormat 遇到未知模式字母会抛异常
            }
        }
        val pattern = builder.toString().trim()
        if (pattern.none { it == 'y' || it == 'M' || it == 'm' || it == 'd' || it == 'H' || it == 'h' || it == 's' }) {
            return null
        }
        return pattern
    }

    /** 引号/方括号/转义之外的日期时间字母（位置， 小写），给 m 定月/分上下文用。 */
    internal fun significantLetters(code: String): List<Pair<Int, Char>> {
        val letters = ArrayList<Pair<Int, Char>>()
        var index = 0
        while (index < code.length) {
            val ch = code[index]
            when {
                ch == '"' -> {
                    index = code.indexOf('"', index + 1).takeIf { it >= 0 }?.plus(1) ?: code.length
                }
                ch == '[' -> {
                    index = code.indexOf(']', index + 1).takeIf { it >= 0 }?.plus(1) ?: code.length
                }
                ch == '\\' -> index += 2
                ch == 'y' || ch == 'Y' || ch == 'm' || ch == 'M' ||
                    ch == 'd' || ch == 'D' || ch == 'h' || ch == 'H' ||
                    ch == 's' || ch == 'S' -> {
                    letters += index to ch.lowercaseChar()
                    index++
                }
                else -> index++
            }
        }
        return letters
    }
    /** 去引号原文与方括号段（颜色/条件/本地化标记），只留裸 token。 */
    internal fun stripLiterals(code: String): String {
        val builder = StringBuilder()
        var index = 0
        while (index < code.length) {
            val ch = code[index]
            when {
                ch == '"' -> {
                    index = code.indexOf('"', index + 1).takeIf { it >= 0 }?.plus(1) ?: code.length
                }
                ch == '[' -> {
                    index = code.indexOf(']', index + 1).takeIf { it >= 0 }?.plus(1) ?: code.length
                }
                ch == '\\' -> index += 2
                else -> {
                    builder.append(ch)
                    index++
                }
            }
        }
        return builder.toString()
    }

    /**
     * 序列数 → Calendar（挂钟语义，不做时区换算，与 Excel 显示一致）。
     * 1900 制式以 1899-12-30 为第 0 天；1904 制式整体 +1462 天。
     */
    internal fun serialToCalendar(serial: Double, use1904: Boolean): Calendar {
        val days = kotlin.math.floor(serial).toInt()
        val millisOfDay = ((serial - kotlin.math.floor(serial)) * 86_400_000.0).toLong()
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.add(Calendar.DATE, days + if (use1904) 1462 else 0)
        calendar.add(Calendar.MILLISECOND, millisOfDay.toInt())
        return calendar
    }
}
