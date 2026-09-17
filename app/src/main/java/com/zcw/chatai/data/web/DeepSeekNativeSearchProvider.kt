package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.model.ToolSource
import com.zcw.chatai.data.net.ApiErrorMapper
import com.zcw.chatai.data.net.ChatApiException
import com.zcw.chatai.data.net.EndpointUrl
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 解析 DeepSeek Anthropic Messages 响应里的 `web_search_tool_result`。纯函数，JVM 可测。 */
object DeepSeekSearchParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val FULLWIDTH_BAR = '\uFF5C'

    fun parse(raw: String): WebSearchResult {
        val response = try {
            json.decodeFromString(AnthropicResponse.serializer(), raw)
        } catch (t: Exception) {
            return WebSearchResult()
        }
        val answerParts = mutableListOf<String>()
        val sources = mutableListOf<ToolSource>()
        val seen = HashSet<String>()
        response.content.forEach { block ->
            when (block.type) {
                "text" -> block.text?.takeIf { it.isNotBlank() }?.let { answerParts += it }
                "web_search_tool_result" -> {
                    val items = block.content as? JsonArray ?: return@forEach
                    items.forEach { item ->
                        val obj = item as? JsonObject ?: return@forEach
                        if (obj["type"]?.jsonPrimitive?.contentOrNull != "web_search_result") return@forEach
                        val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (url.isBlank() || !seen.add(url)) return@forEach
                        val title = obj["title"]?.jsonPrimitive?.contentOrNull
                        val age = obj["page_age"]?.jsonPrimitive?.contentOrNull
                        sources += ToolSource(url = url, title = title, publishedAt = age)
                    }
                }
            }
        }
        val answer = stripDsmlMarkup(answerParts.joinToString("\n")).takeIf { it.isNotBlank() }
        return WebSearchResult(answer = answer, sources = sources)
    }

    /** 参见设计：Anthropic 端点已知会把原生工具标记漏进正文，这里兜底剥离。 */
    fun stripDsmlMarkup(text: String): String {
        var result = text
        val open = "<${FULLWIDTH_BAR}${FULLWIDTH_BAR}DSML${FULLWIDTH_BAR}${FULLWIDTH_BAR}"
        // 结束标记形如 `<｜｜/DSML｜｜tool_calls>`（斜杠在 DSML 之前），不是 `<｜｜DSML｜｜/...`。
        val closeMarker = "<${FULLWIDTH_BAR}${FULLWIDTH_BAR}/DSML${FULLWIDTH_BAR}${FULLWIDTH_BAR}"
        while (true) {
            val start = result.indexOf(open)
            if (start < 0) break
            val close = result.indexOf(">", result.indexOf(closeMarker, start).takeIf { it >= 0 } ?: break)
            if (close < 0) break
            result = result.removeRange(start, close + 1)
        }
        // 清掉残留的孤立 DSML 标签（没有配对结束标记的情况）。
        var cleaned = result
        while (true) {
            val start = cleaned.indexOf(open)
            if (start < 0) break
            val gt = cleaned.indexOf('>', start)
            if (gt < 0) break
            cleaned = cleaned.removeRange(start, gt + 1)
        }
        // 再清掉孤立的结束标记（只有 close、没有 open 的残段）。
        while (true) {
            val start = cleaned.indexOf(closeMarker)
            if (start < 0) break
            val gt = cleaned.indexOf('>', start)
            if (gt < 0) break
            cleaned = cleaned.removeRange(start, gt + 1)
        }
        return collapseBlankLines(cleaned).trim()
    }

    private fun collapseBlankLines(text: String): String {
        val builder = StringBuilder()
        var newlineCount = 0
        text.forEach { ch ->
            if (ch == '\n') {
                newlineCount++
                if (newlineCount <= 2) builder.append(ch)
            } else {
                newlineCount = 0
                builder.append(ch)
            }
        }
        return builder.toString()
    }
}

/**
 * 默认搜索后端：调用 DeepSeek 的 Anthropic 兼容面，用服务端 `web_search` 工具。
 * OpenAI 兼容面不支持该工具类型，只有这条路；客户端只发一次普通 HTTPS 请求。
 */
class DeepSeekNativeSearchProvider(
    private val client: OkHttpClient = defaultClient(),
) : WebSearchProvider {

    override val id: String = "deepseek-native"

    override fun available(baseUrl: String, apiKey: String): Boolean =
        apiKey.isNotBlank() && EndpointUrl.anthropicMessages(baseUrl) != null

    override suspend fun search(query: String, maxResults: Int, config: ChatConfig): WebSearchResult =
        withContext(Dispatchers.IO) {
            val url = EndpointUrl.anthropicMessages(config.baseUrl)
                ?: throw ChatApiException("请先在设置中填写 Base URL")
            val payload = buildJsonObject {
                put("model", config.model)
                put("max_tokens", 2048)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", "Perform a web search for the query: $query")
                                    },
                                )
                            }
                        },
                    )
                }
                putJsonArray("tools") {
                    add(
                        buildJsonObject {
                            put("type", "web_search_20250305")
                            put("name", "web_search")
                            put("max_uses", maxResults.coerceIn(1, 5))
                        },
                    )
                }
                // 强制只调搜索：既保证真的联网，也规避已知的 DSML 标记漏出。
                putJsonObject("tool_choice") {
                    put("type", "tool")
                    put("name", "web_search")
                }
            }
            val request = Request.Builder()
                .url(url)
                .header("x-api-key", config.apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful) {
                        throw ChatApiException(ApiErrorMapper.httpError(response.code, errorMessage(body)))
                    }
                    DeepSeekSearchParser.parse(body)
                }
            } catch (e: ChatApiException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消必须原样向上传播，不能被包装成普通失败。
                throw e
            } catch (t: Exception) {
                throw ChatApiException("联网搜索失败：${t.message ?: "未知错误"}", t)
            }
        }

    private fun errorMessage(body: String): String? = try {
        val error = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(body)
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("error")
            ?.let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("message")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        error
    } catch (t: Exception) {
        null
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}

@Serializable
private data class AnthropicResponse(val content: List<AnthropicBlock> = emptyList())

@Serializable
private data class AnthropicBlock(
    val type: String = "",
    val text: String? = null,
    val content: kotlinx.serialization.json.JsonElement? = null,
)
