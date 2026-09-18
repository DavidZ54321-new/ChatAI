package com.zcw.chatai.data.db

import com.zcw.chatai.data.model.SearchedImage
import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.model.ToolKind
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolSource
import com.zcw.chatai.data.model.ToolStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * `messages.tool_calls` / `messages.tool_result` 两列的 JSON 编解码（纯函数，JVM 可测）。
 *
 * 容错策略：整串非法（坏 JSON / 形状不符 / 必填字段缺失）→ 空列表或 null；
 * 数组内单条语义非法（缺 id/name、未知 status、空 URL 来源）→ 丢弃该条或回退，其余保留。
 */
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
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeResult(result: ToolResult?): String? =
        result?.let { json.encodeToString(ToolResultDto.serializer(), it.toDto()) }

    fun decodeResult(raw: String?): ToolResult? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString(ToolResultDto.serializer(), raw).toModel()
        } catch (_: Exception) {
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
        kind = kind.name,
        images = images.map { ImageDto(it.index, it.title, it.url) },
    )

    private fun ToolResultDto.toModel() = ToolResult(
        status = ToolStatus.entries.firstOrNull { it.name == status } ?: ToolStatus.FAILED,
        detail = detail,
        sources = sources.filter { it.url.isNotBlank() }
            .map { ToolSource(it.url, it.title, it.snippet, it.publishedAt) },
        text = text,
        // 旧数据没有 kind/images 字段：默认 SEARCH + 空列表，行为与升级前一致。
        kind = ToolKind.entries.firstOrNull { it.name == kind } ?: ToolKind.SEARCH,
        images = images.filter { it.url.isNotBlank() }
            .map { SearchedImage(index = it.index, title = it.title, url = it.url) },
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
    val kind: String = ToolKind.SEARCH.name,
    val images: List<ImageDto> = emptyList(),
)

@Serializable
private data class ImageDto(
    val index: Int = 0,
    val title: String = "",
    val url: String,
)

@Serializable
private data class SourceDto(
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
    val publishedAt: String? = null,
)
