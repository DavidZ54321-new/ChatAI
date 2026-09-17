package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig

/** 搜索后端：换实现不动主回路。 */
interface WebSearchProvider {
    val id: String

    /** 廉价的本地检查（无网络调用）：能否用当前配置发起搜索。 */
    fun available(baseUrl: String, apiKey: String): Boolean

    suspend fun search(query: String, maxResults: Int, config: ChatConfig): WebSearchResult
}
