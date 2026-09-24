package com.zcw.chatai.data.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream

/**
 * 相册写入（MediaStore）：统一 MIME / 文件名的解析，导出 PNG/GIF 与保存远程图共用。
 * minSdk 29（Q）起插自己的媒体项无需存储权限。
 */
object GalleryStore {

    /** 相册子目录：`Pictures/ChatAI`。 */
    private const val ALBUM = "ChatAI"

    /** 按文件名推断写入 MIME；未知扩展名回落 JPEG（与旧 saveToGallery 行为一致）。 */
    fun mimeFor(fileName: String): String = when (extensionOf(fileName)) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    /** 导出文件名：`chat_ai_<时间戳>.<扩展名>`（扩展名接受带/不带点、大小写混合）。 */
    fun exportFileName(timestampMs: Long, extension: String): String =
        "chat_ai_$timestampMs.${normalizeExtension(extension)}"

    /** 把内存里的导出字节写进相册。 */
    fun saveBytes(context: Context, bytes: ByteArray, mime: String, displayName: String): Boolean =
        insert(context, displayName, mime) { output -> output.write(bytes) }

    private fun insert(
        context: Context,
        displayName: String,
        mime: String,
        write: (OutputStream) -> Unit,
    ): Boolean {
        val resolver = context.contentResolver
        var inserted: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            inserted = uri
            val output = resolver.openOutputStream(uri)
            if (output == null) {
                deleteQuietly(resolver, uri)
                return false
            }
            output.use(write)
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
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
