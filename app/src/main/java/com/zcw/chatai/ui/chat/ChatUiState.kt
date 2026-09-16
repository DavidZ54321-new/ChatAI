package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role

data class ChatMessageItem(
    val id: String,
    val role: Role,
    val content: String,
    val reasoning: String? = null,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val errorMessage: String? = null,
    val model: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

data class ChatUiState(
    val title: String = "新对话",
    val model: String = "",
    val messages: List<ChatMessageItem> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingMessageId: String? = null,
    val input: String = "",
) {
    val canSend: Boolean
        get() = input.isNotBlank() && !isStreaming
}
