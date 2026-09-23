package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.dto.ApiErrorEnvelope
import com.zcw.chatai.data.net.dto.ChatCompletionChunk
import com.zcw.chatai.data.net.dto.ChatCompletionRequest
import com.zcw.chatai.data.net.dto.ChatRequestBody
import com.zcw.chatai.data.net.dto.ModelList
import com.zcw.chatai.data.net.dto.RequestFunctionCall
import com.zcw.chatai.data.net.dto.RequestMessage
import com.zcw.chatai.data.net.dto.RequestToolCall
import com.zcw.chatai.data.net.dto.StreamOptions
import com.zcw.chatai.data.net.dto.chatJson
import com.zcw.chatai.data.web.WebTools
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class OpenAiCompatibleChatApi(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = chatJson,
) : ChatApi {

    private val eventSourceFactory = EventSources.createFactory(client)

    override fun stream(config: ChatConfig, messages: List<ChatRequestMessage>): Flow<ChatStreamEvent> =
        callbackFlow {
            val eventSource = startStream(config, messages)
            awaitClose { eventSource?.cancel() }
        }.buffer(Channel.UNLIMITED)

    override suspend fun listModels(config: ChatConfig): List<String> = withContext(Dispatchers.IO) {
        val url = EndpointUrl.models(config.baseUrl)
            ?: throw ChatApiException("请先在设置中填写 Base URL")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply {
                if (config.apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
                // Go 之类网关要求稳定会话头，缺了直接 400（实测）。
                if (config.sendSessionHeader) {
                    header("x-opencode-session", config.sessionId?.takeIf { it.isNotBlank() } ?: FALLBACK_SESSION)
                }
            }
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw ChatApiException(
                        ApiErrorMapper.httpError(response.code, errorMessage(body) ?: body),
                    )
                }
                val ids = try {
                    json.decodeFromString(ModelList.serializer(), body).data.map { it.id }
                } catch (t: Exception) {
                    emptyList()
                }
                ids.filter { it.isNotBlank() }
            }
        } catch (e: ChatApiException) {
            throw e
        } catch (t: Exception) {
            throw ChatApiException("连接失败：${t.message ?: "未知错误"}", t)
        }
    }

    private fun ProducerScope<ChatStreamEvent>.startStream(
        config: ChatConfig,
        messages: List<ChatRequestMessage>,
    ): EventSource? {
        val url = EndpointUrl.chatCompletions(config.baseUrl)
        if (url == null) {
            close(ChatApiException("请先在设置中填写 Base URL"))
            return null
        }
        // URL 单独校验：下面的 header/参数错误（例如 API Key 里混进中文）不能再被报成「Base URL 无效」。
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl == null) {
            close(ChatApiException("Base URL 无效：$url"))
            return null
        }
        val request = try {
            buildRequest(httpUrl, config, messages)
        } catch (t: Exception) {
            close(ChatApiException("请求参数无效：${t.message ?: "未知错误"}", t))
            return null
        }
        return try {
            eventSourceFactory.newEventSource(request, StreamListener(this))
        } catch (t: Exception) {
            close(ChatApiException("网络异常：${t.message ?: "未知错误"}", t))
            null
        }
    }

    private fun buildRequest(
        url: HttpUrl,
        config: ChatConfig,
        messages: List<ChatRequestMessage>,
    ): Request {
        val tools = WebTools.specsFor(config.enabledTools)
        val reasoningEffort = config.reasoningEffort?.takeIf { it.isNotBlank() }
        val payload = ChatCompletionRequest(
            model = config.model,
            messages = buildList {
                if (config.systemPrompt.isNotEmpty()) {
                    add(RequestMessage("system", ChatRequestBody.content(config.systemPrompt, emptyList())))
                }
                messages.forEach { message ->
                    // 图片/视频/音频只能出现在 user 消息里，其它角色一律降级为纯文本。
                    val images = if (message.role == ROLE_USER) message.images else emptyList()
                    val videos = if (message.role == ROLE_USER) message.videos else emptyList()
                    val audios = if (message.role == ROLE_USER) message.audios else emptyList()
                    add(
                        RequestMessage(
                            role = message.role,
                            content = if (message.toolCalls.isNotEmpty() && message.content.isEmpty()) {
                                null
                            } else {
                                ChatRequestBody.content(message.content, images, videos, audios)
                            },
                            toolCallId = message.toolCallId,
                            toolCalls = message.toolCalls.takeIf { it.isNotEmpty() }?.map {
                                RequestToolCall(
                                    id = it.id,
                                    function = RequestFunctionCall(name = it.name, arguments = it.arguments),
                                )
                            },
                            reasoningContent = message.reasoning.takeIf { message.role == ROLE_ASSISTANT },
                        ),
                    )
                }
            },
            temperature = config.temperature,
            reasoningEffort = reasoningEffort,
            maxTokens = config.maxTokens,
            streamOptions = if (config.includeUsage) StreamOptions(includeUsage = true) else null,
            tools = tools.takeIf { it.isNotEmpty() },
            toolChoice = if (tools.isNotEmpty()) JsonPrimitive("auto") else null,
        )
        val body = ChatRequestBody.encode(
            json,
            payload,
            config.extraParams,
            thinkingWire = config.thinkingWire,
            reasoningEffort = reasoningEffort,
        )
        val usesOssMedia = messages.any { message -> message.videos.any { it.isOss } }
        return Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("User-Agent", USER_AGENT)
            .apply {
                if (config.apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
                if (config.sendSessionHeader) {
                    header("x-opencode-session", config.sessionId?.takeIf { it.isNotBlank() } ?: FALLBACK_SESSION)
                }
                // 仅当本请求真的带 oss:// 视频时才加解析头，不给普通请求带厂商专有头。
                if (usesOssMedia) {
                    header("X-DashScope-OssResourceResolve", "enable")
                }
            }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun httpErrorMessage(response: Response): String {
        val body = try {
            response.body.string()
        } catch (t: Exception) {
            null
        }
        val detail = body?.let { messageOrNull(it) } ?: body
        return ApiErrorMapper.httpError(response.code, detail)
    }

    /**
     * 错误体里取可读 message：优先标准 `{"error":{"message"}}`，
     * 兜底顶层 `{"message"}`（部分网关的校验错误是这样）。
     */
    private fun errorMessage(body: String): String? = messageOrNull(body)

    private fun messageOrNull(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val envelope = try {
            json.decodeFromString(ApiErrorEnvelope.serializer(), body).error.message
        } catch (t: Exception) {
            null
        }
        if (!envelope.isNullOrBlank()) return envelope
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            (root["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        } catch (t: Exception) {
            null
        }
    }

    private inner class StreamListener(
        private val scope: ProducerScope<ChatStreamEvent>,
    ) : EventSourceListener() {

        private var completed = false

        override fun onEvent(
            eventSource: EventSource,
            id: String?,
            type: String?,
            data: String,
        ) {
            if (data.trim() == DONE_DATA) {
                complete()
                return
            }
            if (data.isBlank()) return
            val chunk = try {
                json.decodeFromString(ChatCompletionChunk.serializer(), data)
            } catch (t: Exception) {
                return
            }
            chunk.choices.forEach { choice ->
                val delta = choice.delta
                val content = delta?.content?.takeIf { it.isNotEmpty() }
                val reasoning = delta?.reasoningContent?.takeIf { it.isNotEmpty() }
                if (content != null || reasoning != null) {
                    scope.trySend(ChatStreamEvent.Delta(content = content, reasoning = reasoning))
                }
                choice.finishReason?.let { reason ->
                    scope.trySend(ChatStreamEvent.Finished(reason))
                }
                delta?.toolCalls?.forEach { tc ->
                    scope.trySend(
                        ChatStreamEvent.ToolCallDelta(
                            index = tc.index,
                            id = tc.id,
                            name = tc.function?.name,
                            arguments = tc.function?.arguments,
                        ),
                    )
                }
            }
            chunk.usage?.let { usage ->
                scope.trySend(
                    ChatStreamEvent.Usage(
                        promptTokens = usage.promptTokens,
                        completionTokens = usage.completionTokens,
                        reasoningTokens = usage.reasoningTokens,
                        cachedTokens = usage.cachedTokens,
                    ),
                )
            }
        }

        override fun onClosed(eventSource: EventSource) {
            complete()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            if (response != null && !response.isSuccessful) {
                scope.close(ChatApiException(httpErrorMessage(response)))
            } else {
                scope.close(ChatApiException("网络异常：${t?.message ?: "未知错误"}", t))
            }
        }

        private fun complete() {
            if (completed) return
            completed = true
            scope.trySend(ChatStreamEvent.Completed)
            scope.close()
        }
    }

    companion object {
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val DONE_DATA = "[DONE]"

        /** 客户端标识（Go 等网关要求客户端自报身份，别用通用 SDK 名）。 */
        const val USER_AGENT = "ChatAI/1.0"

        /** 会话头缺失时的兜底值（模型列表等无会话上下文的请求用）。 */
        const val FALLBACK_SESSION = "chatai"

        private val JSON_MEDIA_TYPE: MediaType = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
