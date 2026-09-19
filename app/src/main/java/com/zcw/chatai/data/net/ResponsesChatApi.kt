package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.dto.ApiErrorEnvelope
import com.zcw.chatai.data.net.dto.ModelList
import com.zcw.chatai.data.net.dto.chatJson
import com.zcw.chatai.data.web.WebTools
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Responses 协议的主对话实现（OpenCode Go 上 Luna/Grok/Muse 的兜底面）。
 *
 * 只在 chat/completions 报"端点不可用"类错误时由 Failover 装饰器启用，
 * 对上层（`ChatRepository` / UI / 落库）暴露的仍是同一套 [ChatStreamEvent]：
 * 文本/思考增量、工具增量、用量、结束原因——协议差异到这里为止。
 *
 * 实测结论（2026-09 真网关，`grok-4.6`，见 Phase0 探针）：
 * - 具名 SSE：`response.output_text.delta` / `response.reasoning_summary_text.delta` /
 *   `response.function_call_arguments.delta`，以 `response.completed` 收尾（没有 `[DONE]`）；
 * - `function_call` 项带 `call_id`（不是 item id），`call_id` 才是下一轮
 *   `function_call_output` 配对的键，所以 `ToolCall.id` 必须用它；
 * - `reasoning: {effort:"none"}` 直接 400，**任何 reasoning 参数都不发**，用服务端默认；
 * - `tools` 接受标准 function 信封，`tool_choice: "auto"` 有效；
 * - `usage` 在 `response.completed.response.usage`，含 `reasoning_tokens` / `cached_tokens`。
 *
 * 另外三个**故意不发**：`max_tokens`（思考会吃光额度，和 chat 面同一个坑）、
 * `extraParams`（键都是 chat 形状，盲合并进 Responses 会 400）、`stream_options`
 * （Responses 一直给 usage，不需要）。
 */
