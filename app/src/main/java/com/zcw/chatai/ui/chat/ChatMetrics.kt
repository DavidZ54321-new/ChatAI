package com.zcw.chatai.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

/**
 * 聊天界面的视觉度量。比例都相对「手机视窗」而不是相对父容器，
 * 这样分屏 / 折叠 / 旋转时观感一致，也方便纯函数单测。
 */
object ChatMetrics {

    /** 消息里图片缩略图的边长 = 视窗宽度的 20%。单图多图统一，超宽就横向滑动。 */
    const val THUMB_FRACTION = 0.2f

    /**
     * 顶栏按钮行本身完全不透明，消散只发生在按钮下沿之后。
     * 尾巴高度 = 视窗高 4%。
     */
    const val TOP_DISSOLVE_TAIL_FRACTION = 0.04f

    /**
     * 底部消散引导带 = 视窗高 3%，贴在 Composer 上沿，
     * 不再用 10% 整条架在消息正文上。
     */
    const val BOTTOM_DISSOLVE_LEAD_FRACTION = 0.03f

    /** [FloatingTopControls]：上下 6.dp padding + 44.dp 按钮。 */
    val TOP_BAR_CONTENT: Dp = 56.dp

    /** markdown 图行里单张图的高度 = 视窗宽 42%（与块级公式一样横向滚动）。 */
    const val MARKDOWN_IMAGE_HEIGHT_FRACTION = 0.42f

    /** 图行里单张图的最大宽度 = 视窗宽 80%（超宽的一行只能滑一张）。 */
    const val MARKDOWN_IMAGE_MAX_WIDTH_FRACTION = 0.8f

    /** 单张图的高度上限 = 视窗高 55%（竖长图不占满整屏，横图仍由宽度上限约束）。 */
    const val MARKDOWN_SINGLE_IMAGE_MAX_HEIGHT_FRACTION = 0.55f

    /**
     * 展开块（思考过程 / 工具调用结果）的高度上限 = 视窗高 30%；
     * 超出部分在块内滚动，不把整条消息撑长（也不穿透外层列表）。
     */
    const val EXPANDED_BLOCK_MAX_HEIGHT_FRACTION = 0.30f

    /** 加载占位的最小宽度，避免行内出现「什么都没有」的空档。 */
    val MARKDOWN_IMAGE_MIN_WIDTH: Dp = 120.dp

    data class DissolveBand(val height: Dp, val opaqueStop: Float)

    fun thumbnailSide(windowWidth: Dp): Dp =
        (windowWidth * THUMB_FRACTION).coerceAtLeast(1.dp)

    fun markdownImageHeight(windowWidth: Dp): Dp =
        (windowWidth * MARKDOWN_IMAGE_HEIGHT_FRACTION).coerceAtLeast(1.dp)

    fun markdownImageMaxWidth(windowWidth: Dp): Dp =
        (windowWidth * MARKDOWN_IMAGE_MAX_WIDTH_FRACTION).coerceAtLeast(1.dp)

    /** 单张图的高度上限；视窗高未知时返回 [Dp.Unspecified]（调用方的 heightIn 会忽略它）。 */
    fun markdownSingleImageMaxHeight(windowHeight: Dp): Dp {
        if (!windowHeight.isSpecified || windowHeight.value <= 0f) return Dp.Unspecified
        return (windowHeight * MARKDOWN_SINGLE_IMAGE_MAX_HEIGHT_FRACTION).coerceAtLeast(1.dp)
    }

    /** 展开块的高度上限；视窗高未知时返回 [Dp.Unspecified]（heightIn 会忽略它）。 */
    fun expandedBlockMaxHeight(windowHeight: Dp): Dp {
        if (!windowHeight.isSpecified || windowHeight.value <= 0f) return Dp.Unspecified
        return (windowHeight * EXPANDED_BLOCK_MAX_HEIGHT_FRACTION).coerceAtLeast(1.dp)
    }

    /**
     * 单张 block 图的显示尺寸：保持宽高比，装进「视窗宽 80% × 视窗高 55%」的框。
     * [aspect] = 宽 / 高；未测量（<= 0）或视窗高未知时只返回宽度上限、高度给 0，
     * 由调用方退回「宽度封顶 + 占位最小高度」。
     */
    fun markdownSingleImageSize(aspect: Float, windowWidth: Dp, windowHeight: Dp): DpSize {
        val maxWidth = markdownImageMaxWidth(windowWidth)
        if (aspect <= 0f || !windowHeight.isSpecified || windowHeight.value <= 0f) {
            return DpSize(maxWidth, 0.dp)
        }
        val maxHeight = markdownSingleImageMaxHeight(windowHeight)
        val width = minOf(maxWidth.value, maxHeight.value * aspect).coerceAtLeast(1f)
        return DpSize(width.dp, (width / aspect).dp)
    }

    fun topDissolve(windowHeight: Dp, statusBar: Dp): DissolveBand {
        val tail = (windowHeight * TOP_DISSOLVE_TAIL_FRACTION).coerceAtLeast(1.dp)
        val height = statusBar + TOP_BAR_CONTENT + tail
        return DissolveBand(height, opaqueStop(statusBar + TOP_BAR_CONTENT, height))
    }

    fun bottomDissolve(windowHeight: Dp, composerStack: Dp): DissolveBand {
        val lead = (windowHeight * BOTTOM_DISSOLVE_LEAD_FRACTION).coerceAtLeast(1.dp)
        val height = composerStack + lead
        return DissolveBand(height, opaqueStop(lead, height))
    }

    private fun opaqueStop(part: Dp, total: Dp): Float {
        val t = total.value
        if (t <= 0f) return 1f
        return (part.value / t).coerceIn(0f, 1f)
    }
}
