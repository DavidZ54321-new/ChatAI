package com.zcw.chatai.ui.drawer

import com.zcw.chatai.data.model.Conversation

/**
 * 会话之间的分支关系查询（纯逻辑，JVM 单测覆盖）。
 *
 * 会话列表是**平铺**的（不再缩进），层级只在「分支页」里逐层查看，
 * 所以这里只回答「谁是谁的直接分支」这类问题，不再做渲染用的树形排布。
 */
object ConversationTree {

    /** [parentId] 的直接下辖分支，按最近活跃排序。空父指针不参与匹配。 */
    fun childrenOf(conversations: List<Conversation>, parentId: String): List<Conversation> {
        if (parentId.isBlank()) return emptyList()
        return conversations
            // 自指（`parent == 自己`）是损坏数据：列进去只会得到一个「自己开自己」的空页。
            .filter { it.parentConversationId == parentId && it.id != parentId }
            .sortedByDescending { it.updatedAt }
    }

    /**
     * 每个会话的下辖分支数（会话列表的长按菜单用它决定要不要显示「下辖分支（N）」）。
     * 父会话已不在列表里的分支、以及自指的分支都不计入——否则会给一条会话
     * 「凭空」记上分支（点进去还是空页）。
     */
    fun childCounts(conversations: List<Conversation>): Map<String, Int> {
        if (conversations.isEmpty()) return emptyMap()
        val known = conversations.mapTo(HashSet()) { it.id }
        val counts = HashMap<String, Int>()
        for (conversation in conversations) {
            val parent = conversation.parentConversationId
            if (parent.isBlank() || parent == conversation.id || parent !in known) continue
            counts[parent] = (counts[parent] ?: 0) + 1
        }
        return counts
    }
}
