package com.zcw.chatai.ui.chat

/**
 * 悬浮菜单左上角，单位是窗口像素。
 *
 * 水平以按压点为中心，再夹进左右 [margin]。
 * 垂直优先放在按压点下方（隔开 [gap]）；底边越过下界时翻到按压点上方。
 * 下界是窗口底边距和 [bottomLimit] 里更靠上的那个（输入栏顶边传进来，避免盖住 Composer）。
 * 上界是 [topLimit]（状态栏底再加边距）。上下都放不下时夹进安全区。
 */
internal data class MenuPlacement(val x: Int, val y: Int)

internal fun placeContextMenu(
    anchorX: Int,
    anchorY: Int,
    menuWidth: Int,
    menuHeight: Int,
    windowWidth: Int,
    windowHeight: Int,
    margin: Int,
    gap: Int,
    bottomLimit: Int = windowHeight - margin,
    topLimit: Int = margin,
): MenuPlacement {
    val edge = margin.coerceAtLeast(0)
    val top = topLimit.coerceAtLeast(0)
    val bottom = minOf(bottomLimit, windowHeight - edge).coerceAtLeast(top)
    val maxX = (windowWidth - edge - menuWidth).coerceAtLeast(edge)
    val x = (anchorX - menuWidth / 2).coerceIn(edge, maxX)

    val belowTop = anchorY + gap
    val aboveTop = anchorY - gap - menuHeight
    val y = when {
        belowTop + menuHeight <= bottom -> belowTop
        aboveTop >= top -> aboveTop
        else -> aboveTop.coerceIn(top, (bottom - menuHeight).coerceAtLeast(top))
    }
    return MenuPlacement(x, y)
}
