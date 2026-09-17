package com.zcw.chatai.data.media

/** 纯逻辑的图片工具（无 Android 依赖，JVM 单测覆盖）。 */
object ImageCodec {

    /** 出站图片长边上限。服务端会把图缩到 ~1300×1300，客户端再大就是白花流量。 */
    const val MAX_EDGE = 1568

    /** 列表缩略图长边。 */
    const val THUMB_EDGE = 360

    const val JPEG_QUALITY = 85

    const val MIME_JPEG = "image/jpeg"
    const val MIME_PNG = "image/png"

    data class ImageSize(val width: Int, val height: Int)

    /** 等比缩放到长边不超过 [maxEdge]；已经足够小则原样返回（不放大）。 */
    fun computeTargetSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): ImageSize {
        if (width <= 0 || height <= 0) return ImageSize(1, 1)
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return ImageSize(width, height)
        val scale = maxEdge.toDouble() / longest
        return ImageSize(
            width = (width * scale).toInt().coerceAtLeast(1),
            height = (height * scale).toInt().coerceAtLeast(1),
        )
    }

    /**
     * 按**文件内容**嗅探图片格式（服务端也是这么做的，不信任扩展名/MIME 声明）。
     * 返回 null 表示不是受支持的图片。
     */
    fun sniffMime(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return MIME_JPEG
        }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (png.indices.all { bytes[it] == png[it] }) return MIME_PNG
        if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) {
            return "image/gif"
        }
        if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        return null
    }

    /** 内联图片的 data URL。**必须内联 base64**：外部 URL 方式实测会被端点拒绝。 */
    fun toDataUrl(mime: String, bytes: ByteArray): String =
        "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)

    /** base64 之后的字符数（4×⌈n/3⌉），用于估算请求体大小。 */
    fun base64Length(byteCount: Long): Long = ((byteCount + 2) / 3) * 4

    /** `/a/b/name.jpg` → `/a/b/name.thumb.jpg`。 */
    fun thumbRelativePath(relativePath: String): String {
        val slash = relativePath.lastIndexOf('/')
        val dot = relativePath.lastIndexOf('.')
        return if (dot > slash) {
            relativePath.substring(0, dot) + ".thumb" + relativePath.substring(dot)
        } else {
            "$relativePath.thumb"
        }
    }
}
