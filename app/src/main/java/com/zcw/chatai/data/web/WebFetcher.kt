package com.zcw.chatai.data.web

/** 抓取一页。本机实现和绑好凭据的 DeepSeek 实现都是这一个入口。 */
fun interface WebFetcher {
    suspend fun fetch(url: String): WebFetchResult
}
