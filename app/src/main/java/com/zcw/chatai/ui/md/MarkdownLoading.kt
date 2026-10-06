package com.zcw.chatai.ui.md

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 有多少段定稿 markdown 还停在解析的 Loading。
 *
 * 不关心谁在等。聊天列表的最后一组是目前唯一的提供者；其它行读到 null，不会为它重组。
 * 流式正文不调用 [TrackMarkdownLoading]。
 */
class MarkdownLoading {
    private val pending = mutableIntStateOf(0)

    val count: Int get() = pending.intValue

    fun enter() {
        pending.intValue++
    }

    fun exit() {
        check(pending.intValue > 0)
        pending.intValue--
    }
}

val LocalMarkdownLoading = staticCompositionLocalOf<MarkdownLoading?> { null }

/** 还在 Loading 时记一笔，离开 Loading 或离开组合时销掉。没有人在等就不做。 */
@Composable
fun TrackMarkdownLoading(isLoading: Boolean) {
    val loading = LocalMarkdownLoading.current ?: return
    if (!isLoading) return
    DisposableEffect(loading) {
        loading.enter()
        onDispose { loading.exit() }
    }
}
