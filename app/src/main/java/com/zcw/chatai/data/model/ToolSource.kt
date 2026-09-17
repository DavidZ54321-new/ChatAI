package com.zcw.chatai.data.model

/** 一条可引用的来源（URL 必有，其余可缺，不编造）。 */
data class ToolSource(
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
    val publishedAt: String? = null,
)

enum class ToolStatus { RUNNING, OK, FAILED }

/** 一次工具调用的结构化结果：结构化字段给 UI，[text] 给模型。 */
data class ToolResult(
    val status: ToolStatus,
    val detail: String,
    val sources: List<ToolSource> = emptyList(),
    val text: String = "",
)
