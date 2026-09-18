package com.zcw.chatai.data.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.net.ChatRequestImage
import com.zcw.chatai.data.net.ChatRequestVideo
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AttachmentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 附件文件仓库：应用私有目录，卸载即清。
 *
 * 布局：`filesDir/attachments/{conversationId}/{id}.jpg` + `{id}.thumb.jpg`。
 * 只保留压缩副本（长边 1568 的发送图 + 360 的列表缩略图），原图不入库不落盘。
 */
class AttachmentStore(private val context: Context) {

    private val root: File get() = File(context.filesDir, DIR)

    suspend fun importImage(
        conversationId: String,
        uri: Uri,
        id: String = UUID.randomUUID().toString(),
    ): Attachment = withContext(Dispatchers.IO) {
        val decoded = try {
            ImageCompressor.decode(context, uri)
        } catch (t: Throwable) {
            throw AttachmentException("无法读取这张图片（格式不受支持或文件已损坏）", t)
        }
        val bitmap = decoded.bitmap
        try {
            val extension = ImageCompressor.outputExtension(bitmap, decoded.sourceMime)
            val mime = ImageCompressor.outputMime(bitmap, decoded.sourceMime)
            val relativePath = "$DIR/$conversationId/$id.$extension"
            val target = File(context.filesDir, relativePath)
            val written = ImageCompressor.write(bitmap, decoded.sourceMime, target)
                ?: throw AttachmentException("图片保存失败，请重试")

            val thumbTarget = thumbnailOf(relativePath)
            val thumbSize = ImageCodec.computeTargetSize(
                decoded.size.width,
                decoded.size.height,
                ImageCodec.THUMB_EDGE,
            )
            val thumb = Bitmap.createScaledBitmap(bitmap, thumbSize.width, thumbSize.height, true)
            try {
                ImageCompressor.write(thumb, ImageCodec.MIME_JPEG, thumbTarget)
            } finally {
                if (thumb !== bitmap) thumb.recycle()
            }

            Attachment(
                id = id,
                kind = AttachmentKind.IMAGE,
                relativePath = relativePath,
                mimeType = mime,
                width = decoded.size.width,
                height = decoded.size.height,
                sizeBytes = written,
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 导入视频：原样复制 mp4（不转码），用 `MediaMetadataRetriever` 读时长并抽首帧做缩略图。
     * 只收 mp4（Qwen 视频输入的格式要求），超限/不可读给出可读错误。
     */
    suspend fun importVideo(
        conversationId: String,
        uri: Uri,
        id: String = UUID.randomUUID().toString(),
    ): Attachment = withContext(Dispatchers.IO) {
        if (!isMp4(uri)) throw AttachmentException("目前只支持 MP4 视频")
        val mime = MIME_MP4
        val relativePath = "$DIR/$conversationId/$id.mp4"
        val target = File(context.filesDir, relativePath)
        target.parentFile?.mkdirs()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw AttachmentException("无法读取这个视频（文件已损坏或权限不足）")
        } catch (t: AttachmentException) {
            throw t
        } catch (t: Throwable) {
            target.delete()
            throw AttachmentException("无法读取这个视频（文件已损坏或权限不足）", t)
        }
        if (target.length() <= 0L) {
            target.delete()
            throw AttachmentException("视频文件为空")
        }
        if (target.length() > AttachmentLimits.MAX_VIDEO_BYTES) {
            val mb = AttachmentLimits.MAX_VIDEO_BYTES / 1024 / 1024
            target.delete()
            throw AttachmentException("视频超过 ${mb} MB 上限，请先压缩或剪短")
        }
        val info = VideoMetadata.read(target)
        val frame = VideoMetadata.firstFrame(target)
        val frameWidth = frame?.width ?: info?.width ?: 0
        val frameHeight = frame?.height ?: info?.height ?: 0
        if (frame != null) {
            val thumbSize = ImageCodec.computeTargetSize(frame.width, frame.height, ImageCodec.THUMB_EDGE)
            val thumb = Bitmap.createScaledBitmap(frame, thumbSize.width, thumbSize.height, true)
            try {
                ImageCompressor.write(thumb, ImageCodec.MIME_JPEG, thumbnailOf(relativePath))
            } finally {
                if (thumb !== frame) thumb.recycle()
                frame.recycle()
            }
        }
        Attachment(
            id = id,
            kind = AttachmentKind.VIDEO,
            relativePath = relativePath,
            mimeType = mime,
            width = frameWidth,
            height = frameHeight,
            sizeBytes = target.length(),
            durationMs = info?.durationMs,
        )
    }

    private fun isMp4(uri: Uri): Boolean {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (type == MIME_MP4) return true
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        return name?.endsWith(".mp4", ignoreCase = true) == true
    }

    /** 视频原文件字节（内联 base64 或上传路由用）。 */
    fun videoBytes(attachment: Attachment): ByteArray? {
        val file = fileOf(attachment)
        if (!file.isFile) return null
        return try {
            file.readBytes().takeIf { it.isNotEmpty() }
        } catch (t: Exception) {
            null
        }
    }

    /** 内联视频：整文件 base64 data URL（小于阈值的视频走这条路，无需上传）。 */
    fun toRequestVideo(attachment: Attachment): ChatRequestVideo? {
        val bytes = videoBytes(attachment) ?: return null
        return ChatRequestVideo(url = ImageCodec.toDataUrl(attachment.mimeType, bytes), isOss = false)
    }

    fun fileOf(attachment: Attachment): File = File(context.filesDir, attachment.relativePath)

    fun thumbnailOf(attachment: Attachment): File = thumbnailOf(attachment.relativePath)

    /** 读文件并内联成 data URL。调用方保证在 IO 线程上（组上下文时统一切过一次线程）。 */
    fun toRequestImage(attachment: Attachment, detail: String?): ChatRequestImage? {
        val file = fileOf(attachment)
        if (!file.isFile) return null
        val bytes = try {
            file.readBytes()
        } catch (t: Exception) {
            return null
        }
        if (bytes.isEmpty()) return null
        val mime = ImageCodec.sniffMime(bytes) ?: attachment.mimeType
        return ChatRequestImage(dataUrl = ImageCodec.toDataUrl(mime, bytes), detail = detail)
    }

    fun delete(attachments: List<Attachment>) {
        attachments.forEach { attachment ->
            runCatching { fileOf(attachment).delete() }
            runCatching { thumbnailOf(attachment).delete() }
        }
    }

    fun deleteConversation(conversationId: String) {
        runCatching { File(root, conversationId).deleteRecursively() }
    }

    /**
     * 清理不再被任何消息引用、且超过 [olderThanMs] 未修改的孤儿文件
     * （应用被杀在发送中途会留下这类文件）。[referenced] 是相对 `filesDir` 的路径集合。
     */
    suspend fun sweepOrphans(referenced: Set<String>, olderThanMs: Long = 6 * 60 * 60 * 1000L) =
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - olderThanMs
            val files = root.listFiles()?.toList().orEmpty()
            for (dir in files) {
                val children = dir.listFiles()?.toList().orEmpty()
                for (file in children) {
                    val relative = file.relativeTo(context.filesDir).path
                    if (relative !in referenced && file.lastModified() < cutoff) {
                        runCatching { file.delete() }
                    }
                }
                if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) {
                    runCatching { dir.delete() }
                }
            }
        }

    private fun thumbnailOf(relativePath: String): File =
        File(context.filesDir, ImageCodec.thumbRelativePath(relativePath))

    companion object {
        const val DIR = "attachments"
        const val MIME_MP4 = "video/mp4"
    }
}
