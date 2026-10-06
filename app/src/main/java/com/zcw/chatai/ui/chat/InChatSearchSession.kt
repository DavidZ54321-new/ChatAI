package com.zcw.chatai.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlin.math.abs

/** 定位点报上来的窗口垂直中心。只保留当前命中的最新一次。 */
private data class SearchSight(
    val messageId: String,
    val segment: Int,
    val start: Int,
    val y: Float,
)

/**
 * 会话内搜索的视图状态。开关本身由调用方持有，因为焦点门禁要比这份状态更早读到它。
 * 锚点、命中和「把当前词送进可视区域中线」都在这里，不进聊天页的组合函数。
 */
class InChatSearchSession internal constructor(
    val open: Boolean,
    val query: String,
    val hitsByMessage: Map<String, List<InChatHit>>,
    val active: InChatHit?,
    val countLabel: String?,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val hitCount: Int,
    val focus: FocusRequester,
    val onCenter: InChatCenterReport,
    val onQuery: (String) -> Unit,
    val step: (Int) -> Unit,
    val close: () -> Unit,
    val toggle: () -> Unit,
    val onControlsBottom: (Float) -> Unit,
    val onBarTop: (Float) -> Unit,
)

@Composable
fun rememberInChatSearch(
    openState: MutableState<Boolean>,
    messages: List<ChatMessageItem>,
    conversationId: String?,
    listState: LazyListState,
    groups: List<MessageGroup>,
    hasBranchHeader: Boolean,
    unpin: () -> Unit,
): InChatSearchSession {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var anchor by remember { mutableStateOf<InChatHit?>(null) }
    var fallback by remember { mutableIntStateOf(0) }
    var scrollToken by remember { mutableIntStateOf(0) }
    val regionTop = remember { mutableFloatStateOf(Float.NaN) }
    val regionBottom = remember { mutableFloatStateOf(Float.NaN) }
    val sight = remember { mutableStateOf<SearchSight?>(null) }
    val open = openState.value

    fun close() {
        openState.value = false
        query = ""
        anchor = null
        fallback = 0
        regionBottom.floatValue = Float.NaN
        sight.value = null
        focusManager.clearFocus()
        keyboard?.hide()
    }

    fun onQuery(next: String) {
        query = next
        anchor = null
        fallback = 0
        if (next.isNotBlank()) scrollToken++
    }

    val hits = remember(messages, query, open) {
        if (!open || query.isBlank()) emptyList() else InChatSearch.find(messages, query)
    }
    val hitsByMessage = remember(hits) { hits.groupBy { it.messageId } }
    val index = if (hits.isEmpty()) {
        -1
    } else {
        InChatSearch.resolveIndex(hits, anchor?.messageId, anchor?.segment ?: 0, anchor?.start ?: 0, fallback)
    }
    val active = hits.getOrNull(index)
    // 查询一落地就把第一处钉住。后面流式只在末尾追加时，选择器不跳回新出现的更早命中。
    SideEffect {
        if (open && anchor == null && hits.isNotEmpty()) {
            anchor = hits.first()
            fallback = 0
        }
    }

    fun step(delta: Int) {
        val next = InChatSearch.step(index, hits.size, delta)
        if (next < 0 || next == index) return
        anchor = hits[next]
        fallback = next
        scrollToken++
    }

    val latestActive = rememberUpdatedState(active)
    val onCenter = remember<InChatCenterReport> {
        report@{ messageId, segment, start, y ->
            val hit = latestActive.value ?: return@report
            if (hit.messageId != messageId || hit.segment != segment || hit.start != start) return@report
            val previous = sight.value
            if (previous != null &&
                previous.messageId == messageId &&
                previous.segment == segment &&
                previous.start == start &&
                abs(previous.y - y) <= 0.5f
            ) {
                return@report
            }
            sight.value = SearchSight(messageId, segment, start, y)
        }
    }

    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        withFrameNanos { }
        if (!openState.value) return@LaunchedEffect
        val focused = runCatching { focus.requestFocus() }.isSuccess
        if (focused && openState.value) keyboard?.show()
    }
    LaunchedEffect(conversationId) {
        if (openState.value) close()
    }

    val hitNow = rememberUpdatedState(active)
    val groupsNow = rememberUpdatedState(groups)
    val branchNow = rememberUpdatedState(hasBranchHeader)
    val openNow = rememberUpdatedState(open)
    val conversationNow = rememberUpdatedState(conversationId)
    val unpinNow = rememberUpdatedState(unpin)
    LaunchedEffect(scrollToken) {
        if (scrollToken == 0 || !openNow.value) return@LaunchedEffect
        val startedOn = conversationNow.value
        unpinNow.value()
        centerOnSearchHit(
            listState = listState,
            still = { openNow.value && conversationNow.value == startedOn },
            hit = { hitNow.value },
            sight = { sight.value },
            regionTop = { regionTop.floatValue },
            regionBottom = { regionBottom.floatValue },
            lazyIndex = index@{
                val target = hitNow.value ?: return@index null
                InChatSearch.messageLazyIndex(groupsNow.value, target.messageId, branchNow.value)
            },
        )
    }

    val countLabel = when {
        !open || query.isBlank() -> null
        hits.isEmpty() -> "无结果"
        else -> "${index + 1}/${hits.size}"
    }
    return InChatSearchSession(
        open = open,
        query = query,
        hitsByMessage = hitsByMessage,
        active = active,
        countLabel = countLabel,
        canPrevious = index > 0,
        canNext = index >= 0 && index < hits.lastIndex,
        hitCount = hits.size,
        focus = focus,
        onCenter = onCenter,
        onQuery = ::onQuery,
        step = ::step,
        close = ::close,
        toggle = {
            if (openState.value) close() else {
                focusManager.clearFocus()
                openState.value = true
            }
        },
        onControlsBottom = { regionTop.floatValue = it },
        onBarTop = { regionBottom.floatValue = it },
    )
}

