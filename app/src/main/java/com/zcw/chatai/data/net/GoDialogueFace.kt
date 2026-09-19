package com.zcw.chatai.data.net

/**
 * 主对话按**模型**选首选面（纯函数，JVM 可测）。
 *
 * 背景（2026-09 真 key 实测 + Phase0 探针）：
 * - Go 网关的 `grok-4.6 / gpt-5.6-luna / muse-spark-*` 在 chat/completions 面是
 *   503 `Endpoint is unavailable`，但在 `/responses` 面正常（含流式 + function tools）；
 * - deepseek 系 / minimax-m3 的 Responses 面不可用（无视工具空转或超时），必须走 chat。
 *
 * 和 `GoSearchFace`（data/web，管搜索后端选面）是同一张模型表、不同语义，
 * 故意各写一份：搜索面和对话面的挂载状态互不蕴含，分开改才不会互相绊倒。
 */
object GoDialogueFace {

    /** 该模型的主对话是否优先走 Responses 面。大小写不敏感。 */
    fun prefersResponses(model: String): Boolean {
        // 按分隔符分词后整词匹配：裸 `in` 会把 `amuse-x` 误判，`startsWith("luna")`
        // 会把 `lunalab-1` 误判；只有独立词元才算（`gpt-5.6-luna`→含 luna ✓）。
        val tokens = model.lowercase().substringAfterLast("/")
            .split('-', '_', '.', ' ').filter { it.isNotEmpty() }
        return "grok" in tokens || "luna" in tokens || "muse" in tokens || "gpt" in tokens
    }
}
