package com.zcw.chatai.ui.common

import com.zcw.chatai.data.model.Conversation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSearchTest {

    @Test
    fun blankQueryFollowsAllAndSkipsSearch() = runTest {
        val all = MutableStateFlow(listOf(conversation("a")))
        var searches = 0
        val search = ConversationSearch(
            all = all,
            search = {
                searches += 1
                flowOf(listOf(conversation("hit")))
            },
            scope = backgroundScope,
        )
        val ids = search.results.map { list -> list.map { it.id } }

        assertEquals(listOf("a"), ids.first { it.isNotEmpty() })
        assertEquals(0, searches)

        search.setQuery("cat")
        assertEquals(listOf("hit"), ids.first { it == listOf("hit") })
        assertEquals(1, searches)

        search.setQuery("   ")
        assertEquals(listOf("a"), ids.first { it == listOf("a") })
        assertEquals(1, searches)

        all.value = listOf(conversation("b"))
        assertEquals(listOf("b"), ids.first { it == listOf("b") })
        assertEquals(1, searches)
    }

    private fun conversation(id: String) = Conversation(
        id = id,
        title = id,
        model = "m",
        systemPrompt = null,
        createdAt = 0L,
        updatedAt = 0L,
        lastMessagePreview = "",
        messageCount = 0,
        isPinned = false,
    )
}
