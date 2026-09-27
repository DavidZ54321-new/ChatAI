package com.zcw.chatai.data.video

/** 视频生成模式。`wan3.0-video` 由 `input.media[].type` 自动路由，这里只决定出站素材形状与 UI。 */
enum class VideoMode { T2V, I2V, R2V }

/**
 * 视频出站素材的组装规则与校验（纯函数，JVM 单测覆盖）。
 *
 * - 文生视频（T2V）：只有提示词，不传 media。
 * - 图生视频（I2V）：首帧必填，尾帧可选 → `first_frame` (+ `last_frame`)。
 * - 参考生视频（R2V）：1~[MAX_REFERENCES] 张参考图 → `reference_image`。
 */
object VideoInputs {

    /** 参考图上限（wan2.7-r2v 是「参考图 + 参考视频 ≤ 5」；wan3.0 更宽松，取各模型交集）。 */
    const val MAX_REFERENCES = 5

    /**
     * 出站校验：返回 null 表示可发送，否则是给用户看的一句话。
     * [hasFirstFrame] / [hasLastFrame] / [referenceCount] 是素材的**存在性**（不是附件对象），
     * 方便复用同一份规则做 UI 可发送判定与仓库提交前校验。
     */
    fun validate(
        mode: VideoMode,
        prompt: String,
        hasFirstFrame: Boolean,
        hasLastFrame: Boolean,
        referenceCount: Int,
    ): String? = when (mode) {
        VideoMode.T2V -> if (prompt.isBlank()) "请输入提示词" else null
        VideoMode.I2V -> if (!hasFirstFrame) "图生视频需要一张首帧图片" else null
        VideoMode.R2V -> when {
            referenceCount <= 0 -> "参考生视频至少需要一张参考图"
            referenceCount > MAX_REFERENCES -> "参考图最多 $MAX_REFERENCES 张"
            else -> null
        }
    }

    /** 出站 media 的 `type` 有序列表（顺序即模型理解里「图1/图2」的编号顺序）。 */
    fun mediaTypes(
        mode: VideoMode,
        hasFirstFrame: Boolean,
        hasLastFrame: Boolean,
        referenceCount: Int,
    ): List<String> = when (mode) {
        VideoMode.T2V -> emptyList()
        VideoMode.I2V -> buildList {
            if (hasFirstFrame) add(TYPE_FIRST_FRAME)
            if (hasLastFrame) add(TYPE_LAST_FRAME)
        }
        VideoMode.R2V -> List(referenceCount.coerceAtLeast(0)) { TYPE_REFERENCE_IMAGE }
    }

    const val TYPE_FIRST_FRAME = "first_frame"
    const val TYPE_LAST_FRAME = "last_frame"
    const val TYPE_REFERENCE_IMAGE = "reference_image"
}
