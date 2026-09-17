package com.zcw.chatai.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/** 解码（自动应用 EXIF 方向）→ 等比缩放 → 重新编码。HEIC 等格式也被归一化成 JPEG/PNG。 */
object ImageCompressor {

    data class Decoded(val bitmap: Bitmap, val sourceMime: String, val size: ImageCodec.ImageSize)

    fun decode(context: Context, uri: Uri, maxEdge: Int = ImageCodec.MAX_EDGE): Decoded {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        var target: ImageCodec.ImageSize? = null
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val size = ImageCodec.computeTargetSize(info.size.width, info.size.height, maxEdge)
            target = size
            // 软件位图才能可靠地 compress；硬件位图在部分机型上会抛异常。
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(size.width, size.height)
        }
        val resolved = target ?: ImageCodec.ImageSize(bitmap.width, bitmap.height)
        return Decoded(
            bitmap = bitmap,
            sourceMime = ImageCodec.sniffMime(readHead(context, uri)) ?: ImageCodec.MIME_JPEG,
            size = resolved,
        )
    }

    /** 有 alpha 的图（PNG/WebP 源）保留 PNG，其余写 JPEG。返回落盘字节数，失败为 null。 */
    fun write(bitmap: Bitmap, sourceMime: String, target: File): Long? {
        target.parentFile?.mkdirs()
        return try {
            FileOutputStream(target).use { out ->
                if (!bitmap.compress(outputFormat(bitmap, sourceMime), ImageCodec.JPEG_QUALITY, out)) {
                    return null
                }
            }
            target.length()
        } catch (t: Exception) {
            target.delete()
            null
        }
    }

    fun outputMime(bitmap: Bitmap, sourceMime: String): String =
        if (keepAlpha(bitmap, sourceMime)) ImageCodec.MIME_PNG else ImageCodec.MIME_JPEG

    fun outputExtension(bitmap: Bitmap, sourceMime: String): String =
        if (keepAlpha(bitmap, sourceMime)) "png" else "jpg"

    private fun keepAlpha(bitmap: Bitmap, sourceMime: String): Boolean =
        bitmap.hasAlpha() && sourceMime != ImageCodec.MIME_JPEG

    private fun outputFormat(bitmap: Bitmap, sourceMime: String): Bitmap.CompressFormat =
        if (keepAlpha(bitmap, sourceMime)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG

    private fun readHead(context: Context, uri: Uri, length: Int = 16): ByteArray = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(length)
            val read = stream.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        } ?: ByteArray(0)
    } catch (t: Exception) {
        ByteArray(0)
    }
}
