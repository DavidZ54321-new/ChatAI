package com.zcw.chatai.data.net.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
)

@Serializable
data class RequestMessage(val role: String, val content: String)

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
)

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorPayload)

@Serializable
data class ApiErrorPayload(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
