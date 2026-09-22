package com.zcw.chatai.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.delay

/**
 * 是否真的贴在列表末尾。
 *
 * 不能只看 [LazyListState.canScrollForward]：LazyColumn 没组合到的变高 item
 * （长 Markdown）高度是估的，常常偏短，会在中途就报「不能再往下」。
 * 必须最后一项已经出现在可见列表里，并且此时不能再往下滚。
 */
fun isChatListAtBottom(
    lastVisibleIndex: Int?,
    totalItems: Int,
    canScrollForward: Boolean,
): Boolean {
    if (totalItems <= 0) return true
    return lastVisibleIndex != null &&
        lastVisibleIndex >= totalItems - 1 &&
        !canScrollForward
}

/**
 * 底部溢出：最后一项下沿超出视口下沿的像素数，已贴底时为 0（纯函数，JVM 可测）。
 */
fun bottomOverflow(lastOffset: Int, lastSize: Int, viewportEndOffset: Int): Int =
    (lastOffset + lastSize - viewportEndOffset).coerceAtLeast(0)

/**
 * 贴底跟滑是否允许向前补滚（纯函数，JVM 可测）。
 *
 * 两条门槛，缺一不可：
 * 1. **可见末项离末尾还差两项以上 = 用户在读历史**，绝不动（哪怕 pinned 状态异常）；
 * 2. **已无可滚余量 = 已贴底**，无需动。
 *
 * 「最底部才随生成下滑，其余时刻不自动跳转」的可测表达。
 */
fun canFollowScroll(
    lastVisibleIndex: Int?,
    totalItems: Int,
    canScrollForward: Boolean,
): Boolean {
    if (totalItems <= 0 || lastVisibleIndex == null) return false
    // 末项是 disclaimer；lastIndex-1 = 最后一组消息。贴着它或它之后才算「近末尾」。
    if (lastVisibleIndex < totalItems - 2) return false
    return canScrollForward
}

private const val COMPOSE_SETTLE_MS = 64L

/** 贴底跟滑时一次向前补的像素上限（会被 maxScroll 钳住，等价于「滚到当前真末尾」）。 */
private const val FOLLOW_CATCHUP_PX = 1 shl 24

/**
 * **跳**到末尾：切会话 / 刚发出用户消息 / 点「回到底部」用——都是用户显式动作。
 *
 * `scrollToItem(末项)` 把列表钳到 maxScroll（真末尾），随后补一次可见末项的正向溢出，
 * 再等一拍让下一屏 Markdown 量完高（估高偏短会假到底，下一拍重试）。
 * 用户上滑松钉后 [stillPinned] 立刻停。
 *
 * 返回值 = 是否真的到达底部：定位门用它判断「这次定位算不算完成」，但**不在到底时也要露出列表**。
 */
suspend fun LazyListState.jumpToEnd(stillPinned: () -> Boolean = { true }): Boolean {
    repeat(40) {
        if (!stillPinned()) return false
        val lastIndex = layoutInfo.totalItemsCount - 1
        if (lastIndex < 0) {
            delay(COMPOSE_SETTLE_MS)
            return@repeat
        }
        scrollToItem(lastIndex)
        val last = layoutInfo.visibleItemsInfo.lastOrNull()
        if (last != null) {
            val overflow = bottomOverflow(last.offset, last.size, layoutInfo.viewportEndOffset)
            if (overflow > 0) scrollBy(overflow.toFloat())
        }
        delay(COMPOSE_SETTLE_MS)
        val still = layoutInfo.visibleItemsInfo.lastOrNull()
        if (isChatListAtBottom(still?.index, layoutInfo.totalItemsCount, canScrollForward)) return true
    }
    val last = layoutInfo.visibleItemsInfo.lastOrNull()
    return isChatListAtBottom(last?.index, layoutInfo.totalItemsCount, canScrollForward)
}

/**
 * **跟**上末尾：流式增量 / 工具结果增长时用，且**只在贴底跟随时才会被调用**。
 *
 * 两条铁律（用户正在看内容就不能被打扰）：
 * 1. **只向前补，绝不重定位**——`scrollToItem` 会把末项顶对到视口顶并回填一屏，
 *    短消息时正好把视口拽回用户气泡（本 bug 的根因）。`scrollBy` 只吃前向余量。
 * 2. **可见末项离末尾还差两项以上 = 用户在读历史，直接收手**，哪怕 pinned 状态异常也不动。
 *
 * 已贴底（无前向余量）就 return：任何时候都不自动跳转。
 */
suspend fun LazyListState.followToEnd(stillPinned: () -> Boolean = { true }) {
    repeat(6) {
        if (!stillPinned()) return
        val info = layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        if (!canFollowScroll(last?.index, info.totalItemsCount, canScrollForward)) return
        // 向前吃掉剩余前向余量（被 maxScroll 钳住）：覆盖「disclaimer/新步骤刚滑出视口」的
        // 尾部补滚，也绝不会把视口往回拽（scrollToItem 重定位才是滚回用户气泡的元凶）。
        scrollBy(FOLLOW_CATCHUP_PX.toFloat())
        delay(COMPOSE_SETTLE_MS)
    }
}
