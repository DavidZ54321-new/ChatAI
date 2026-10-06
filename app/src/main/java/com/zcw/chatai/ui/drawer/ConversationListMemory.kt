package com.zcw.chatai.ui.drawer

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** 未筛选的「最近」，或当前搜索词筛出来的那一份。两份滚动互不覆盖。 */
internal enum class ListPane { BROWSE, FILTERED }

/** 空白（含纯空白）是未筛选列表；有词才是筛选结果。 */
internal fun listPane(query: String): ListPane =
    if (query.isBlank()) ListPane.BROWSE else ListPane.FILTERED

/**
 * 筛选词换成另一个非空白词时，筛选列表回到顶部。
 * 清空词会切回未筛选列表，两份滚动都保持原样。
 */
internal fun shouldResetFilteredScroll(appliedQuery: String, query: String): Boolean =
    query.isNotBlank() && query != appliedQuery

/**
 * 搜索框可见：用户展开过，或词还在。
 * 词还在时框必须在，避免「框没了但列表仍被过滤」。
 */
internal fun searchFieldVisible(query: String, expanded: Boolean): Boolean =
    expanded || query.isNotBlank()

/**
 * 一个工作区的列表滚动与搜索展开。
 * 活在 [ConversationListMemoryViewModel] 里：列表页被拆掉后续上；进程死掉即丢，不落盘。
 */
class ConversationListSlot {
    val browse = LazyListState()
    val filtered = LazyListState()

    /** 用户点开过搜索框。收起搜索时由列表页置回 false。 */
    var expanded by mutableStateOf(false)

    /** 筛选滚动当前对应的搜索词。词变了才回顶。 */
    var appliedQuery: String = ""

    fun stateFor(query: String): LazyListState =
        if (listPane(query) == ListPane.BROWSE) browse else filtered
}

/**
 * 对话 / 生图 / 视频各一份槽。跟 Activity 走：旋转还在，划掉后台就丢。
 */
internal class ConversationListMemoryViewModel : ViewModel() {
    private val slots: Map<WorkspaceMode, ConversationListSlot> =
        WorkspaceMode.entries.associateWith { ConversationListSlot() }

    fun slot(mode: WorkspaceMode): ConversationListSlot = slots.getValue(mode)
}
