package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageGroupsTest {

    private fun message(id: String, role: Role, content: String = "") =
        ChatMessageItem(id = id, role = role, content = content)

    @Test
    fun foldsConsecutiveAssistantAndToolRowsIntoOneTurn() {
        val groups = MessageGroups.of(
            listOf(
                message("u1", Role.USER, "问题"),
                message("a1", Role.ASSISTANT, "我先查一下"),
                message("t1", Role.TOOL),
                message("t2", Role.TOOL),
                message("a2", Role.ASSISTANT, "答案"),
                message("u2", Role.USER, "继续"),
                message("a3", Role.ASSISTANT, "补充"),
            ),
        )
        assertEquals(4, groups.size)
        assertEquals(listOf("u1"), groups[0].items.map { it.id })
        val turn = groups[1]
        assertTrue(turn is MessageGroup.Assistant)
        assertEquals(listOf("a1", "t1", "t2", "a2"), turn.items.map { it.id })
        assertEquals("a1", turn.key)
        assertTrue(groups[2] is MessageGroup.User)
        assertTrue(groups[3] is MessageGroup.Assistant)
    }

    @Test
    fun consecutiveUserMessagesStaySeparate() {
        val groups = MessageGroups.of(
            listOf(
                message("u1", Role.USER, "一"),
                message("u2", Role.USER, "二"),
            ),
        )
        assertEquals(listOf("u1", "u2"), groups.map { it.key })
        assertTrue(groups.all { it is MessageGroup.User })
    }

    @Test
    fun leadingAssistantRunWithoutUserStillGroups() {
        val groups = MessageGroups.of(listOf(message("a1", Role.ASSISTANT, "你好")))
        assertEquals(1, groups.size)
        assertTrue(groups.single() is MessageGroup.Assistant)
    }

    @Test
    fun emptyInputGivesNoGroups() {
        assertEquals(emptyList<MessageGroup>(), MessageGroups.of(emptyList()))
    }

    /** 流式追加新步骤时，整组的 key 必须保持首行 id，LazyColumn 才不会整块重建。 */
    @Test
    fun groupKeyStaysStableWhenANewStepIsAppended() {
        val before = MessageGroups.of(
            listOf(
                message("u1", Role.USER, "问题"),
                message("a1", Role.ASSISTANT, "第一步"),
            ),
        )
        val after = MessageGroups.of(
            listOf(
                message("u1", Role.USER, "问题"),
                message("a1", Role.ASSISTANT, "第一步"),
                message("t1", Role.TOOL),
                message("a2", Role.ASSISTANT, "第二步"),
            ),
        )
        assertEquals(before[1].key, after[1].key)
        assertEquals(3, after[1].items.size)
    }
}
