package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import kotlinx.coroutines.flow.Flow

interface ChatApi {
    fun stream(config: ChatConfig, messages: List<ChatRequestMessage>): Flow<ChatStreamEvent>
}

data class ChatRequestMessage(val role: String, val content: String)

sealed interface ChatStreamEvent {
    data class Delta(val content: String? = null, val reasoning: String? = null) : ChatStreamEvent

    data class Usage(val promptTokens: Int?, val completionTokens: Int?) : ChatStreamEvent

    data object Completed : ChatStreamEvent
}

class ChatApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