class ResponsesChatApi(
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
        // Responses 面没有模型列表端点：复用同一根的标准 `/models`（与 chat 面共用）。
        val url = EndpointUrl.models(config.baseUrl)
            ?: throw ChatApiException("请先在设置中填写 Base URL")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", OpenAiCompatibleChatApi.USER_AGENT)
            .apply {
                if (config.apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
                if (config.sendSessionHeader) {
                    header("x-opencode-session", config.sessionId?.takeIf { it.isNotBlank() } ?: OpenAiCompatibleChatApi.FALLBACK_SESSION)
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
        val url = EndpointUrl.responses(config.responsesBaseUrl.ifBlank { config.baseUrl })
        if (url == null) {
            close(ChatApiException("请先在设置中填写 Base URL"))
            return null
        }
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl == null) {
            close(ChatApiException("Base URL 无效：$url"))
            return null
        }
        val input = when (val translated = ResponsesHistory.translate(config.systemPrompt, messages)) {
            is ResponsesHistory.Result.Ok -> translated.input
            ResponsesHistory.Result.HasVideo ->
                run {
                    close(ChatApiException("该模型在备用线路上不支持视频输入，请换回主线路模型或移除视频"))
                    return null
                }
        }
        val request = try {
            buildRequest(httpUrl, config, input)
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

    private fun buildRequest(url: okhttp3.HttpUrl, config: ChatConfig, input: JsonArray): Request {
        val tools = WebTools.specsFor(config.enabledTools)
        val payload = buildJsonObject {
            put("model", config.model)
            put("input", input)
            put("stream", true)
            config.temperature?.let { put("temperature", it) }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("type", "function")
                                put("name", tool.function.name)
                                put("description", tool.function.description)
                                put("parameters", tool.function.parameters)
                            },
                        )
                    }
                }
                put("tool_choice", "auto")
            }
        }
        return Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("User-Agent", OpenAiCompatibleChatApi.USER_AGENT)
            .apply {
                if (config.apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
                if (config.sendSessionHeader) {
                    header("x-api-key", config.apiKey)
                    header("x-opencode-session", config.sessionId?.takeIf { it.isNotBlank() } ?: OpenAiCompatibleChatApi.FALLBACK_SESSION)
                }
            }
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun errorMessage(body: String?): String? {
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
        private var hasFunctionCall = false
        /** 已收到过增量的条目下标：这些以增量为准，`done` 项里的全量不再补。 */
        private val argsFromDelta = HashSet<Int>()
        private val textFromDelta = HashSet<Int>()

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            val payload = try {
                json.parseToJsonElement(data).jsonObject
            } catch (t: Exception) {
                return
            }
            when (type ?: payload.string("type")) {
                "response.output_text.delta" ->
                    payload.string("delta")?.takeIf { it.isNotEmpty() }?.let {
                        textFromDelta += payload.int("output_index")
                        scope.trySend(ChatStreamEvent.Delta(content = it))
                    }

                "response.reasoning_summary_text.delta" ->
                    payload.string("delta")?.takeIf { it.isNotEmpty() }?.let {
                        scope.trySend(ChatStreamEvent.Delta(reasoning = it))
                    }

                "response.output_item.added" -> {
                    val item = payload["item"] as? JsonObject ?: return
                    if (item.string("type") != "function_call") return
                    hasFunctionCall = true
                    scope.trySend(
                        ChatStreamEvent.ToolCallDelta(
                            index = payload.int("output_index"),
                            id = item.string("call_id"),
                            name = item.string("name"),
                        ),
                    )
                }

                // 注意：这里只拼 arguments，不带 id——增量里的 item_id 是条目 id，
                // 会覆盖 output_item.added 里记下的配对用 call_id（见类注释）。
                "response.function_call_arguments.delta" ->
                    payload.string("delta")?.takeIf { it.isNotEmpty() }?.let {
                        val index = payload.int("output_index")
                        argsFromDelta += index
                        scope.trySend(
                            ChatStreamEvent.ToolCallDelta(
                                index = index,
                                arguments = it,
                            ),
                        )
                    }

                // 网关可能把参数合并进 done 项而不发（或只发截断的）增量：
                // 没见过增量的下标才用全量补，有增量的以增量为准（防 "{}{}" 重复拼接）。
                "response.output_item.done" -> {
                    val item = payload["item"] as? JsonObject ?: return
                    val index = payload.int("output_index")
                    when (item.string("type")) {
                        "function_call" ->
                            if (index !in argsFromDelta) {
                                item.string("arguments")?.takeIf { it.isNotEmpty() }?.let {
                                    scope.trySend(
                                        ChatStreamEvent.ToolCallDelta(
                                            index = index,
                                            arguments = it,
                                        ),
                                    )
                                }
                            }

                        "message" ->
                            if (index !in textFromDelta) {
                                val parts = item["content"] as? JsonArray ?: return
                                val text = parts.mapNotNull { (it as? JsonObject)?.string("text") }
                                    .joinToString("")
                                if (text.isNotEmpty()) scope.trySend(ChatStreamEvent.Delta(content = text))
                            }
                    }
                }

                "response.completed" -> {
                    val response = payload["response"] as? JsonObject
                    val usage = response?.get("usage") as? JsonObject
                    usage?.let { scope.trySend(it.toUsage()) }
                    val status = response?.string("status")
                    scope.trySend(
                        ChatStreamEvent.Finished(
                            when {
                                hasFunctionCall -> "tool_calls"
                                status == "completed" -> "stop"
                                else -> status
                            },
                        ),
                    )
                    complete()
                }

                "error" ->
                    fail(payload.string("message") ?: "服务端出错，请稍后重试")
            }
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            if (completed) return
            if (response != null) {
                val body = try {
                    response.body.string()
                } catch (e: Exception) {
                    null
                }
                fail(ApiErrorMapper.httpError(response.code, errorMessage(body) ?: body))
            } else {
                fail(ChatApiException("网络异常：${t?.message ?: "未知错误"}", t))
            }
        }

        override fun onClosed(eventSource: EventSource) {
            // completed 后服务端的正常关流：complete() 已经 close 过，这里直接无视。
            // 没收到 completed 就关流 = 中途掐断，必须报错，不能静默吞成"正常结束"。
            if (!completed) fail(ChatApiException("连接中断，请重试"))
        }

        private fun JsonObject.toUsage(): ChatStreamEvent.Usage {
            val reasoning = (this["output_tokens_details"] as? JsonObject)?.intOrNull("reasoning_tokens")
            val cached = (this["input_tokens_details"] as? JsonObject)?.intOrNull("cached_tokens")
            return ChatStreamEvent.Usage(
                promptTokens = intOrNull("input_tokens"),
                completionTokens = intOrNull("output_tokens"),
                reasoningTokens = reasoning,
                cachedTokens = cached,
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.int(key: String): Int =
            (this[key] as? JsonPrimitive)?.intOrNull ?: 0

        private fun JsonObject.intOrNull(key: String): Int? =
            (this[key] as? JsonPrimitive)?.intOrNull

        private fun fail(error: ChatApiException) {
            if (completed) return
            completed = true
            scope.close(error)
        }

        private fun fail(message: String) = fail(ChatApiException(message))

        private fun complete() {
            if (completed) return
            completed = true
            scope.trySend(ChatStreamEvent.Completed)
            scope.close()
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
