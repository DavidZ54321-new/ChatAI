package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import kotlinx.coroutines.flow.Flow

interface ChatApi {
    fun stream(config: ChatConfig, messages: List<ChatRequestMessage>): Flow<ChatStreamEvent>

    /** 标准 `GET /models`，用于设置页的「测试连接 / 拉取模型列表」。 */
    suspend fun listModels(config: ChatConfig): List<String>
}

/**
 * 一条出站消息。纯文本消息在请求体里仍然编码成 JSON 字符串（最兼容），
 * 只有 [images] 非空时才编码成内容块数组。
 */
data class ChatRequestMessage(
    val role: String,
    val content: String,
    val images: List<ChatRequestImage> = emptyList(),
)

/** 内联图片（`data:image/jpeg;base64,...`）。不用外部 URL：实测多数端点有防盗链。 */
data class ChatRequestImage(
    val dataUrl: String,
    val detail: String? = null,
)

sealed interface ChatStreamEvent {
    data class Delta(val content: String? = null, val reasoning: String? = null) : ChatStreamEvent

    data class Usage(
        val promptTokens: Int?,
        val completionTokens: Int?,
        val reasoningTokens: Int? = null,
        val cachedTokens: Int? = null,
    ) : ChatStreamEvent

    /** 服务端给出的 `finish_reason`（`stop`/`length`/`aborted`/...），流结束前到达。 */
    data class Finished(val reason: String?) : ChatStreamEvent

    data object Completed : ChatStreamEvent
}

class ChatApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
