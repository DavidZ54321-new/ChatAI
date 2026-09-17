package com.zcw.chatai.data.model

enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

/** 模型请求的一次函数调用（`arguments` 是原始 JSON 字符串）。 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

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
    /** 思考耗时（毫秒）；null = 未测量。 */
    val reasoningMs: Long? = null,
    val attachments: List<Attachment> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolResult: ToolResult? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
