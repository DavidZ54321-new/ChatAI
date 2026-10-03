package com.zcw.chatai.data.image.qwen

import kotlinx.serialization.Serializable

/**
 * 千问图像生成/编辑的可选参数（纯函数）。
 *
 * 文生图和图像编辑走同一个 DashScope 请求。空尺寸表示沿用现在的行为：
 * 文生图 `1024*1024`，带参考图时不传 size，让模型跟原图。
 * `prompt_extend` 仍是设置里的那个开关，不放在这里。
 */
@Serializable
data class QwenImageOptions(
    val size: String = "",
    /** 1..6，对应 `n`。 */
    val count: Int = 1,
    val negativePrompt: String = "",
    /** null = 不下发，由服务端随机。 */
    val seed: Int? = null,
    val watermark: Boolean = false,
    /** 空 = 不下发（服务端 direct）。`agent` 只对文生图生效。 */
    val promptExtendMode: String = "",
    /** null = 不下发。只在智能改写打开时才有意义。 */
    val enableThinking: Boolean? = null,
) {
    fun sanitized(): QwenImageOptions = sanitize(
        size = size,
        count = count,
        negativePrompt = negativePrompt,
        seed = seed,
        watermark = watermark,
        promptExtendMode = promptExtendMode,
        enableThinking = enableThinking,
    )

    /** 真正写进请求的尺寸。空白时文生图用方图，编辑省略。 */
    fun wireSize(editing: Boolean): String? =
        size.ifBlank { if (editing) null else DEFAULT_TEXT_SIZE }

    /**
     * 改写方式。改写关着、或带参考图时选了 agent，都不下发。
     * 图像编辑传 agent 会 400。
     */
    fun wireExtendMode(editing: Boolean, promptExtend: Boolean): String? {
        if (!promptExtend) return null
        val mode = promptExtendMode
        if (mode.isEmpty()) return null
        if (editing && mode == MODE_AGENT) return null
        return mode
    }

    /** 思考只在智能改写打开时下发。 */
    fun wireThinking(promptExtend: Boolean): Boolean? =
        if (promptExtend) enableThinking else null

    companion object {
        const val DEFAULT_TEXT_SIZE = "1024*1024"
        const val MAX_COUNT = 6
        const val MAX_NEGATIVE = 500
        const val MAX_SEED = 2_147_483_647
        const val MODE_DIRECT = "direct"
        const val MODE_AGENT = "agent"
        const val MIN_PIXELS = 512L * 512L
        const val MAX_PIXELS = 2048L * 2048L

        val MODES = listOf(MODE_DIRECT, MODE_AGENT)
        val PRESET_SIZES = listOf(
            "1024*1024",
            "2048*2048",
            "2688*1536",
            "1536*2688",
            "2368*1728",
            "1728*2368",
            "1664*928",
            "1472*1104",
            "1328*1328",
            "1104*1472",
            "928*1664",
        )

        fun sanitize(
            size: String,
            count: Int,
            negativePrompt: String,
            seed: Int?,
            watermark: Boolean,
            promptExtendMode: String,
            enableThinking: Boolean?,
        ): QwenImageOptions {
            val cleanSize = normalizeSize(size).takeIf { sizeProblem(it) == null }.orEmpty()
            val cleanMode = promptExtendMode.trim().lowercase().takeIf { it in MODES }.orEmpty()
            val cleanSeed = seed?.takeIf { it in 0..MAX_SEED }
            return QwenImageOptions(
                size = cleanSize,
                count = count.coerceIn(1, MAX_COUNT),
                negativePrompt = negativePrompt.trim().take(MAX_NEGATIVE),
                seed = cleanSeed,
                watermark = watermark,
                promptExtendMode = cleanMode,
                enableThinking = enableThinking,
            )
        }

        /** 空字符串合法。否则是 `宽*高`，总像素在 512²..2048²，长短边不超过 8:1。 */
        fun sizeProblem(raw: String): String? {
            val size = raw.trim()
            if (size.isEmpty()) return null
            val edges = edgesOf(size) ?: return "尺寸写成宽*高，例如 1024*1024"
            val (width, height) = edges
            if (width <= 0 || height <= 0) return "尺寸写成宽*高，例如 1024*1024"
            val pixels = width.toLong() * height
            if (pixels < MIN_PIXELS || pixels > MAX_PIXELS) {
                return "总像素要在 512*512 到 2048*2048 之间"
            }
            val longEdge = maxOf(width, height)
            val shortEdge = minOf(width, height)
            if (longEdge > shortEdge * 8) return "长短边之比不能超过 8:1"
            return null
        }

        fun normalizeSize(raw: String): String {
            val edges = edgesOf(raw) ?: return ""
            return "${edges.first}*${edges.second}"
        }

        private fun edgesOf(raw: String): Pair<Int, Int>? {
            val size = raw.trim().lowercase()
                .replace('x', '*')
                .replace('×', '*')
            if (size.isEmpty()) return null
            val parts = size.split('*')
            if (parts.size != 2) return null
            val width = parts[0].toIntOrNull() ?: return null
            val height = parts[1].toIntOrNull() ?: return null
            return width to height
        }
    }
}
