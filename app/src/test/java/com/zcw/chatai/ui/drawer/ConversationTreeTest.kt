package com.zcw.chatai.ui.drawer

import com.zcw.chatai.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTreeTest {

    private fun conversation(id: String, parent: String = "", updatedAt: Long = 0L) = Conversation(
        id = id,
        title = id,
        model = "deepseek-flash",
        systemPrompt = null,
        createdAt = 0L,
        updatedAt = updatedAt,
        lastMessagePreview = "",
        messageCount = 0,
        isPinned = false,
        parentConversationId = parent,
    )

    // ---------- childrenOf ----------

    @Test
    fun childrenAreSortedByUpdatedAtDesc() {
        val conversations = listOf(
            conversation("root"),
            conversation("old", parent = "root", updatedAt = 10L),
            conversation("new", parent = "root", updatedAt = 20L),
        )
        assertEquals(
            listOf("new", "old"),
            ConversationTree.childrenOf(conversations, "root").map { it.id },
        )
    }

    /** 只给直接分支：孙辈要再下钻一层才看得到（分支页就是逐层下钻的）。 */
    @Test
    fun grandchildrenAreNotIncluded() {
        val conversations = listOf(
            conversation("root"),
            conversation("child", parent = "root"),
            conversation("grandchild", parent = "child"),
        )
        assertEquals(listOf("child"), ConversationTree.childrenOf(conversations, "root").map { it.id })
        assertEquals(listOf("grandchild"), ConversationTree.childrenOf(conversations, "child").map { it.id })
    }

    @Test
    fun leafAndUnknownParentsHaveNoChildren() {
        val conversations = listOf(conversation("root"), conversation("child", parent = "root"))
        assertTrue(ConversationTree.childrenOf(conversations, "child").isEmpty())
        assertTrue(ConversationTree.childrenOf(conversations, "ghost").isEmpty())
        assertTrue(ConversationTree.childrenOf(emptyList(), "root").isEmpty())
    }

    /** 空父指针不该互相认领（否则所有普通会话会变成彼此的「分支」）。 */
    @Test
    fun blankParentNeverMatches() {
        val conversations = listOf(conversation("a"), conversation("b"))
        assertTrue(ConversationTree.childrenOf(conversations, "").isEmpty())
    }

    // ---------- childCounts ----------

    @Test
    fun countsDirectChildrenPerConversation() {
        val conversations = listOf(
            conversation("root"),
            conversation("c1", parent = "root"),
            conversation("c2", parent = "root"),
            conversation("c3", parent = "c1"),
        )
        assertEquals(mapOf("root" to 2, "c1" to 1), ConversationTree.childCounts(conversations))
    }

    @Test
    fun conversationsWithoutBranchesAreAbsentFromCounts() {
        val conversations = listOf(conversation("a"), conversation("b"))
        assertTrue(ConversationTree.childCounts(conversations).isEmpty())
        assertTrue(ConversationTree.childCounts(emptyList()).isEmpty())
    }

    /**
     * 父会话被删之后留下的分支（平铺列表里它还在，带「分支」标签）：
     * 不该给任何一条**存在**的会话记上分支数，否则长按菜单会凭空冒出「下辖分支（1）」。
     */
    @Test
    fun orphansDoNotProducePhantomCounts() {
        val conversations = listOf(
            conversation("alive"),
            conversation("orphan", parent = "deleted-parent"),
        )
        assertTrue(ConversationTree.childCounts(conversations).isEmpty())
        // 孤儿自己仍是一条正常可查询的会话，只是它名下没有分支。
        assertTrue(ConversationTree.childrenOf(conversations, "orphan").isEmpty())
    }

    /**
     * 自指（`parent == 自己`）只可能来自损坏的数据（手工改库 / 畸形的合并导入）。
     * 既不进计数、也不进子列表：否则长按菜单会凭空多一个点进去只有它自己的「下辖分支（1）」。
     */
    @Test
    fun selfParentedConversationIsIgnored() {
        val conversations = listOf(
            conversation("a", parent = "a"),
            conversation("b", parent = "a"),
        )
        assertEquals(mapOf("a" to 1), ConversationTree.childCounts(conversations))
        assertEquals(listOf("b"), ConversationTree.childrenOf(conversations, "a").map { it.id })
    }

    /** `updatedAt` 相同时保持输入顺序（DAO 已按最近活跃排好，不用再打乱）。 */
    @Test
    fun tiesKeepInputOrder() {
        val conversations = listOf(
            conversation("root"),
            conversation("first", parent = "root", updatedAt = 5L),
            conversation("second", parent = "root", updatedAt = 5L),
        )
        assertEquals(
            listOf("first", "second"),
            ConversationTree.childrenOf(conversations, "root").map { it.id },
        )
    }
}
