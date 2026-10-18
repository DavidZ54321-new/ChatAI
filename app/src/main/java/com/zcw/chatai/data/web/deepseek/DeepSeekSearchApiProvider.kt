package com.zcw.chatai.data.web.deepseek

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.ApiErrorMapper
import com.zcw.chatai.data.net.ChatApiException
import com.zcw.chatai.data.web.WebSearchProvider
import com.zcw.chatai.data.web.WebSearchResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * DeepSeek 官方 Search：`POST /v1/tools/search`，原始 JSON，不经过对话模型。
 * 空结果交给回退链借下一个后端；HTTP 错误抛出，同样借道。超长搜索词直接拒绝，不截断。
 */
class DeepSeekSearchApiProvider(
    private val client: OkHttpClient = defaultClient(),
) : WebSearchProvider {

    override val id: String = "deepseek-search"

    override fun available(baseUrl: String, apiKey: String): Boolean =
        apiKey.isNotBlank() && DeepSeekTools.searchUrl(baseUrl) != null

    override suspend fun search(query: String, maxResults: Int, config: ChatConfig): WebSearchResult {
        val url = DeepSeekTools.searchUrl(config.baseUrl)
            ?: throw ChatApiException("请先在设置中填写 Base URL")
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return WebSearchResult()
        if (DeepSeekTools.codePointCount(trimmed) > DeepSeekTools.MAX_QUERY_CHARS) {
            throw ChatApiException("搜索词超过 ${DeepSeekTools.MAX_QUERY_CHARS} 个字符，请缩短后再搜")
        }
        val payload = buildJsonObject {
            put("query", trimmed)
            put("count", maxResults.coerceIn(1, 50))
            put("need_content", false)
        }
        return try {
            val http = DeepSeekTools.post(client, url, config.apiKey, payload, MAX_RESPONSE_BYTES)
            if (http.code !in 200..299) {
                throw ChatApiException(
                    ApiErrorMapper.httpError(http.code, DeepSeekToolsParser.errorMessage(http.text)),
                )
            }
            DeepSeekToolsParser.parseSearch(http.text)
        } catch (e: ChatApiException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (t: Exception) {
            throw ChatApiException("联网搜索失败：${t.message ?: "未知错误"}", t)
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 2_000_000

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
