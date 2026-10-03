package com.zcw.chatai.data.image.openai

import kotlinx.serialization.Serializable

/**
 * OpenAI Images API 的输出参数（纯函数）。
 *
 * 空字符串表示不下发，让服务端用自己的默认（质量 / 背景 / 格式都是 auto 或 png）。
 * 尺寸为空时，文生图仍用 [GENERATION_SIZE]，带参考图仍用 [EDIT_SIZE]。
 * 只收文档里 GPT Image 2 / 2.5 认的取值；透明背景不能配 jpeg。
 */
@Serializable
data class OpenAiImageOptions(
    val quality: String = "",
    val size: String = "",
    val background: String = "",
    val outputFormat: String = "",
    /** null = 不下发。只在 jpeg / webp 时才有意义。 */
    val outputCompression: Int? = null,
    /** 1..4，对应请求里的 `n`。接口允许到 10，界面只做到 4 张。 */
    val count: Int = 1,
) {
    fun sanitized(): OpenAiImageOptions = sanitize(
        quality = quality,
        size = size,
        background = background,
        outputFormat = outputFormat,
        outputCompression = outputCompression,
        count = count,
    )

    companion object {
        const val GENERATION_SIZE = "1024x1024"
        const val EDIT_SIZE = "auto"

        val QUALITIES = listOf("low", "medium", "high", "xhigh", "max")
        val BACKGROUNDS = listOf("auto", "opaque", "transparent")
        val FORMATS = listOf("png", "jpeg", "webp")
        val PRESET_SIZES = listOf(
            "auto",
            "1024x1024",
            "1536x1024",
            "1024x1536",
            "2048x2048",
            "2048x1152",
            "3840x2160",
            "2160x3840",
        )

        const val MIN_PIXELS = 655_360L
        const val MAX_PIXELS = 8_294_400L
        const val MAX_EDGE = 3840
        /** 文档把高于 2560×1440 的分辨率标成实验性。 */
        const val EXPERIMENTAL_PIXELS = 3_686_400L
        const val MAX_COUNT = 4

        fun sanitize(
            quality: String,
            size: String,
            background: String,
            outputFormat: String,
            outputCompression: Int?,
            count: Int,
        ): OpenAiImageOptions {
            val cleanQuality = quality.trim().lowercase().takeIf { it in QUALITIES }.orEmpty()
            val cleanBackground = background.trim().lowercase().takeIf { it in BACKGROUNDS }.orEmpty()
            var cleanFormat = outputFormat.trim().lowercase().takeIf { it in FORMATS }.orEmpty()
            if (cleanBackground == "transparent" && cleanFormat == "jpeg") cleanFormat = "png"
            val cleanSize = size.trim().lowercase().takeIf { sizeProblem(it) == null }.orEmpty()
            val cleanCompression = outputCompression
                ?.takeIf { cleanFormat == "jpeg" || cleanFormat == "webp" }
                ?.coerceIn(0, 100)
            return OpenAiImageOptions(
                quality = cleanQuality,
                size = cleanSize,
                background = cleanBackground,
                outputFormat = cleanFormat,
                outputCompression = cleanCompression,
                count = count.coerceIn(1, MAX_COUNT),
            )
        }

        /**
         * 空字符串合法（走默认尺寸）。
         * 否则是 `auto`，或 `宽x高`：两边都是 16 的倍数、单边 ≤3840、长短边 ≤3:1、
         * 总像素在 655360..8294400。
         */
        fun sizeProblem(raw: String): String? {
            val size = raw.trim().lowercase()
            if (size.isEmpty() || size == "auto") return null
            val parts = size.split('x')
            if (parts.size != 2) return "尺寸写成宽x高，例如 1536x864"
            val width = parts[0].toIntOrNull()
            val height = parts[1].toIntOrNull()
            if (width == null || height == null || width <= 0 || height <= 0) {
                return "尺寸写成宽x高，例如 1536x864"
            }
            if (width % 16 != 0 || height % 16 != 0) return "宽和高都要是 16 的倍数"
            if (width > MAX_EDGE || height > MAX_EDGE) return "单边不能超过 3840"
            val longEdge = maxOf(width, height)
            val shortEdge = minOf(width, height)
            if (longEdge > shortEdge * 3) return "长短边之比不能超过 3:1"
            val pixels = width.toLong() * height
            if (pixels < MIN_PIXELS || pixels > MAX_PIXELS) {
                return "总像素要在 655360 到 8294400 之间"
            }
            return null
        }

        fun experimentalSize(raw: String): Boolean {
            if (sizeProblem(raw) != null) return false
            val parts = raw.trim().lowercase().split('x')
            if (parts.size != 2) return false
            val width = parts[0].toIntOrNull() ?: return false
            val height = parts[1].toIntOrNull() ?: return false
            return width.toLong() * height > EXPERIMENTAL_PIXELS
        }
    }
}
