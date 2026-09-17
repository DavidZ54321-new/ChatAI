package com.zcw.chatai.data.db

import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolSource
import com.zcw.chatai.data.model.ToolStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** `messages.tool_calls` / `messages.tool_result` 两列的 JSON 编解码（纯函数，JVM 可测）。 */
object ToolCallCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val callsSerializer = ListSerializer(ToolCallDto.serializer())

    fun encodeCalls(calls: List<ToolCall>): String? =
        if (calls.isEmpty()) null else json.encodeToString(callsSerializer, calls.map { it.toDto() })

    fun decodeCalls(raw: String?): List<ToolCall> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(callsSerializer, raw).mapNotNull { it.toModel() }
        } catch (t: Exception) {
            emptyList()
        }
    }

    fun encodeResult(result: ToolResult?): String? =
        result?.let { json.encodeToString(ToolResultDto.serializer(), it.toDto()) }

    fun decodeResult(raw: String?): ToolResult? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString(ToolResultDto.serializer(), raw).toModel()
        } catch (t: Exception) {
            null
        }
    }

    private fun ToolCall.toDto() = ToolCallDto(id = id, name = name, arguments = arguments)

    private fun ToolCallDto.toModel(): ToolCall? =
        if (id.isBlank() || name.isBlank()) null else ToolCall(id = id, name = name, arguments = arguments)

    private fun ToolResult.toDto() = ToolResultDto(
        status = status.name,
        detail = detail,
        sources = sources.map { SourceDto(it.url, it.title, it.snippet, it.publishedAt) },
        text = text,
    )

    private fun ToolResultDto.toModel() = ToolResult(
        status = ToolStatus.entries.firstOrNull { it.name == status } ?: ToolStatus.FAILED,
        detail = detail,
        sources = sources.filter { it.url.isNotBlank() }
            .map { ToolSource(it.url, it.title, it.snippet, it.publishedAt) },
        text = text,
    )
}

@Serializable
private data class ToolCallDto(val id: String, val name: String, val arguments: String = "{}")

@Serializable
private data class ToolResultDto(
    val status: String,
    val detail: String,
    val sources: List<SourceDto> = emptyList(),
    val text: String = "",
)

@Serializable
private data class SourceDto(
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
    val publishedAt: String? = null,
)
