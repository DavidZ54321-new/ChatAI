package com.zcw.chatai.data.web

import com.zcw.chatai.data.net.TransientNetwork
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 本地 HTTP 抓取。非 2xx 也是结果；只接受 http(s) 与公网地址，关闭自动重定向，
 * 限制大小与类型，HTML 转纯文本。
 *
 * [hostPolicy] 可注入以便单测（MockWebServer 跑在 localhost）。
 */
class HttpWebFetcher(
    private val client: OkHttpClient = defaultClient(),
    private val hostPolicy: (String) -> Boolean = ::isPublicHost,
    private val maxBytes: Int = 200_000,
    private val allowedTypes: Set<String> = DEFAULT_ALLOWED_TYPES,
) : WebFetcher {

    override suspend fun fetch(url: String): WebFetchResult = withContext(Dispatchers.IO) {
        val httpUrl = runCatching { url.trim().toHttpUrlOrNull() }.getOrNull()
            ?: return@withContext failure(url, "只支持 http(s) URL")
        if (httpUrl.scheme != "http" && httpUrl.scheme != "https") {
            return@withContext failure(url, "只支持 http(s) URL")
        }
        if (!hostPolicy(httpUrl.host)) {
            return@withContext failure(url, "拒绝访问非公网地址")
        }
        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        try {
            val http = TransientNetwork.retry { client.awaitBody(request, maxBytes) }
            val contentType = http.contentType.orEmpty().substringBefore(';').trim().lowercase()
            if (contentType.isNotEmpty() && contentType !in allowedTypes) {
                return@withContext failure(url, "不支持的内容类型：$contentType")
            }
            val truncated = http.contentLength > http.bytes.size
            val raw = http.text
            val text = if (contentType == "text/html" || contentType == "application/xhtml+xml" || contentType.isEmpty()) {
                HtmlToText.toText(raw)
            } else {
                raw
            }
            WebFetchResult(url = httpUrl.toString(), statusCode = http.code, text = text, truncated = truncated)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Exception) {
            failure(url, "抓取失败：${t.message ?: "未知错误"}")
        }
    }

    private fun failure(url: String, message: String) =
        WebFetchResult(url = url, statusCode = 0, text = message, truncated = false)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        private val DEFAULT_ALLOWED_TYPES = setOf(
            "text/html", "text/plain", "application/json", "application/xhtml+xml", "text/markdown",
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        /** 拒绝 loopback / 私网 / link-local / multicast。 */
        fun isPublicHost(host: String): Boolean = try {
            val addresses = InetAddress.getAllByName(host)
            addresses.isNotEmpty() && addresses.all { address ->
                !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress &&
                    !address.isSiteLocalAddress &&
                    !address.isAnyLocalAddress &&
                    !address.isMulticastAddress
            }
        } catch (t: Exception) {
            false
        }
    }
}
