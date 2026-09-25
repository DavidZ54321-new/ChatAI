package com.zcw.chatai.data.ai

/** 思考行展示用的字数。预览折叠和 ticker 共用，避免一边截一边又扫整段。 */
const val REASONING_PREVIEW_CHARS = 200

/**
 * 把推理折叠成单行（换行 == 空格）。
 *
 * 不能只取「第一段非空行」—— 推理文本的行结构不可控（模型可能先写一个小标题、
 * 或用空行分段、甚至逐字换行），那样预览会退化成很短甚至一个字的碎片。
 *
 * [maxChars] 到达后停止扫描：历史行只要开头一段，不必把几万字再拼成一个新字符串。
 * 默认不截断。折叠空白手写循环而不用 `Regex` —— Android 走 ICU 正则引擎。
 */
fun collapseReasoningWhitespace(reasoning: String, maxChars: Int = Int.MAX_VALUE): String? {
    if (maxChars <= 0) return null
    val collapsed = StringBuilder()
    var pendingSpace = false
    for (ch in reasoning) {
        if (ch.isWhitespace()) {
            if (collapsed.isNotEmpty()) pendingSpace = true
        } else {
            if (pendingSpace) {
                // 空格后面还要放得下一个字，否则会留下悬空空格。
                if (collapsed.length + 1 >= maxChars) break
                collapsed.append(' ')
                pendingSpace = false
            }
            if (collapsed.length >= maxChars) break
            collapsed.append(ch)
        }
    }
    return collapsed.toString().takeIf { it.isNotEmpty() }
}

/** 思考行滚动窗口：生成中取尾，结束后取头。避免把整段思维链丢进 Text。 */
fun reasoningTickerText(
    collapsed: String,
    followEnd: Boolean,
    maxChars: Int = REASONING_PREVIEW_CHARS,
): String {
    if (collapsed.length <= maxChars) return collapsed
    return if (followEnd) collapsed.takeLast(maxChars) else collapsed.take(maxChars)
}
