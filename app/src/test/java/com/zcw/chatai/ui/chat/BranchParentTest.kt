package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BranchParentTest {

    @Test
    fun ordinaryConversationHasNoBanner() {
        val conversation = conversation("c1", parent = "")
        assertNull(resolveBranchParent(conversation, listOf(conversation)))
        assertNull(resolveBranchParent(null, listOf(conversation)))
    }

    @Test
    fun bannerUsesParentTitleWhileParentExists() {
        val parent = conversation("p", title = "方案对比")
        val branch = conversation("b", parent = "p")
        assertEquals(
            BranchParent(id = "p", title = "方案对比"),
            resolveBranchParent(branch, listOf(parent, branch)),
        )
    }

    @Test
    fun deletedParentHidesBanner() {
        val branch = conversation("b", parent = "gone")
        assertNull(resolveBranchParent(branch, listOf(branch)))
    }

    @Test
    fun blankParentTitleFallsBack() {
        val parent = conversation("p", title = "  ")
        val branch = conversation("b", parent = "p")
        assertEquals(
            BranchParent(id = "p", title = "新对话"),
            resolveBranchParent(branch, listOf(parent, branch)),
        )
    }

    private fun conversation(id: String, title: String = "标题", parent: String = "") = Conversation(
        id = id,
        title = title,
        model = "m",
        systemPrompt = null,
        createdAt = 0L,
        updatedAt = 0L,
        lastMessagePreview = "",
        messageCount = 0,
        isPinned = false,
        parentConversationId = parent,
    )
}
