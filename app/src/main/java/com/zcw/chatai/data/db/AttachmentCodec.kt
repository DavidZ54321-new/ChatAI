package com.zcw.chatai.data.db

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * `messages.attachments` 列的 JSON 编解码（纯函数，JVM 可测）。
 *
 * 容错策略：整串非法 → 空列表；单条非法（未知 kind / 缺关键字段）→ 丢弃该条并保留其余，
 * 保证未来版本写下的未知附件不会让整条消息的附件全部消失。
 */
object AttachmentCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serializer = ListSerializer(AttachmentDto.serializer())

    fun encode(attachments: List<Attachment>): String? =
        if (attachments.isEmpty()) null else json.encodeToString(serializer, attachments.map { it.toDto() })

    fun decode(raw: String?): List<Attachment> {
        if (raw.isNullOrBlank()) return emptyList()
        val decoded = try {
            json.decodeFromString(serializer, raw)
        } catch (t: Exception) {
            return emptyList()
        }
        return decoded.mapNotNull { it.toModel() }
    }

    private fun Attachment.toDto(): AttachmentDto = AttachmentDto(
        id = id,
        kind = kind.name,
        path = relativePath,
        mime = mimeType,
        width = width,
        height = height,
        size = sizeBytes,
        durationMs = durationMs,
        remoteUrl = remoteUrl,
        remoteModel = remoteModel,
        remoteExpiresAt = remoteExpiresAt,
        pendingKey = pendingKey,
        pendingPolicy = pendingPolicy,
        extractedPath = extractedPath,
        extractedMeta = extractedMeta,
        extractedChars = extractedChars,
        displayName = displayName,
    )

    private fun AttachmentDto.toModel(): Attachment? {
        if (id.isBlank() || path.isBlank()) return null
        val attachmentKind = AttachmentKind.entries.firstOrNull { it.name == kind } ?: return null
        return Attachment(
            id = id,
            kind = attachmentKind,
            relativePath = path,
            mimeType = mime.ifBlank { DEFAULT_MIME },
            width = width,
            height = height,
            sizeBytes = size,
            durationMs = durationMs,
            remoteUrl = remoteUrl,
            remoteModel = remoteModel,
            remoteExpiresAt = remoteExpiresAt,
            pendingKey = pendingKey,
            pendingPolicy = pendingPolicy,
            extractedPath = extractedPath,
            extractedMeta = extractedMeta,
            extractedChars = extractedChars,
            displayName = displayName,
        )
    }

    private const val DEFAULT_MIME = "image/jpeg"
}

@Serializable
private data class AttachmentDto(
    val id: String,
    val kind: String,
    val path: String,
    val mime: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0L,
    val durationMs: Long? = null,
    val remoteUrl: String? = null,
    val remoteModel: String? = null,
    val remoteExpiresAt: Long? = null,
    val pendingKey: String? = null,
    val pendingPolicy: String? = null,
    val extractedPath: String? = null,
    val extractedMeta: String? = null,
    val extractedChars: Long = 0,
    val displayName: String? = null,
)
