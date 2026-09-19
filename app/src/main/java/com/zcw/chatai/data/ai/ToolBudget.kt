package com.zcw.chatai.data.ai

/**
 * 工具预算提示（纯函数，JVM 单测覆盖）。
 *
 * 每次工具执行完都把「还剩几轮」写进该工具结果的 [com.zcw.chatai.data.model.ToolResult.modelNote]，
 * 只给模型看（UI 不显示）。余 0 的那一轮同时承担「无工具收尾」的指令：模型已经没有任何工具可调，
 * 必须用现有信息作答并如实说明未完成的部分——这条也顺手堵住 DeepSeek 在无工具请求里把内部
 * DSML 工具调用标记漏进正文的行为。
 */
object ToolBudget {

    fun note(remaining: Int): String {
        if (remaining > 0) {
            return "[Tool budget] $remaining more tool round(s) available this turn."
        }
        return "[Tool budget] This was your final tool round; no further tool calls are allowed. " +
            "Do not output any tool-call markup. Answer the user now using the information already " +
            "gathered, and state plainly which parts of the task are still unresolved."
    }

    /** 把工具自身的提示与预算提示合并成一条 modelNote（都为空则返回 null）。 */
    fun merge(vararg notes: String?): String? =
        notes.filterNotNull().filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}
