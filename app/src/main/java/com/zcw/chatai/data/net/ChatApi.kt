package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.model.MessageCitation
import com.zcw.chatai.data.model.ToolCall
import java.io.File
import kotlinx.coroutines.flow.Flow

interface ChatApi {
    fun stream(config: ChatConfig, messages: List<ChatRequestMessage>): Flow<ChatStreamEvent>

    /** 标准 `GET /models`，用于设置页的「测试连接 / 拉取模型列表」。 */
    suspend fun listModels(config: ChatConfig): List<String>
}

/**
 * 一条出站消息。纯文本消息在请求体里仍然编码成 JSON 字符串（最兼容），
 * 只有 [images]/[videos]/[audios] 非空时才编码成内容块数组。
 */
data class ChatRequestMessage(
    val role: String,
    val content: String,
    val images: List<ChatRequestImage> = emptyList(),
    /** 视频块（仅 user 角色会真正编码）。 */
    val videos: List<ChatRequestVideo> = emptyList(),
    /** 音频块（仅 user 角色会真正编码；MiMo `input_audio` 形状）。 */
    val audios: List<ChatRequestAudio> = emptyList(),
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
    /** 来源标注（如 `[Image 3 | turn 5 2/2]`），以文本块形式插在该图片块前面。 */
    val label: String? = null,
)

/**
 * 出站视频。云端地址和本地文件是两种形态；占位符只在组 JSON 时生成。
 * [Remote.isOss] 为 true 时请求带 OSS 解析头。
 */
sealed interface ChatRequestVideo {
    data class Remote(val url: String, val isOss: Boolean = false) : ChatRequestVideo
    data class Inline(val id: String, val file: File, val mime: String) : ChatRequestVideo
}

/**
 * 出站音频。MiMo 的 `input_audio` 只有一个 `data` 字段（URL 或 data URI），无 `format`。
 */
sealed interface ChatRequestAudio {
    data class Remote(val dataUrl: String) : ChatRequestAudio
    data class Inline(val id: String, val file: File, val mime: String) : ChatRequestAudio
}

sealed interface ChatStreamEvent {
    data class Delta(val content: String? = null, val reasoning: String? = null) : ChatStreamEvent

    data class Usage(
        val promptTokens: Int?,
        val completionTokens: Int?,
        val reasoningTokens: Int? = null,
        val cachedTokens: Int? = null,
    ) : ChatStreamEvent

    data class Citations(val values: List<MessageCitation>) : ChatStreamEvent

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

/** 发送路径上的内存耗尽。只认 [OutOfMemoryError] 本身，不靠堆栈原文猜原因。 */
const val SEND_OUT_OF_MEMORY = "手机内存不足，没法完成发送，请稍后再试"

fun Throwable.userFacingSendError(fallback: String): String {
    if (hasOutOfMemory()) return SEND_OUT_OF_MEMORY
    return message?.takeIf { it.isNotBlank() } ?: fallback
}

private fun Throwable.hasOutOfMemory(): Boolean {
    if (this is OutOfMemoryError) return true
    val nested = cause
    return nested != null && nested !== this && nested.hasOutOfMemory()
}
