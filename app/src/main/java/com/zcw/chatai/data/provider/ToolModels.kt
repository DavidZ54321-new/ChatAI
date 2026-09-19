package com.zcw.chatai.data.provider

/**
 * 工具后端模型优先级（纯函数，JVM 可测）。
 *
 * 背景：图搜（文搜图/图搜图）借道 Qwen 的 Responses 原生工具，效果**按模型而变**——
 * 实测 `qwen3.8-flash` 对部分图片返回空结果，`qwen3.8-27b` / `qwen3.8-max` 正常。
 * 所以图搜不再跟随「通义千问条目里的对话模型」，而是用一条独立的优先级链：
 * 先 `qwen3.8-27b`，空结果或报错再退到 `qwen3.8-max`（见 `ToolFallbackChain`）。
 *
 * 设置页可覆盖这条链；留空/非法时回退到供应商预设里的默认值。
 */
object ToolModels {

    /** 一条链最多几个模型：限制最坏情况的调用次数与计费。 */
    const val MAX = 3

    private val SEPARATORS = charArrayOf(',', '，', ' ', '\n', '\r', '\t')

    /** 解析用户填写的模型串（逗号/空白分隔），trim、去重、丢弃空项。 */
    fun parse(raw: String?): List<String> = raw.orEmpty()
        .split(*SEPARATORS)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    /** 生效的模型链：用户覆盖优先，空则用预设默认。 */
    fun resolve(raw: String?, presetDefault: List<String>): List<String> =
        parse(raw).take(MAX).ifEmpty { presetDefault.take(MAX) }

    /** 设置页校验：至少一个、最多 [MAX] 个（空串合法，表示用内置默认）。 */
    fun validate(raw: String): String? {
        val models = parse(raw)
        if (models.isEmpty()) return null
        if (models.size > MAX) return "最多 $MAX 个模型"
        return null
    }
}
