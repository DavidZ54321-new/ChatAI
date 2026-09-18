package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.web.awaitBody
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Qwen（DashScope）Responses API 的最小客户端：POST 一个 JSON、拿回完整 JSON 文本。
 *
 * 只服务**侧信道工具调用**（联网搜索/文搜图/图搜图），主对话回路仍是 Chat Completions。
 * 工具调用天然慢（实测 web_search 一轮 ~86s），超时给得宽；响应体走 [awaitBody] 循环读到 EOF，
 * 不发生「长 JSON 从中间截断」的静默失败。
 */
class QwenResponsesClient(
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun execute(config: ChatConfig, payload: JsonObject): String {
        val url = EndpointUrl.responses(config.baseUrl)
            ?: throw ChatApiException("请先在设置中填写 Base URL")
        val httpUrl = url.toHttpUrlOrNull()
            ?: throw ChatApiException("Base URL 无效：$url")
        val request = Request.Builder()
            .url(httpUrl)
            .header("Accept", "application/json")
            .apply {
                if (config.apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            val http = TransientNetwork.retry { client.awaitBody(request, MAX_RESPONSE_BYTES) }
            if (http.code !in 200..299) {
                throw ChatApiException(ApiErrorMapper.httpError(http.code, errorMessage(http.text)))
            }
            http.text
        } catch (e: ChatApiException) {
            throw e
        } catch (e: CancellationException) {
            // 协程取消必须原样向上传播，不能被包装成普通失败。
            throw e
        } catch (t: Exception) {
            throw ChatApiException("Responses 请求失败：${t.message ?: "未知错误"}", t)
        }
    }

    private fun errorMessage(body: String): String? = try {
        val root = Json { ignoreUnknownKeys = true; isLenient = true }
            .parseToJsonElement(body)
            .jsonObject
        root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: root["message"]?.jsonPrimitive?.contentOrNull
    } catch (t: Exception) {
        null
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** 响应上限：远超正常画像结果，防止异常端点拖垮内存。 */
        private const val MAX_RESPONSE_BYTES = 4_000_000

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(200, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
