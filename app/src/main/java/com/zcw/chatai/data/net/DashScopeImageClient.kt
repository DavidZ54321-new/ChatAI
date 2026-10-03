package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.web.awaitBody
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DashScope 千问图像生成/编辑（**同步**）客户端。
 *
 * 一个端点同时服务文生图与图像编辑：请求体里没有 `image` 块就是文生图。
 * 响应只给图片 URL（24 小时有效），所以 [download] 负责把结果取回本地字节。
 *
 * 鉴权用「通义千问」供应商条目里的 API Key；端点由该条目的 Chat Base URL
 * **同源推导**（见 `EndpointUrl.dashScopeImageGeneration`），不需要 workspace 域名。
 */
class DashScopeImageClient(
    private val client: OkHttpClient = defaultClient(),
) {

    /** 提交一次生成/编辑，返回生成的图片 URL 列表（保持服务端顺序）。 */
    suspend fun generate(config: ChatConfig, request: QwenImageRequest): List<String> {
        val endpoint = EndpointUrl.dashScopeImageGeneration(config.baseUrl)
            ?: throw ChatApiException("Base URL 无效，无法推导生图端点，请到设置里检查")
        val body = QwenImagePayload.build(request).toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("User-Agent", OpenAiCompatibleChatApi.USER_AGENT)
            .apply {
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .post(body)
            .build()

        val http = try {
            TransientNetwork.retry { client.awaitBody(httpRequest, MAX_RESPONSE_BYTES) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            throw ChatApiException("生图请求失败：${t.message ?: "网络异常"}", t)
        }

        val outcome = QwenImageParser.parse(http.text)
        if (http.code !in 200..299) {
            throw ChatApiException(
                when (outcome) {
                    is QwenImageOutcome.Failure -> ApiErrorMapper.dashScope(outcome.code, outcome.message)
                    else -> ApiErrorMapper.httpError(http.code, http.text)
                },
            )
        }
        return when (outcome) {
            is QwenImageOutcome.Success -> outcome.urls
            is QwenImageOutcome.Failure -> throw ChatApiException(ApiErrorMapper.dashScope(outcome.code, outcome.message))
            QwenImageOutcome.Malformed -> throw ChatApiException("生图响应解析失败，请稍后重试")
        }
    }

    /** 下载生成图（返回值上限 [maxBytes]；结果 URL 24 小时失效，必须即时落盘）。 */
    suspend fun download(url: String, maxBytes: Int = MAX_IMAGE_BYTES): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", OpenAiCompatibleChatApi.USER_AGENT)
            .get()
            .build()
        val http = try {
            TransientNetwork.retry { client.awaitBody(request, maxBytes) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            throw ChatApiException("生成图下载失败：${t.message ?: "网络异常"}", t)
        }
        if (http.code !in 200..299) {
            throw ChatApiException("生成图下载失败（HTTP ${http.code}）")
        }
        if (http.bytes.isEmpty()) throw ChatApiException("生成图下载失败：内容为空")
        return http.bytes
    }

    /** GET 一段 JSON（模型列表等）。非 2xx 抛 [ChatApiException]，调用方决定是否保留已拿到的页。 */
    suspend fun getText(url: String, apiKey: String, maxBytes: Int = MAX_RESPONSE_BYTES): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", OpenAiCompatibleChatApi.USER_AGENT)
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }
            .get()
            .build()
        val http = try {
            TransientNetwork.retry { client.awaitBody(request, maxBytes) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            throw ChatApiException("请求失败：${t.message ?: "网络异常"}", t)
        }
        if (http.code !in 200..299) {
            throw ChatApiException(ApiErrorMapper.httpError(http.code, http.text.take(300)))
        }
        return http.text
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val MAX_RESPONSE_BYTES = 2_000_000
        private const val MAX_IMAGE_BYTES = 24 * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 生图较慢（尤其带 prompt_extend / 多图输入）：读超时给足，但不设 callTimeout
            //（由上层协程取消来控制，`awaitBody` 会随之 cancel socket）。
            .readTimeout(300, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
