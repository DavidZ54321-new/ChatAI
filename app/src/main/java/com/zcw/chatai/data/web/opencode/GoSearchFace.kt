package com.zcw.chatai.data.web.opencode

/**
 * OpenCode Go 网关按**模型**选搜索面（纯函数，JVM 可测）。
 *
 * 背景（2026-09 真 key 实测）：
 * - 链路 A `/messages`（Anthropic `web_search_20260209`）：deepseek 系 + minimax-m3；
 * - 链路 B `/responses`（Responses `web_search`）：gpt-5.6-luna / grok-4.6 /
 *   muse-spark-1.2 / muse-spark-1.3，此时 `/messages` 503。
 *
 * 未知模型默认走 A（现状行为），路由器失败/空结果时再借道 B。
 */
object GoSearchFace {

    /** 该模型是否**优先**走 Responses 面。大小写不敏感（锚定规则见 `GoDialogueFace`，两表同语义）。 */
    fun prefersResponses(model: String): Boolean {
        val tokens = model.lowercase().substringAfterLast("/")
            .split('-', '_', '.', ' ').filter { it.isNotEmpty() }
        return "grok" in tokens || "luna" in tokens || "muse" in tokens || "gpt" in tokens
    }
}
