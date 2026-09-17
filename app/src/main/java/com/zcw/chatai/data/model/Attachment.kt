package com.zcw.chatai.data.model

enum class AttachmentKind { IMAGE }

/**
 * 消息附件。二进制不入库，只把元数据以 JSON 存进 `messages.attachments`，
 * 文件本身落在应用私有目录（相对 [relativePath] 是相对 `filesDir` 的路径，
 * 这样换设备/恢复备份后仍然可解析）。
 */
data class Attachment(
    val id: String,
    val kind: AttachmentKind = AttachmentKind.IMAGE,
    val relativePath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)
