package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.model.ToolCall
import kotlinx.coroutines.flow.Flow

interface ChatApi {
    fun stream(config: ChatConfig, messages: List<ChatRequestMessage>): Flow<ChatStreamEvent>

    /** 标准 `GET /models`，用于设置页的「测试连接 / 拉取模型列表」。 */
    suspend fun listModels(config: ChatConfig): List<String>
}

/**
 * 一条出站消息。纯文本消息在请求体里仍然编码成 JSON 字符串（最兼容），
 * 只有 [images]/[videos] 非空时才编码成内容块数组。
 */
data class ChatRequestMessage(
    val role: String,
    val content: String,
    val images: List<ChatRequestImage> = emptyList(),
    /** 视频块（仅 user 角色会真正编码）。 */
    val videos: List<ChatRequestVideo> = emptyList(),
    /** assistant 消息携带的工具调用（重发历史时用）。 */
    val toolCalls: List<ToolCall> = emptyList(),
    /** `role=tool` 结果消息对应的调用 id。 */
    val toolCallId: String? = null,
    /** 仅带 tool_calls 的 assistant 回合需要回传（否则思考模式 400）。 */
    val reasoning: String? = null,
)

/** 内联图片（`data:image/jpeg;base64,...`）。不用外部 URL：实测多数端点有防盗链。 */
data class ChatRequestImage(
    val dataUrl: String,
    val detail: String? = null,
    /** 来源标注（如 `[Image 3 | previous turn 2/2]`），以文本块形式插在该图片块前面。 */
    val label: String? = null,
)

/**
 * 视频部件：小文件内联 data URL，大文件是云端的 `oss://` 临时 URL。
 * [isOss] 为 true 时请求需要带厂商的 OSS 解析头（由网络层按需添加）。
 */
data class ChatRequestVideo(
    val url: String,
    val isOss: Boolean = false,
)

sealed interface ChatStreamEvent {
    data class Delta(val content: String? = null, val reasoning: String? = null) : ChatStreamEvent

    data class Usage(
        val promptTokens: Int?,
        val completionTokens: Int?,
        val reasoningTokens: Int? = null,
        val cachedTokens: Int? = null,
    ) : ChatStreamEvent

    /** 模型决定调用工具；`arguments` 是逐字符增量，必须按 index 拼接。 */
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val arguments: String? = null,
    ) : ChatStreamEvent

    /** 服务端给出的 `finish_reason`（`stop`/`length`/`aborted`/...），流结束前到达。 */
    data class Finished(val reason: String?) : ChatStreamEvent

    data object Completed : ChatStreamEvent
}

class ChatApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
