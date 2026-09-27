package com.zcw.chatai.data.image

/**
 * 生图/改图模型：内置候选 + 用户覆盖。与对话模型、图搜模型**完全解耦**
 * （生图走 DashScope 原生的 multimodal-generation 端点，与任何对话供应商无关）。
 */
object ImageModels {

    const val DEFAULT = "qwen-image-3.0-pro"

    /**
     * 设置页可选候选。都是 Qwen-Image 系列，跑在同一个同步端点上：
     * 无输入图即文生图，有输入图即图像编辑。
     */
    val presets: List<String> = listOf(
        "qwen-image-3.0-pro",
        "qwen-image-3.0",
        "qwen-image-2.0-pro",
        "qwen-image-2.0",
        "qwen-image-edit-max",
        "qwen-image-edit-plus",
        "qwen-image-edit",
    )

    /** 生效模型：用户填的优先，空则回落内置默认。 */
    fun resolve(raw: String): String = raw.trim().ifEmpty { DEFAULT }
}
