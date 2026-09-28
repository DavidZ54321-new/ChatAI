package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.ToolKind
import com.zcw.chatai.data.model.ToolResult

/**
 * 工具行标题。主列表和链路页共用，避免两套文案。
 *
 * 旧数据没有 kind，按 SEARCH 兜底：抓取行的 detail 是 URL 或「正在抓取」。
 */
fun toolCallTitle(result: ToolResult): String = when (result.kind) {
    ToolKind.FETCH -> "网页抓取"
    ToolKind.IMAGE_SEARCH -> "文搜图"
    ToolKind.IMAGE_SIMILAR -> "以图搜图"
    ToolKind.SEARCH ->
        if (result.detail.startsWith("http") || result.detail.startsWith("正在抓取")) "网页抓取" else "联网搜索"
}
