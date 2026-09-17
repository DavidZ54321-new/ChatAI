package com.zcw.chatai.data.model

enum class Role { USER, ASSISTANT, SYSTEM }

/** 出站请求里的 role 字面量（OpenAI 兼容格式一律小写）。 */
val Role.wire: String
    get() = name.lowercase()
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
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)
