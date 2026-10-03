package com.zcw.chatai.data.image

/** 选择器里的一个图像模型。 */
data class ImageModelOption(
    val id: String,
    val label: String,
    /** 为 false 时本轮不能带参考图（百炼 `request_modality` 只有 Text）。 */
    val acceptsImageInput: Boolean,
) {
    /** 下拉里既显示 id（真正提交的名字）也显示中文名，方便筛选。 */
    fun suggestion(): String {
        val wire = id.trim()
        val title = label.trim()
        if (title.isEmpty() || title.equals(wire, ignoreCase = true)) return wire
        return "$wire · $title"
    }
}

/** 从建议行还原模型 id。手输的 id 没有分隔符，原样返回。 */
fun imageModelIdFromSuggestion(suggestion: String): String =
    suggestion.substringBefore(" · ").trim()

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
