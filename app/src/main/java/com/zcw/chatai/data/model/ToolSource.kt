package com.zcw.chatai.data.model

/** 一条可引用的来源（URL 必有，其余可缺，不编造）。 */
data class ToolSource(
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
    val publishedAt: String? = null,
)

/** 图搜结果里的一张网图（Qwen 的 `{index,title,url}` 形状）。 */
data class SearchedImage(
    val index: Int,
    val title: String,
    val url: String,
)

enum class ToolStatus { RUNNING, OK, FAILED }

/** 工具语义分类：决定工具行标题与结果渲染方式（旧数据缺失时按 SEARCH 兜底）。 */
enum class ToolKind { SEARCH, FETCH, IMAGE_SEARCH, IMAGE_SIMILAR }

/** 一次工具调用的结构化结果：结构化字段给 UI，[text] 给模型，[modelNote] 只给模型。 */
data class ToolResult(
    val status: ToolStatus,
    val detail: String,
    val sources: List<ToolSource> = emptyList(),
    val text: String = "",
    val kind: ToolKind = ToolKind.SEARCH,
    val images: List<SearchedImage> = emptyList(),
    /**
     * 仅随 [text] 一起发给模型的附注（工具预算、空结果提示等）；
     * UI 只渲染 [text]，不显示这里的内容。
     */
    val modelNote: String? = null,
)
