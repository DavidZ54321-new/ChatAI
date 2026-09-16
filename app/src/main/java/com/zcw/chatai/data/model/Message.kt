package com.zcw.chatai.data.model

enum class Role { USER, ASSISTANT, SYSTEM }
enum class MessageStatus { COMPLETE, STREAMING, ERROR, CANCELLED }

data class Message(
    val id: String,
    val conversationId: String,
    val role: Role,
    val content: String,
    val status: MessageStatus,
    val errorMessage: String?,
    val reasoningContent: String?,
    val seq: Long,
    val model: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val createdAt: Long,
    val updatedAt: Long,
)