/**
 * 把当前命中送进顶栏下沿和搜索条上沿之间的中线。
 * 测量只要身份对得上就用，包括循环开始前已经报过的那一次。
 * 滚完若定位点还停在滚之前的位置，用滚动量估计新位置，避免拿旧坐标再滚一遍。
 * 到顶或到底就停，不垫空白。
 */
private suspend fun centerOnSearchHit(
    listState: LazyListState,
    still: () -> Boolean,
    hit: () -> InChatHit?,
    sight: () -> SearchSight?,
    regionTop: () -> Float,
    regionBottom: () -> Float,
    lazyIndex: () -> Int?,
) {
    var broughtIntoView = false
    var stable = 0
    var staleY = Float.NaN
    var estimatedY = Float.NaN
    for (_frame in 0 until 90) {
        if (!still()) return
        val target = hit() ?: return
        val top = regionTop()
        val bottom = regionBottom()
        val seen = sight()?.takeIf { it.samePlace(target) }
        if (top.isNaN() || bottom.isNaN() || bottom <= top || seen == null) {
            val index = lazyIndex()
            if (index != null && !broughtIntoView &&
                listState.layoutInfo.visibleItemsInfo.none { it.index == index }
            ) {
                listState.scrollToItem(index)
                broughtIntoView = true
                staleY = Float.NaN
            }
            stable = 0
            withFrameNanos { }
            continue
        }
        val y = if (!staleY.isNaN() && abs(seen.y - staleY) <= 0.5f) estimatedY else seen.y
        if (!staleY.isNaN() && abs(seen.y - staleY) > 0.5f) staleY = Float.NaN
        val delta = InChatSearch.centerDelta(y, top, bottom)
        if (abs(delta) <= 2f) {
            stable++
            if (stable >= 3 && !centerDrifted(target, sight, regionTop, regionBottom)) return
            if (stable >= 3) stable = 0
            withFrameNanos { }
            continue
        }
        stable = 0
        val consumed = listState.scrollBy(delta)
        val canMore = if (delta > 0f) listState.canScrollForward else listState.canScrollBackward
        if (InChatSearch.scrollBlocked(delta, consumed, canMore)) return
        if (abs(consumed) >= 0.5f) {
            staleY = seen.y
            estimatedY = seen.y - consumed
        }
        withFrameNanos { }
    }
}

/** 刚觉得居中之后，定位点若又挪开了中线，就还得再滚。 */
private suspend fun centerDrifted(
    target: InChatHit,
    sight: () -> SearchSight?,
    regionTop: () -> Float,
    regionBottom: () -> Float,
): Boolean {
    val baseline = sight()?.takeIf { it.samePlace(target) }?.y
    repeat(8) {
        withFrameNanos { }
        val seen = sight()?.takeIf { it.samePlace(target) } ?: return@repeat
        if (baseline != null && abs(seen.y - baseline) <= 0.5f) return@repeat
        val top = regionTop()
        val bottom = regionBottom()
        if (top.isNaN() || bottom.isNaN() || bottom <= top) return true
        if (abs(InChatSearch.centerDelta(seen.y, top, bottom)) > 2f) return true
    }
    return false
}

private fun SearchSight.samePlace(hit: InChatHit): Boolean =
    messageId == hit.messageId && segment == hit.segment && start == hit.start
