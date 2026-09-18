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

private const val COMPOSE_SETTLE_MS = 64L

/**
 * 滚到最后一项真正出现在视口里。
 *
 * 每次跳转后等一拍，让下一屏 Markdown 量完高；只等一帧的话会假到底，
 * 人停在中途的用户气泡上。用户上滑松钉后 [stillPinned] 立刻停，不再抢滚动。
 */
suspend fun LazyListState.scrollToEnd(stillPinned: () -> Boolean = { true }) {
    repeat(40) {
        if (!stillPinned()) return
        val lastIndex = layoutInfo.totalItemsCount - 1
        if (lastIndex < 0) {
            delay(COMPOSE_SETTLE_MS)
            return@repeat
        }
        scrollToItem(lastIndex)
        val last = layoutInfo.visibleItemsInfo.lastOrNull()
        if (last != null) {
            val extra = last.offset + last.size - layoutInfo.viewportEndOffset
            if (extra > 0) scrollBy(extra.toFloat())
        }
        delay(COMPOSE_SETTLE_MS)
        val still = layoutInfo.visibleItemsInfo.lastOrNull()
        if (isChatListAtBottom(still?.index, layoutInfo.totalItemsCount, canScrollForward)) return
    }
}
