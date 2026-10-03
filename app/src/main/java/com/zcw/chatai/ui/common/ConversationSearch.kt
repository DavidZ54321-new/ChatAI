package com.zcw.chatai.ui.common

import com.zcw.chatai.data.model.Conversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * 会话列表的搜索词与结果。
 * 空串（含纯空白）不过滤，直接订 [all]；有词才调用 [search]。
 * 种类由 [search] 的实现限定，这里不关心对话、生图还是视频。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSearch(
    all: Flow<List<Conversation>>,
    search: (String) -> Flow<List<Conversation>>,
    scope: CoroutineScope,
) {
    private val query = MutableStateFlow("")

    val searchQuery: StateFlow<String> = query.asStateFlow()

    val results: StateFlow<List<Conversation>> = query
        .flatMapLatest { text -> if (text.isBlank()) all else search(text) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        query.value = value
    }
}
