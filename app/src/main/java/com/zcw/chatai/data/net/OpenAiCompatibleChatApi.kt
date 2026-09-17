package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.dto.ApiErrorEnvelope
import com.zcw.chatai.data.net.dto.ChatCompletionChunk
import com.zcw.chatai.data.net.dto.ChatCompletionRequest
import com.zcw.chatai.data.net.dto.ChatRequestBody
import com.zcw.chatai.data.net.dto.ModelList
import com.zcw.chatai.data.net.dto.RequestMessage
import com.zcw.chatai.data.net.dto.StreamOptions
import com.zcw.chatai.data.net.dto.chatJson
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
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
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
            .apply {
                if (config.apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    val message = try {
                        json.decodeFromString(ApiErrorEnvelope.serializer(), body).error.message
                    } catch (t: Exception) {
                        null
                    }
                    throw ChatApiException(ApiErrorMapper.httpError(response.code, message ?: body))
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
        val request = try {
            buildRequest(url, config, messages)
        } catch (t: Exception) {
            close(ChatApiException("Base URL 无效：$url", t))
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
        url: String,
        config: ChatConfig,
        messages: List<ChatRequestMessage>,
    ): Request {
        val payload = ChatCompletionRequest(
            model = config.model,
            messages = buildList {
                if (config.systemPrompt.isNotEmpty()) {
                    add(RequestMessage("system", ChatRequestBody.content(config.systemPrompt, emptyList())))
                }
                messages.forEach { message ->
                    // 图片只能出现在 user/tool 消息里，其它角色一律降级为纯文本。
                    val images = if (message.role == ROLE_USER) message.images else emptyList()
                    add(RequestMessage(message.role, ChatRequestBody.content(message.content, images)))
                }
            },
            temperature = config.temperature,
            reasoningEffort = config.reasoningEffort?.takeIf { it.isNotBlank() },
            maxTokens = config.maxTokens,
            streamOptions = if (config.includeUsage) StreamOptions(includeUsage = true) else null,
        )
        val body = ChatRequestBody.encode(json, payload, config.extraParams)
        return Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .apply {
                if (config.apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer ${config.apiKey}")
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
        val apiMessage = body?.let { text ->
            try {
                json.decodeFromString(ApiErrorEnvelope.serializer(), text).error.message
            } catch (t: Exception) {
                null
            }
        }
        val detail = apiMessage?.takeIf { it.isNotBlank() } ?: body
        return ApiErrorMapper.httpError(response.code, detail)
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
        private const val DONE_DATA = "[DONE]"
        private val JSON_MEDIA_TYPE: MediaType = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
