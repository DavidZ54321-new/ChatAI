package com.zcw.chatai.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 聊天界面的视觉度量。两个比例都相对「手机视窗」而不是相对父容器，
 * 这样分屏 / 折叠 / 旋转时观感一致，也方便纯函数单测。
 */
object ChatMetrics {

    /** 消息里图片缩略图的边长 = 视窗宽度的 20%。单图多图统一，超宽就横向滑动。 */
    const val THUMB_FRACTION = 0.2f

    /** 底部消散带高度 = 视窗高度的 10%（就是系统隐形导航栏那一带）。 */
    const val BOTTOM_DISSOLVE_FRACTION = 0.10f

    fun thumbnailSide(windowWidth: Dp): Dp =
        (windowWidth * THUMB_FRACTION).coerceAtLeast(1.dp)

    fun bottomDissolve(windowHeight: Dp): Dp =
        (windowHeight * BOTTOM_DISSOLVE_FRACTION).coerceAtLeast(1.dp)
}
