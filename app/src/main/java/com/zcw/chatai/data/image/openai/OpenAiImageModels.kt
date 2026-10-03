package com.zcw.chatai.data.image.openai

import com.zcw.chatai.data.image.ImageModelOption

/**
 * OpenAI 图像模型筛选（纯函数）。
 *
 * `GET /v1/models` 是全量目录。留下 `gpt-image-` 与 `chatgpt-image-`，丢掉 DALL·E。
 */
object OpenAiImageModels {

    val builtin: List<ImageModelOption> = listOf(
        ImageModelOption("gpt-image-2.5-sunburst", "gpt-image-2.5-sunburst", acceptsImageInput = true),
        ImageModelOption("gpt-image-2.5-flare", "gpt-image-2.5-flare", acceptsImageInput = true),
    )

    fun filter(ids: List<String>): List<ImageModelOption> {
        val seen = LinkedHashSet<String>()
        val result = ArrayList<ImageModelOption>()
        for (raw in ids) {
            val id = raw.trim()
            if (id.isEmpty() || !seen.add(id)) continue
            val lower = id.lowercase()
            if (lower.startsWith("dall-e")) continue
            if (lower.startsWith("gpt-image-") || lower.startsWith("chatgpt-image-")) {
                result.add(ImageModelOption(id, id, acceptsImageInput = true))
            }
        }
        return result
    }
}
