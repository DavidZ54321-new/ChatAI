package com.zcw.chatai.data.web.mimo

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.ApiErrorMapper
import com.zcw.chatai.data.net.ChatApiException
import com.zcw.chatai.data.net.EndpointUrl
import com.zcw.chatai.data.net.OpenAiCompatibleChatApi
import com.zcw.chatai.data.net.TransientNetwork
import com.zcw.chatai.data.web.WebSearchProvider
import com.zcw.chatai.data.web.WebSearchResult
import com.zcw.chatai.data.web.awaitBody
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * MiMo 官方 `web_search` 插件（Chat Completions 面专用）。
 *
 * 适配方式是**借道旁路**：主回路里模型照常调标准 `web_search` function，
 * 这里另发一次带服务端插件的请求，把 `message.annotations`（url_citation）
 * 解析成 [com.zcw.chatai.data.model.ToolSource] 列表回填。
 * 任意会话供应商都能借 MiMo 搜索；插件需在 MiMo 控制台启用（5 分钟缓存）。
 */
class MiMoWebSearchProvider(
    private val client: OkHttpClient = defaultClient(),
) : WebSearchProvider {

    override val id: String = "mimo-web-search-plugin"

    override fun available(baseUrl: String, apiKey: String): Boolean =
        apiKey.isNotBlank() && EndpointUrl.chatCompletions(baseUrl) != null

    override suspend fun search(query: String, maxResults: Int, config: ChatConfig): WebSearchResult {
        val url = EndpointUrl.chatCompletions(config.baseUrl)
            ?: throw ChatApiException("请先在设置中填写 Base URL")
        val payload = buildJsonObject {
            put("model", config.model)
            put("stream", false)
            // MiMo 只接受 tool_choice=auto（其余值被服务端剥掉）；保证联网靠 force_search。
            put("tool_choice", "auto")
            // 纯搜索不需要思维链：关掉明显更快（同 Qwen enable_thinking:false 的做法）。
            putJsonObject("thinking") { put("type", "disabled") }
            putJsonArray("tools") {
                add(
                    buildJsonObject {
                        put("type", "web_search")
                        // 一轮最多并行 3 个关键词，控成本；force_search 保证模型必搜。
                        put("max_keyword", 3)
                        put("force_search", true)
                    },
                )
            }
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", "请联网搜索并用中文简要总结关键事实。\n\n搜索内容：$query")
                    },
                )
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("content-type", "application/json")
            .header("User-Agent", OpenAiCompatibleChatApi.USER_AGENT)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            val http = TransientNetwork.retry { client.awaitBody(request, MAX_RESPONSE_BYTES) }
            if (http.code !in 200..299) {
                throw ChatApiException(ApiErrorMapper.httpError(http.code, errorMessage(http.text)))
            }
            MiMoSearchParser.parse(http.text, maxResults)
        } catch (e: ChatApiException) {
            throw e
        } catch (e: CancellationException) {
            // 协程取消必须原样向上传播，不能被包装成普通失败。
            throw e
        } catch (t: Exception) {
            throw ChatApiException("联网搜索失败：${t.message ?: "未知错误"}", t)
        }
    }

    private fun errorMessage(body: String): String? = try {
        Json { ignoreUnknownKeys = true }
            .parseToJsonElement(body)
            .let { it as? JsonObject }
            ?.get("error")
            ?.let { it as? JsonObject }
            ?.get("message")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
    } catch (t: Exception) {
        null
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** 搜索响应上限：远超正常搜索结果，防止异常端点拖垮内存。 */
        private const val MAX_RESPONSE_BYTES = 2_000_000

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
