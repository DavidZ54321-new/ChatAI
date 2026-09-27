package com.zcw.chatai.data.video

/**
 * 视频生成模型：内置候选 + 用户覆盖。与对话/生图模型**完全解耦**
 * （走 DashScope 原生的 video-synthesis 异步端点，与任何对话供应商无关）。
 *
 * `wan3.0-video` 是 All-in-One：同一个模型名覆盖文生 / 图生（首帧、首尾帧）/ 参考生，
 * 由请求体 `input.media[].type` 自动路由，所以默认就用它。
 */
object VideoModels {

    const val DEFAULT = "wan3.0-video"

    /** 设置页可选候选。 */
    val presets: List<String> = listOf(
        "wan3.0-video",
        "wan2.6-t2v",
        "wan2.7-i2v-2026-04-25",
        "wan2.7-r2v-2026-06-12",
    )

    /** 生效模型：用户填的优先，空则回落内置默认。 */
    fun resolve(raw: String): String = raw.trim().ifEmpty { DEFAULT }
}
