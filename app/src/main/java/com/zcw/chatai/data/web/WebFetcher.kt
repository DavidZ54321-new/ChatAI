package com.zcw.chatai.data.web

/** 本地抓取后端。 */
interface WebFetcher {
    suspend fun fetch(url: String): WebFetchResult
}
