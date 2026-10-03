package com.zcw.chatai.data.net

import com.zcw.chatai.data.image.openai.OpenAiImageOptions
import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.web.awaitBody
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Images API（同步）。
 *
 * 无参考图 `POST /images/generations`，有参考图 `POST /images/edits`。
 * 端点挂在供应商 Chat Base 的 `/v1` 根上。响应是 base64，不再二次下载。
 */
class OpenAiImageClient(
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun generate(
        config: ChatConfig,
        model: String,
        prompt: String,
        images: List<String>,
        options: OpenAiImageOptions = OpenAiImageOptions(),
    ): List<ByteArray> = withContext(Dispatchers.IO) {
        val root = EndpointUrl.compatV1Root(config.baseUrl)
            ?: throw ChatApiException("Base URL 无效，无法推导生图端点，请到设置里检查")
        val editing = images.isNotEmpty()
        val path = if (editing) "/images/edits" else "/images/generations"
        val payload = if (editing) {
            OpenAiImagePayload.edits(model, prompt, images, options)
        } else {
            OpenAiImagePayload.generations(model, prompt, options)
        }
        val http = post(root + path, config.apiKey, payload)
        val outcome = OpenAiImageParser.parse(http.text)
        if (http.code !in 200..299) {
            val detail = (outcome as? OpenAiImageOutcome.Failure)?.message
            throw ChatApiException(ApiErrorMapper.httpError(http.code, detail ?: http.text.take(300)))
        }
        when (outcome) {
            is OpenAiImageOutcome.Success -> outcome.images
            is OpenAiImageOutcome.Failure ->
                throw ChatApiException(outcome.message ?: "服务端未返回图片")
            OpenAiImageOutcome.Malformed -> throw ChatApiException("生图响应解析失败，请稍后重试")
        }
    }

    private suspend fun post(url: String, apiKey: String, payload: JsonObject) = try {
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", OpenAiCompatibleChatApi.USER_AGENT)
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }
            .post(body)
            .build()
        TransientNetwork.retry { client.awaitBody(request, MAX_RESPONSE_BYTES) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        throw ChatApiException("生图请求失败：${t.message ?: "网络异常"}", t)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** GPT Image 的 `b64_json` 比千问那条 URL JSON 大得多，2MB 会把图截断。 */
        private const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
