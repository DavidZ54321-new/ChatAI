package com.zcw.chatai.data.model

enum class AttachmentKind { IMAGE, VIDEO, DOCUMENT }

/**
 * 消息附件。二进制不入库，只把元数据以 JSON 存进 `messages.attachments`，
 * 文件本身落在应用私有目录（相对 [relativePath] 是相对 `filesDir` 的路径，
 * 这样换设备/恢复备份后仍然可解析）。
 *
 * 视频的大文件路由会往云端传一份（免费临时存储，48h），[remoteUrl] 等字段就是那份拷贝的
 * 本地日志：本地文件永远是真相源，云端 URL 只是缓存，过期/换模型就重传。
 */
data class Attachment(
    val id: String,
    val kind: AttachmentKind = AttachmentKind.IMAGE,
    val relativePath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    /** 视频时长（毫秒）；图片为 null。 */
    val durationMs: Long? = null,
    /** 已上传的 `oss://` 临时 URL（仅视频大文件路由）。 */
    val remoteUrl: String? = null,
    /** 上传时指定的模型：官方要求文件与模型绑定，换模型必须重传。 */
    val remoteModel: String? = null,
    /** `oss://` URL 的过期时间（epoch ms，上传后 48h）。 */
    val remoteExpiresAt: Long? = null,
    /** 崩溃恢复日志：上传进行中的完整 OSS key（重传用它探测「云端已存在」）。 */
    val pendingKey: String? = null,
    /** 崩溃恢复日志：与 [pendingKey] 配套的上传凭证（JSON，300 秒有效）。 */
    val pendingPolicy: String? = null,
    /** 文档解析出的纯文本 sidecar（相对 `filesDir` 的路径，仅 DOCUMENT 有）。 */
    val extractedPath: String? = null,
    /** sidecar 的规模标注（如 "共 8 页"；解析期写入，出站直接引用）。 */
    val extractedMeta: String? = null,
    /** sidecar 纯文本字符数（会话文档配额用；老数据为 0）。 */
    val extractedChars: Long = 0,
    /** 用户选择时的原始文件名（`【文档：xxx】` 标注与缩略图用；图片/视频为 null）。 */
    val displayName: String? = null,
)
