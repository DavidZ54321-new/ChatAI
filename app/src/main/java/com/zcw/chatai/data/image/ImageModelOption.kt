package com.zcw.chatai.data.image

/** 选择器里的一个图像模型。 */
data class ImageModelOption(
    val id: String,
    val label: String,
    /** 为 false 时本轮不能带参考图（百炼 `request_modality` 只有 Text）。 */
    val acceptsImageInput: Boolean,
)

/** 选择器的一个供应商段。顺序与注册的后端一致。 */
data class ImageModelSection(
    val providerId: String,
    val title: String,
    val models: List<ImageModelOption>,
)

/** 当前模型解析出的供应商、张数上限和参考图能力。 */
data class ImageSelection(
    val providerId: String,
    val providerLabel: String,
    val maxInputImages: Int,
    val acceptsImageInput: Boolean,
)
