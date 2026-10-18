package com.zcw.chatai.data.web.deepseek

import com.zcw.chatai.data.net.ApiErrorMapper
import com.zcw.chatai.data.web.HttpWebFetcher
import com.zcw.chatai.data.web.WebFetchResult
import com.zcw.chatai.data.web.WebFetcher
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * DeepSeek 官方 Fetch：`POST /v1/tools/fetch`。
 *
 * [bound] 把 Key 和基址收进 [WebFetcher]，仓库只按 URL 调用。
 * 发出去之前先做和本机抓取一样的公网校验。HTTP 失败是结果，不改走本机。
 * 读超时留到 90 秒：上游网关自己会等大约 35 秒才返回 504，连接慢时不能先被客户端掐断。
 */
class DeepSeekFetchProvider(
    private val client: OkHttpClient = defaultClient(),
    private val hostPolicy: (String) -> Boolean = HttpWebFetcher::isPublicHost,
    private val maxBytes: Int = MAX_RESPONSE_BYTES,
) {

    fun available(baseUrl: String, apiKey: String): Boolean =
        apiKey.isNotBlank() && DeepSeekTools.fetchUrl(baseUrl) != null

    fun bound(baseUrl: String, apiKey: String): WebFetcher = WebFetcher { url ->
        fetch(url, baseUrl, apiKey)
    }

    suspend fun fetch(url: String, baseUrl: String, apiKey: String): WebFetchResult = withContext(Dispatchers.IO) {
        val rejected = reject(url)
        if (rejected != null) return@withContext rejected
        val endpoint = DeepSeekTools.fetchUrl(baseUrl)
            ?: return@withContext failure(url, "请先在设置中填写 Base URL")
        val payload = buildJsonObject { put("url", url.trim()) }
        try {
            val http = DeepSeekTools.post(client, endpoint, apiKey, payload, maxBytes)
            if (http.code !in 200..299) {
                // 404 多半是目标页，不是 Chat Base URL。有服务端原文就原样带上。
                val detail = DeepSeekToolsParser.errorMessage(http.text)
                val text = if (detail != null) {
                    "抓取失败（HTTP ${http.code}）：$detail"
                } else {
                    ApiErrorMapper.httpError(http.code, null)
                }
                return@withContext WebFetchResult(
                    url = url.trim(),
                    statusCode = http.code,
                    text = text,
                    truncated = false,
                )
            }
            val page = DeepSeekToolsParser.parseFetch(http.text)
                ?: return@withContext failure(url, "抓取结果无法解析")
            if (page.content.isBlank()) {
                return@withContext failure(url, "抓取没有返回正文")
            }
            val body = if (page.title.isBlank()) page.content else page.title + "\n\n" + page.content
            WebFetchResult(
                url = url.trim(),
                statusCode = http.code,
                text = body,
                truncated = http.bytes.size >= maxBytes,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Exception) {
            failure(url, "抓取失败：${t.message ?: "未知错误"}")
        }
    }

    private fun reject(url: String): WebFetchResult? {
        val trimmed = url.trim()
        if (DeepSeekTools.codePointCount(trimmed) > DeepSeekTools.MAX_URL_CHARS) {
            return failure(trimmed, "URL 超过 ${DeepSeekTools.MAX_URL_CHARS} 个字符")
        }
        val httpUrl = runCatching { trimmed.toHttpUrlOrNull() }.getOrNull()
            ?: return failure(trimmed, "只支持 http(s) URL")
        if (httpUrl.scheme != "http" && httpUrl.scheme != "https") {
            return failure(trimmed, "只支持 http(s) URL")
        }
        if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) {
            return failure(trimmed, "URL 不能包含用户名或密码")
        }
        if (httpUrl.host.isBlank() || !hostPolicy(httpUrl.host)) {
            return failure(trimmed, "拒绝访问非公网地址")
        }
        return null
    }

    private fun failure(url: String, message: String) =
        WebFetchResult(url = url, statusCode = 0, text = message, truncated = false)

    companion object {
        private const val MAX_RESPONSE_BYTES = 2_000_000

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
