package com.zcw.chatai.data.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * 相册写入（MediaStore）：统一 MIME / 文件名的解析，导出 PNG/GIF、保存远程图与生成视频共用。
 * minSdk 29（Q）起插自己的媒体项无需存储权限。
 */
object GalleryStore {

    /** 相册子目录：图片在 `Pictures/ChatAI`，视频在 `Movies/ChatAI`。 */
    private const val ALBUM = "ChatAI"

    /** 按文件名推断写入 MIME；未知扩展名回落 JPEG（与旧 saveToGallery 行为一致）。 */
    fun mimeFor(fileName: String): String = when (extensionOf(fileName)) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    /** 视频 MIME：目前只产 mp4，其余按扩展名给常见值，未知回落 mp4。 */
    fun videoMimeFor(fileName: String): String = when (extensionOf(fileName)) {
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> "video/mp4"
    }

    /** 导出文件名：`chat_ai_<时间戳>.<扩展名>`（扩展名接受带/不带点、大小写混合）。 */
    fun exportFileName(timestampMs: Long, extension: String): String =
        "chat_ai_$timestampMs.${normalizeExtension(extension)}"

    /** 把内存里的图片导出字节写进相册。 */
    fun saveBytes(context: Context, bytes: ByteArray, mime: String, displayName: String): Boolean =
        insert(
            context = context,
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativeDir = "${Environment.DIRECTORY_PICTURES}/$ALBUM",
            displayName = displayName,
            mime = mime,
        ) { output -> output.write(bytes) }

    /** 把本地视频文件**流式**写进相册（`Movies/ChatAI`）：不把整段视频读进内存。 */
    fun saveVideoFile(context: Context, source: File, displayName: String, mime: String = "video/mp4"): Boolean =
        insert(
            context = context,
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            relativeDir = "${Environment.DIRECTORY_MOVIES}/$ALBUM",
            displayName = displayName,
            mime = mime,
        ) { output -> source.inputStream().use { it.copyTo(output) } }

    private fun insert(
        context: Context,
        collection: Uri,
        relativeDir: String,
        displayName: String,
        mime: String,
        write: (OutputStream) -> Unit,
    ): Boolean {
        val resolver = context.contentResolver
        var inserted: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return false
            inserted = uri
            val output = resolver.openOutputStream(uri)
            if (output == null) {
                deleteQuietly(resolver, uri)
                return false
            }
            output.use(write)
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            true
        } catch (_: Throwable) {
            // 失败别留 IS_PENDING=1 的孤儿行：相册看不见，但一直占着一条记录。
            inserted?.let { deleteQuietly(resolver, it) }
            false
        }
    }

    private fun deleteQuietly(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    private fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return ""
        return normalizeExtension(fileName.substring(dot + 1))
    }

    private fun normalizeExtension(extension: String): String =
        extension.removePrefix(".").lowercase()
}
