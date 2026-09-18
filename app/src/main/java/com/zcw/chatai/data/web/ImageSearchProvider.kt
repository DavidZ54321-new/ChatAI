package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.model.SearchedImage

/** 一次图搜的结果：图片列表 + 可选的模型说明。 */
data class ImageSearchOutcome(
    val images: List<SearchedImage>,
    val answer: String? = null,
)

/**
 * 图搜后端（文搜图 + 以图搜图）：换实现不动主回路。
 * 只有部分供应商（Qwen Responses API）提供这类原生工具。
 */
interface ImageSearchProvider {
    val id: String

    /** 廉价的本地检查（无网络调用）：能否用当前配置发起图搜。 */
    fun available(baseUrl: String, apiKey: String): Boolean

    /** 文搜图：按文字描述找网图。 */
    suspend fun searchByText(query: String, maxResults: Int, config: ChatConfig): ImageSearchOutcome

    /** 以图搜图：按图片内容找视觉相似的网图（[imageDataUrl] 为 base64 data URI）。 */
    suspend fun searchByImage(
        imageDataUrl: String,
        hint: String?,
        maxResults: Int,
        config: ChatConfig,
    ): ImageSearchOutcome
}
