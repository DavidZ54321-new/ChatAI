package com.zcw.chatai.data.net.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

val chatJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<RequestMessage>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
)

@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = true)

/** `content` 是联合类型：纯文本为 JSON 字符串，带图片时为内容块数组。 */
@Serializable
data class RequestMessage(val role: String, val content: JsonElement)

@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
    @SerialName("completion_tokens_details") val completionTokensDetails: CompletionTokensDetails? = null,
) {
    /** 上下文缓存命中的输入 token（OpenAI 用 details，DeepSeek 两个都给）。 */
    val cachedTokens: Int?
        get() = promptTokensDetails?.cachedTokens ?: promptCacheHitTokens

    /** 思维链 token（计入输出计费）。 */
    val reasoningTokens: Int?
        get() = completionTokensDetails?.reasoningTokens
}

@Serializable
data class PromptTokensDetails(@SerialName("cached_tokens") val cachedTokens: Int? = null)

@Serializable
data class CompletionTokensDetails(@SerialName("reasoning_tokens") val reasoningTokens: Int? = null)

@Serializable
data class ModelList(val data: List<ModelInfo> = emptyList())

@Serializable
data class ModelInfo(val id: String = "", @SerialName("owned_by") val ownedBy: String? = null)

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorPayload)

@Serializable
data class ApiErrorPayload(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
