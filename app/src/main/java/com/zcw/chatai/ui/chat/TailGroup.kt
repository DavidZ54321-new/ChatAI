package com.zcw.chatai.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.zcw.chatai.ui.md.LocalMarkdownLoading
import com.zcw.chatai.ui.md.MarkdownLoading
import com.zcw.chatai.ui.theme.LocalDarkTheme
import com.zcw.chatai.ui.theme.LocalThemeFamily

/**
 * 最后一组的高度占位。只包尾部那一项：视口、字号、主题和缓存查询不进其它行。
 *
 * 占位只盖住冷启动时 markdown 还在加载、实测还没出来的那几帧。
 * 定稿后的摆放高度以 [onPlaced] 为准，比缓存矮也写回去——展开后收起、回合结束变矮
 * 都不能再把最小高度粘住，否则操作行和免责声明之间会留一块空白。
 */
@Composable
fun TailGroup(
    conversationId: String,
    groupKey: String,
    contentChars: Int,
    streaming: Boolean,
    located: Boolean,
    store: TailLayoutStore,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val widthPx = LocalWindowInfo.current.containerSize.width
    val fontMilli = (LocalDensity.current.fontScale * 1000f).toInt()
    val themeName = LocalThemeFamily.current.name
    val dark = LocalDarkTheme.current
    val cachedPx = if (streaming) {
        null
    } else {
        store.lookup(conversationId, groupKey, contentChars, widthPx, fontMilli, themeName, dark)
    }
    val loading = remember(groupKey) { MarkdownLoading() }
    // 组合期读一次：Loading 结束会重组，高度没变时 onPlaced 也会再跑。定稿不用这个快照。
    @Suppress("UNUSED_VARIABLE")
    val markdownLoadingEpoch = loading.count
    var measuredPx by remember(groupKey) { mutableIntStateOf(0) }
    // 正文字数变了就作废：上一份正文的定稿高度不能拿来给新正文占位。
    var settledPx by remember(groupKey, contentChars) { mutableIntStateOf(0) }
    val reserve = reserveTailHeight(cachedPx, measuredPx, streaming, settled = settledPx > 0)
    LaunchedEffect(located, streaming, settledPx, groupKey, contentChars, widthPx, fontMilli, themeName, dark) {
        if (!shouldRecordTailHeight(located, streaming, settledPx)) return@LaunchedEffect
        store.record(
            conversationId,
            TailLayout(
                groupKey = groupKey,
                heightPx = settledPx,
                contentChars = contentChars,
                widthPx = widthPx,
                fontScaleMilli = fontMilli,
                themeFamily = themeName,
                dark = dark,
            ),
        )
    }
    val density = LocalDensity.current
    Box(
        modifier.then(
            if (reserve && cachedPx != null) {
                Modifier.heightIn(min = with(density) { cachedPx.toDp() })
            } else {
                Modifier
            },
        ),
    ) {
        CompositionLocalProvider(LocalMarkdownLoading provides loading) {
            // 摆放之后才记高度。定稿只看这一刻的加载计数，不用组合期拍下的快照。
            Box(
                Modifier.onPlaced { coordinates ->
                    val height = coordinates.size.height
                    measuredPx = height
                    if (!streaming && loading.count == 0 && height > 0) {
                        settledPx = height
                    }
                },
            ) {
                content()
            }
        }
    }
}
