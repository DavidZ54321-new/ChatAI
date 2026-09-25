package com.zcw.chatai.data.backup

import com.zcw.chatai.data.db.ConversationEntity
import com.zcw.chatai.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergeTest {

    private fun conversation(id: String, parent: String = "") = ConversationEntity(
        id = id,
        title = "会话 $id",
        model = "deepseek-flash",
        systemPrompt = null,
        createdAt = 1,
        updatedAt = 2,
        lastMessagePreview = "",
        messageCount = 0,
        isPinned = false,
        parentConversationId = parent,
    )

    private fun message(id: String, conversationId: String, attachments: String? = null) = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = "USER",
        content = "正文",
        status = "COMPLETE",
        errorMessage = null,
        reasoningContent = null,
        seq = 1,
        model = null,
        promptTokens = null,
        completionTokens = null,
        attachments = attachments,
        createdAt = 1,
        updatedAt = 2,
    )

    @Test
    fun assignsFreshIdsToConversationsAndMessages() {
        val remapped = BackupMerge.remap(
            conversations = listOf(conversation("c1")),
            messages = listOf(message("m1", "c1"), message("m2", "c1")),
            newId = ids(),
        )
        val newConversationId = remapped.conversationIds.getValue("c1")
        assertEquals(listOf("new-1"), remapped.conversations.map { it.id })
        assertEquals(listOf("new-2", "new-3"), remapped.messages.map { it.id })
        assertTrue(remapped.messages.all { it.conversationId == newConversationId })
        // 标题等业务字段照旧。
        assertEquals("会话 c1", remapped.conversations.single().title)
    }

    @Test
    fun remapsBranchParentToTheNewId() {
        val remapped = BackupMerge.remap(
            conversations = listOf(conversation("c1"), conversation("c2", parent = "c1")),
            messages = emptyList(),
            newId = ids(),
        )
        val byOld = remapped.conversationIds
        val branch = remapped.conversations.first { it.parentConversationId.isNotBlank() }
        assertEquals(byOld.getValue("c1"), branch.parentConversationId)
        assertEquals(byOld.getValue("c2"), branch.id)
    }

    @Test
    fun parentOutsideTheBackupBecomesRoot() {
        val remapped = BackupMerge.remap(
            conversations = listOf(conversation("c2", parent = "not-in-archive")),
            messages = emptyList(),
            newId = ids(),
        )
        assertEquals("", remapped.conversations.single().parentConversationId)
    }

    @Test
    fun rewritesAttachmentDirectoriesToTheNewConversation() {
        val attachments =
            """[{"id":"a1","path":"attachments/c1/a1.jpg","extractedPath":"attachments/c1/a1.extracted.txt"}]"""
        val remapped = BackupMerge.remap(
            conversations = listOf(conversation("c1")),
            messages = listOf(message("m1", "c1", attachments)),
            newId = ids(),
        )
        val newId = remapped.conversationIds.getValue("c1")
        assertEquals(
            """[{"id":"a1","path":"attachments/$newId/a1.jpg","extractedPath":"attachments/$newId/a1.extracted.txt"}]""",
            remapped.messages.single().attachments,
        )
    }

    /** 消息指向的会话不在备份里时丢掉：`messages.conversation_id` 有外键，插进去会直接失败。 */
    @Test
    fun dropsMessagesOfConversationsThatAreNotImported() {
        val remapped = BackupMerge.remap(
            conversations = listOf(conversation("c1")),
            messages = listOf(message("m1", "c1"), message("orphan", "c9")),
            newId = ids(),
        )
        assertEquals(1, remapped.messages.size)
        assertEquals(
            remapped.conversationIds.getValue("c1"),
            remapped.messages.single().conversationId,
        )
    }

    @Test
    fun emptyInputStaysEmpty() {
        val remapped = BackupMerge.remap(emptyList(), emptyList(), ids())
        assertTrue(remapped.conversations.isEmpty())
        assertTrue(remapped.messages.isEmpty())
        assertTrue(remapped.conversationIds.isEmpty())
    }

    private fun ids(): () -> String {
        var counter = 0
        return { "new-${++counter}" }
    }
}
