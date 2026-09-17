package com.zcw.chatai.data.ai

/**
 * 「已深度思考」后面那段时间。
 *
 * - `null`：没测到（迁移前的历史消息 / 没有思考的消息）→ 界面只显示「已深度思考」
 * - 不足 1 秒：不给数字，避免出现「已深度思考 0s」
 * - 超过 1 分钟转为「1分12秒」：思考链可以很长（实测 1M 上下文的模型能想几分钟）
 */
fun formatReasoningDuration(ms: Long?): String? = when {
    ms == null || ms < 1_000 -> null
    ms < 60_000 -> "${ms / 1000}s"
    else -> {
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        if (seconds == 0L) "${totalSeconds / 60}分" else "${totalSeconds / 60}分${seconds}秒"
    }
}
