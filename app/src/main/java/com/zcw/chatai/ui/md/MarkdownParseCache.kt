package com.zcw.chatai.ui.md

import android.util.LruCache
import com.mikepenz.markdown.model.State

/**
 * 进程级 markdown 解析缓存。
 *
 * 库自带的 `rememberMarkdownState` 只在同一个 item 内保留解析结果；`LazyColumn` 把滚出
 * 屏幕的 item 销毁后，滚回来会重新解析。长会话来回滚动就是反复「解析 → 渲染」，这里按
 * 内容缓存 AST，命中时零解析成本。
 */
object MarkdownParseCache {

    private const val MAX_ENTRIES = 32

    private val cache = LruCache<String, State.Success>(MAX_ENTRIES)

    fun get(content: String): State.Success? = cache.get(content)

    fun put(content: String, state: State.Success) {
        cache.put(content, state)
    }
}
