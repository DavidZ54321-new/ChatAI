package com.zcw.chatai.data.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.zcw.chatai.data.backup.FileSwap
import com.zcw.chatai.data.doc.DocumentException
import com.zcw.chatai.data.doc.DocumentKind
import com.zcw.chatai.data.doc.DocumentParser
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.net.ChatRequestAudio
import com.zcw.chatai.data.net.ChatRequestImage
import com.zcw.chatai.data.net.ChatRequestVideo
import java.io.ByteArrayOutputStream
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

    /**
     * 导入文档：原文件原样落盘 + 解析出的纯文本写 sidecar `.txt`。
     * 出站时只发 sidecar 文本（见 [documentText]），原文件是真相源（重解析/未来预览用）。
     */
    suspend fun importDocument(
        conversationId: String,
        uri: Uri,
        id: String = UUID.randomUUID().toString(),
    ): Attachment = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri)?.takeIf { it.isNotBlank() } ?: "未命名文档"
        val resolverMime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val bytes = readCapped(uri, AttachmentLimits.MAX_DOC_BYTES)
            ?: throw AttachmentException(
                "文档超过 ${AttachmentLimits.MAX_DOC_BYTES / 1024 / 1024} MB 上限，请拆分或压缩后再发送",
            )
        if (bytes.isEmpty()) throw AttachmentException("文档是空文件")
        val kind = try {
            DocumentKind.detect(resolverMime, displayName, bytes)
        } catch (t: DocumentException) {
            Log.w(TAG, "不支持的文档类型：$displayName", t)
            throw AttachmentException(t.message ?: "暂不支持这种文档格式", t)
        }
        val parsed = try {
            DocumentParser.parse(bytes, kind, displayName)
        } catch (t: DocumentException) {
            Log.w(TAG, "文档解析失败：$displayName", t)
            throw AttachmentException(t.message ?: "文档解析失败", t)
        }
        val relativePath = "$DIR/$conversationId/$id.${extensionFor(kind, displayName)}"
        val target = File(context.filesDir, relativePath)
        // sidecar 必须和原文件不同名：TEXT 文档原文件本身就是 .txt（同名会覆盖原文件）。
        val sidecarRelative = "$DIR/$conversationId/$id.extracted.txt"
        try {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            File(context.filesDir, sidecarRelative).writeText(parsed.text, Charsets.UTF_8)
        } catch (t: Throwable) {
            target.delete()
            File(context.filesDir, sidecarRelative).delete()
            throw AttachmentException("文档保存失败，请重试", t)
        }
        Attachment(
            id = id,
            kind = AttachmentKind.DOCUMENT,
            relativePath = relativePath,
            mimeType = canonicalMime(kind, resolverMime),
            width = 0,
            height = 0,
            sizeBytes = bytes.size.toLong(),
            extractedPath = sidecarRelative,
            extractedMeta = parsed.meta,
            extractedChars = parsed.text.length.toLong(),
            displayName = displayName,
        )
    }

    /** 文档 sidecar 纯文本。调用方保证在 IO 线程上（组上下文时统一切过一次线程）。 */
    fun documentText(attachment: Attachment): String? {
        val relative = attachment.extractedPath ?: return null
        val file = File(context.filesDir, relative)
        if (!file.isFile) return null
        return try {
            file.readText(Charsets.UTF_8).takeIf { it.isNotEmpty() }
        } catch (t: Exception) {
            null
        }
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

    /** 视频/音频原文件字节（内联 base64 或上传路由用）。 */
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

    /** 内联音频：整文件 base64 data URL（MiMo `input_audio.data` 形状）。 */
    fun toRequestAudio(attachment: Attachment): ChatRequestAudio? {
        val bytes = videoBytes(attachment) ?: return null
        return ChatRequestAudio(dataUrl = ImageCodec.toDataUrl(attachment.mimeType, bytes))
    }

    /**
     * 导入音频：原样复制（不转码），用 `MediaMetadataRetriever` 读时长。
     * 无位图缩略图（用字母牌呈现）；只收 MiMo 支持的格式，超限/不可读给出可读错误。
     */
    suspend fun importAudio(
        conversationId: String,
        uri: Uri,
        id: String = UUID.randomUUID().toString(),
    ): Attachment = withContext(Dispatchers.IO) {
        val resolverMime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val displayName = queryDisplayName(uri) ?: "未命名音频"
        val extension = audioExtension(resolverMime, displayName)
            ?: throw AttachmentException("暂不支持这种音频格式（支持 MP3/WAV/FLAC/M4A/OGG）")
        val mime = audioMime(extension)
        val relativePath = "$DIR/$conversationId/$id.$extension"
        val target = File(context.filesDir, relativePath)
        target.parentFile?.mkdirs()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw AttachmentException("无法读取这个音频（文件已损坏或权限不足）")
        } catch (t: AttachmentException) {
            throw t
        } catch (t: Throwable) {
            target.delete()
            throw AttachmentException("无法读取这个音频（文件已损坏或权限不足）", t)
        }
        if (target.length() <= 0L) {
            target.delete()
            throw AttachmentException("音频文件为空")
        }
        if (target.length() > AttachmentLimits.MAX_AUDIO_BYTES) {
            val mb = AttachmentLimits.MAX_AUDIO_BYTES / 1024 / 1024
            target.delete()
            throw AttachmentException("音频超过 ${mb} MB 上限，请先压缩或剪短")
        }
        val info = VideoMetadata.read(target)
        Attachment(
            id = id,
            kind = AttachmentKind.AUDIO,
            relativePath = relativePath,
            mimeType = mime,
            width = 0,
            height = 0,
            sizeBytes = target.length(),
            durationMs = info?.durationMs,
            displayName = displayName,
        )
    }

    private fun audioExtension(resolverMime: String?, displayName: String): String? {
        val fromMime = when (resolverMime) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/x-m4a", "audio/aac" -> "m4a"
            "audio/ogg", "application/ogg" -> "ogg"
            else -> null
        }
        if (fromMime != null) return fromMime
        return displayName.substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("mp3", "wav", "flac", "m4a", "ogg") }
    }

    private fun audioMime(extension: String): String = when (extension) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        else -> "audio/ogg"
    }

    fun fileOf(attachment: Attachment): File = File(context.filesDir, attachment.relativePath)

    fun extractedFileOf(attachment: Attachment): File? =
        attachment.extractedPath?.let { File(context.filesDir, it) }

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

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    /** 流式读 + 计数：超限返回 null（不落盘、不 whole 读），IO 异常直接抛可读错误。 */
    private fun readCapped(uri: Uri, maxBytes: Long): ByteArray? {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > maxBytes) return null
                    out.write(buffer, 0, read)
                }
                return out.toByteArray()
            }
        } catch (t: Throwable) {
            throw AttachmentException("无法读取这个文档（文件已损坏或权限不足）", t)
        }
        throw AttachmentException("无法读取这个文档（文件已损坏或权限不足）")
    }

    private fun extensionFor(kind: DocumentKind, displayName: String): String = when (kind) {
        DocumentKind.PDF -> "pdf"
        DocumentKind.DOCX -> "docx"
        DocumentKind.XLSX -> "xlsx"
        DocumentKind.PPTX -> "pptx"
        DocumentKind.TEXT -> displayName.substringAfterLast('.', "txt").lowercase()
            .takeIf { it in setOf("txt", "md", "markdown", "csv", "log", "json") } ?: "txt"
    }

    private fun canonicalMime(kind: DocumentKind, resolverMime: String?): String {
        if (!resolverMime.isNullOrBlank()) return resolverMime
        return when (kind) {
            DocumentKind.PDF -> "application/pdf"
            DocumentKind.DOCX ->
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            DocumentKind.XLSX ->
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            DocumentKind.PPTX ->
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            DocumentKind.TEXT -> "text/plain"
        }
    }

    fun delete(attachments: List<Attachment>) {
        attachments.forEach { attachment ->
            runCatching { fileOf(attachment).delete() }
            runCatching { thumbnailOf(attachment).delete() }
            runCatching {
                attachment.extractedPath?.let { File(context.filesDir, it).delete() }
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        runCatching { File(root, conversationId).deleteRecursively() }
    }

    /** 附件目录根：备份导出要整棵树遍历，不是逐个附件走元数据。 */
    fun rootDir(): File = root

    /**
     * 把一批附件复制到目标会话目录（主文件 + 缩略图 + 文档 sidecar 一起搬），
     * 返回 `源附件 id → 新附件`（路径已重写）；源文件不存在的条目直接跳过。
     *
     * 用于「对话分支」：分支会话必须自包含——共享原路径的话，
     * 删父会话（`deleteConversation` 递归删目录）会把分支的文件一起带走。
     */
    suspend fun copyToConversation(
        targetConversationId: String,
        attachments: List<Attachment>,
    ): Map<String, Attachment> = withContext(Dispatchers.IO) {
        val copied = LinkedHashMap<String, Attachment>()
        for (attachment in attachments) {
            val source = fileOf(attachment)
            if (!source.isFile) continue
            val targetRelative = "$DIR/$targetConversationId/${source.name}"
            val target = File(context.filesDir, targetRelative)
            try {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
                val sourceThumb = thumbnailOf(attachment)
                if (sourceThumb.isFile) {
                    File(context.filesDir, ImageCodec.thumbRelativePath(targetRelative))
                        .writeBytes(sourceThumb.readBytes())
                }
            } catch (t: Throwable) {
                Log.w(TAG, "附件复制失败：${attachment.relativePath}", t)
                target.delete()
                continue
            }
            val targetExtracted = attachment.extractedPath?.let { path ->
                val sourceSidecar = File(context.filesDir, path)
                if (!sourceSidecar.isFile) {
                    null
                } else {
                    val relative = "$DIR/$targetConversationId/${sourceSidecar.name}"
                    runCatching {
                        File(context.filesDir, relative).writeBytes(sourceSidecar.readBytes())
                    }.getOrNull()?.let { relative }
                }
            }
            copied[attachment.id] = attachment.copy(
                relativePath = targetRelative,
                extractedPath = targetExtracted,
            )
        }
        copied
    }

    /**
     * 覆盖式还原：把 [stagingRoot] 下的 `attachments/`（备份解压的落地目录）
     * 顶替当前的附件根目录。真正的顶替规则在 `FileSwap`——它保证任何一步失败都不会
     * 让旧目录先被删掉（导入是脱离界面生命周期跑的，被杀在这个窗口里会丢光附件）。
     */
    suspend fun swapIn(stagingRoot: File): Boolean = withContext(Dispatchers.IO) {
        FileSwap.swapIn(root, File(stagingRoot, DIR))
    }

    /** 清空全部附件：覆盖式还原遇到「备份不含附件」时，用它清掉本机旧文件。 */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        runCatching { root.deleteRecursively() }
        Unit
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
        private const val TAG = "AttachmentStore"
    }
}
