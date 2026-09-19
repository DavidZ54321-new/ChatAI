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

private const val COMPOSE_SETTLE_MS = 64L

/**
 * **跳**到末尾：切会话 / 刚发出用户消息 / 点「回到底部」用。
 *
 * 每次跳转后等一拍，让下一屏 Markdown 量完高；只等一帧的话会假到底，
 * 人停在中途的用户气泡上。用户上滑松钉后 [stillPinned] 立刻停，不再抢滚动。
 *
 * 返回值 = 是否真的到达底部：定位门用它判断「这次定位算不算完成」，但**不在到底时也要露出列表**。
 *
 * 注意：这里会把最后一项**顶部**对到视口顶再补溢出，是一次「重定位」——只适合跳转，
 * 流式跟随请用 [followToEnd]（否则每个增量都会瞬移一下）。
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
 * **跟**上末尾：流式增量 / 工具结果增长时用。
 *
 * 与 [jumpToEnd] 的区别是**不做重定位**——只在内容变高、底部溢出时 `scrollBy` 补回底部，
 * 所以不会把最后一项（整个 assistant 回合）的顶部瞬移到视口顶，也就不再「被拽到用户气泡」。
 * 只有当最后一项还没组合进视口（首屏兜底）时才跳一次。
 */
suspend fun LazyListState.followToEnd(stillPinned: () -> Boolean = { true }) {
    repeat(6) {
        if (!stillPinned()) return
        val lastIndex = layoutInfo.totalItemsCount - 1
        if (lastIndex < 0) return
        val last = layoutInfo.visibleItemsInfo.lastOrNull()
        if (last == null || last.index < lastIndex) {
            // 最后一项还没进视口：没有可补的溢出，只能跳一次。
            scrollToItem(lastIndex)
        } else {
            val overflow = bottomOverflow(last.offset, last.size, layoutInfo.viewportEndOffset)
            if (overflow > 0) scrollBy(overflow.toFloat())
        }
        delay(COMPOSE_SETTLE_MS)
        val still = layoutInfo.visibleItemsInfo.lastOrNull()
        if (isChatListAtBottom(still?.index, layoutInfo.totalItemsCount, canScrollForward)) return
    }
}
