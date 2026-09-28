package com.zcw.chatai.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.zcw.chatai.ui.theme.LocalDarkTheme
import com.zcw.chatai.ui.theme.LocalThemeFamily

/**
 * 最后一组的高度占位。只包尾部那一项：视口、字号、主题和缓存查询不进其它行。
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
    var measuredPx by remember(groupKey) { mutableIntStateOf(0) }
    val reserve = reserveTailHeight(cachedPx, measuredPx, streaming)
    LaunchedEffect(located, streaming, measuredPx, cachedPx, groupKey, contentChars, widthPx, fontMilli, themeName, dark) {
        if (!shouldRecordTailHeight(located, streaming, measuredPx, cachedPx)) return@LaunchedEffect
        store.record(
            conversationId,
            TailLayout(
                groupKey = groupKey,
                heightPx = measuredPx,
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
        Box(Modifier.onSizeChanged { measuredPx = it.height }) {
            content()
        }
    }
}
