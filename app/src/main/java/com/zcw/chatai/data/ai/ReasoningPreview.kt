package com.zcw.chatai.data.ai

/**
 * 把整段推理折叠成单行（换行 == 空格），不截断。
 *
 * 不能只取「第一段非空行」—— 推理文本的行结构不可控（模型可能先写一个小标题、
 * 或用空行分段、甚至逐字换行），那样预览会退化成很短甚至一个字的碎片。
 *
 * 折叠空白手写循环而不用 `Regex` —— Android 走 ICU 正则引擎，`(?U)` 这类内联标志
 * 会在类初始化时抛 ExceptionInInitializerError，而 JVM 单测发现不了。
 */
fun collapseReasoningWhitespace(reasoning: String): String? {
    val collapsed = StringBuilder()
    var pendingSpace = false
    for (ch in reasoning) {
        if (ch.isWhitespace()) {
            if (collapsed.isNotEmpty()) pendingSpace = true
        } else {
            if (pendingSpace) {
                collapsed.append(' ')
                pendingSpace = false
            }
            collapsed.append(ch)
        }
    }
    return collapsed.toString().takeIf { it.isNotEmpty() }
}

/** 思考行滚动窗口：生成中取尾，结束后取头。避免把整段思维链丢进 Text。 */
fun reasoningTickerText(collapsed: String, followEnd: Boolean, maxChars: Int = 200): String {
    if (collapsed.length <= maxChars) return collapsed
    return if (followEnd) collapsed.takeLast(maxChars) else collapsed.take(maxChars)
}
