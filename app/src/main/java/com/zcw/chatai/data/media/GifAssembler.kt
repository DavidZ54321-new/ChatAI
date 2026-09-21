package com.zcw.chatai.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.Base64

/** 逐帧 GIF 组装的三种结局。 */
sealed interface GifResult {
    /** 成功编出动图。 */
    data class Animated(val bytes: ByteArray) : GifResult

    /** 帧间无差异（如 CSS 动效烘焙不出来）：调用方降级存首帧 PNG。 */
    data class Static(val pngBytes: ByteArray) : GifResult

    data object Failed : GifResult
}

/**
 * 把页内逐帧回传的 PNG base64 交给 [GifEncoder] 编成动图。
 *
 * 编码本身在 [GifEncoder]（纯逻辑、JVM 可测）；这里只做解码与调度。
 */
object GifAssembler {

    fun assemble(frames: List<String>, delayMs: Int): GifResult {
        if (frames.isEmpty()) return GifResult.Failed
        val first = decode(frames.first()) ?: return GifResult.Failed
        if (frames.size < 2 || frames.distinct().size <= 1) {
            val png = encodePng(first)
            first.recycle()
            return if (png == null) GifResult.Failed else GifResult.Static(png)
        }
        val width = first.width
        val height = first.height
        first.recycle()

        val decoded = ArrayList<GifEncoder.Frame>(frames.size)
        for (base64 in frames) {
            val bitmap = decode(base64) ?: continue
            if (bitmap.width != width || bitmap.height != height) {
                bitmap.recycle()
                continue
            }
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            decoded.add(GifEncoder.Frame(pixels, delayMs))
        }
        val bytes = GifEncoder.encode(width, height, decoded) ?: return GifResult.Failed
        return GifResult.Animated(bytes)
    }

    private fun decode(base64: String): Bitmap? = try {
        val bytes = Base64.getDecoder().decode(base64)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Throwable) {
        null
    }

    private fun encodePng(bitmap: Bitmap): ByteArray? = try {
        java.io.ByteArrayOutputStream().use { out ->
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
        }
    } catch (_: Throwable) {
        null
    }
}
