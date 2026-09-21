package com.zcw.chatai.data.media

/**
 * 围栏 SVG 的动效分类：决定预览页给不给「保存 GIF」按钮。
 *
 * 只有 SMIL（`<animate>` 系）和 CSS（`@keyframes`）能在 `<img>` 里自播、
 * 被 canvas 抽帧；`<script>` 驱动的在 `<img>` 里不跑，做不出 GIF。
 * 纯字符串扫描（仓库约定不用 `Regex`），JVM 单测覆盖。
 */
object SvgAnimation {

    enum class Kind {
        /** 静态：只有「保存 PNG」。 */
        NONE,

        /** SMIL 或 CSS 动效：可抽帧，给「保存 GIF」（冻帧则导出时降级 PNG）。 */
        SMIL_OR_CSS,

        /** 只有脚本动效：`<img>` 里不播，不给 GIF 按钮。 */
        SCRIPT_ONLY,
    }

    fun kind(code: String): Kind {
        val lower = code.lowercase()
        if (hasSmil(lower) || hasCssAnimation(lower)) return Kind.SMIL_OR_CSS
        if ("<script" in lower) return Kind.SCRIPT_ONLY
        return Kind.NONE
    }

    private fun hasSmil(lower: String): Boolean =
        "<animate" in lower ||
            "<animatetransform" in lower ||
            "<animatemotion" in lower ||
            "<animatecolor" in lower ||
            "<set" in lower

    /**
     * CSS 动画：`@keyframes`，或 `animation:` 声明且取值不是 `none`。
     * `animation: none` 是常见的「关掉动画」写法，不该给 GIF 入口。
     */
    private fun hasCssAnimation(lower: String): Boolean {
        if ("@keyframes" in lower) return true
        var index = lower.indexOf(CSS_ANIMATION)
        while (index >= 0) {
            val value = lower.substring(index + CSS_ANIMATION.length).trimStart()
            if (!value.startsWith("none")) return true
            index = lower.indexOf(CSS_ANIMATION, index + 1)
        }
        return false
    }

    private const val CSS_ANIMATION = "animation:"
}
