package com.zcw.chatai.ui.branch

/**
 * 分支页的下钻轨迹（纯逻辑，JVM 单测覆盖）：末尾是当前所在的会话，前面的都是它一路上来的祖先。
 *
 * 单独抽出来是因为这几个转换（下钻 / 返回 / 面包屑跳转）在 Composable 里没法做 JVM 测试，
 * 而「数据被手工改出环时不能无限加深」「退到起点才关页面」这类规则必须被钉住。
 */
object BranchTrail {

    fun start(rootId: String): List<String> = listOf(rootId)

    /** 下钻一层；目标已在本页轨迹里（成环）或为空时原样返回。 */
    fun drill(trail: List<String>, id: String): List<String> =
        if (id.isBlank() || id in trail) trail else trail + id

    /** 返回一层；已经在起点则返回 null，调用方据此关掉页面。 */
    fun back(trail: List<String>): List<String>? =
        if (trail.size > 1) trail.dropLast(1) else null

    /** 面包屑点第 [index] 段：只对更早的段生效，点当前段是空操作。 */
    fun pick(trail: List<String>, index: Int): List<String> =
        if (index in 0 until trail.lastIndex) trail.take(index + 1) else trail

    fun current(trail: List<String>): String? = trail.lastOrNull()
}
