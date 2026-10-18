package com.zcw.chatai.data.web.deepseek

import com.zcw.chatai.data.model.ToolSource
import com.zcw.chatai.data.net.EndpointUrl
import com.zcw.chatai.data.net.OpenAiCompatibleChatApi
import com.zcw.chatai.data.net.TransientNetwork
import com.zcw.chatai.data.web.HttpBody
import com.zcw.chatai.data.web.WebSearchResult
import com.zcw.chatai.data.web.awaitBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DeepSeek 官方 Search / Fetch（`POST {origin}/v1/tools/search|fetch`）。
 * Query 上限 128 个码位，URL 上限 8192 个码位。超长搜索词直接拒绝，不截断后另搜。
 */
object DeepSeekTools {

    const val MAX_QUERY_CHARS = 128
    const val MAX_URL_CHARS = 8192

    private val jsonMedia = "application/json".toMediaType()

    fun searchUrl(baseUrl: String): String? {
        val origin = EndpointUrl.originOf(baseUrl) ?: return null
        return "$origin/v1/tools/search"
    }

    fun fetchUrl(baseUrl: String): String? {
        val origin = EndpointUrl.originOf(baseUrl) ?: return null
        return "$origin/v1/tools/fetch"
    }

    fun codePointCount(text: String): Int = text.codePointCount(0, text.length)

    /** Bearer POST。4xx/5xx 原样返回，由调用方决定抛错还是写成抓取结果。传输失败才重试。 */
    internal suspend fun post(
        client: OkHttpClient,
        endpoint: String,
        apiKey: String,
        payload: JsonObject,
        maxBytes: Int,
    ): HttpBody {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("User-Agent", OpenAiCompatibleChatApi.USER_AGENT)
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        return TransientNetwork.retry { client.awaitBody(request, maxBytes) }
    }
}

/** 解析官方 Search / Fetch 的 JSON。形状不对时搜索退回空结果，抓取返回 null。 */
object DeepSeekToolsParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseSearch(raw: String): WebSearchResult {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return WebSearchResult()
        val results = runCatching { root["results"]?.jsonArray }.getOrNull() ?: return WebSearchResult()
        val sources = mutableListOf<ToolSource>()
        val seen = HashSet<String>()
        results.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val url = item.text("url") ?: return@forEach
            if (!seen.add(url)) return@forEach
            sources += ToolSource(
                url = url,
                title = item.text("title"),
                snippet = item.text("summary") ?: item.text("snippet"),
                publishedAt = item.text("date"),
            )
        }
        return WebSearchResult(sources = sources)
    }

    /** OpenAI 形状错误体里的 `error.message`。没有或解析失败返回 null。 */
    fun errorMessage(body: String): String? = try {
        json.parseToJsonElement(body)
            .let { it as? JsonObject }
            ?.get("error")
            ?.let { it as? JsonObject }
            ?.get("message")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
    } catch (t: Exception) {
        null
    }

    /** 成功体的标题与 Markdown 正文。没有 `content` 字段时返回 null。 */
    fun parseFetch(raw: String): DeepSeekFetchedPage? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        if (!root.containsKey("content")) return null
        val content = root.text("content").orEmpty()
        return DeepSeekFetchedPage(title = root.text("title").orEmpty(), content = content)
    }

    private fun JsonObject.text(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
}

data class DeepSeekFetchedPage(val title: String, val content: String)
