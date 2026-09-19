package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.model.ToolSource
import com.zcw.chatai.data.net.ChatApiException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenCodeGoSearchRouterTest {

    private fun config(model: String) = ChatConfig(
        baseUrl = "https://opencode.ai/zen/go/v1",
        apiKey = "k",
        model = model,
        systemPrompt = "",
        temperature = null,
    )

    private fun fake(
        id: String = "fake",
        answer: String? = "A",
        urls: List<String> = listOf("https://a.example"),
        error: Throwable? = null,
        onSearch: ((String) -> Unit)? = null,
    ) = object : WebSearchProvider {
        override val id: String = id
        override fun available(baseUrl: String, apiKey: String): Boolean = true
        override suspend fun search(query: String, maxResults: Int, config: ChatConfig): WebSearchResult {
            onSearch?.invoke(query)
            error?.let { throw it }
            return WebSearchResult(
                answer = answer,
                sources = urls.map { ToolSource(url = it) },
            )
        }
    }

    @Test
    fun lunaTriesResponsesFirst() = runBlocking {
        var messagesCalled = false
        var responsesCalled = false
        val router = OpenCodeGoSearchRouter(
            messages = fake(id = "m", onSearch = { messagesCalled = true }),
            responses = fake(id = "r", answer = "from-b", onSearch = { responsesCalled = true }),
        )
        val result = router.search("q", 5, config("gpt-5.6-luna"))
        assertEquals("from-b", result.answer)
        assertTrue(responsesCalled)
        assertTrue(!messagesCalled)
    }

    @Test
    fun deepseekTriesMessagesFirst() = runBlocking {
        var messagesCalled = false
        var responsesCalled = false
        val router = OpenCodeGoSearchRouter(
            messages = fake(id = "m", answer = "from-a", onSearch = { messagesCalled = true }),
            responses = fake(id = "r", onSearch = { responsesCalled = true }),
        )
        val result = router.search("q", 5, config("deepseek-v4.1-flash"))
        assertEquals("from-a", result.answer)
        assertTrue(messagesCalled)
        assertTrue(!responsesCalled)
    }

    @Test
    fun messages503FallsBackToResponses() = runBlocking {
        val router = OpenCodeGoSearchRouter(
            messages = fake(id = "m", error = ChatApiException("服务暂时不可用，请稍后重试")),
            responses = fake(id = "r", answer = "from-b"),
        )
        val result = router.search("q", 5, config("grok-4.6"))
        assertEquals("from-b", result.answer)
    }

    @Test
    fun emptyPrimaryFallsBackToSecondary() = runBlocking {
        val router = OpenCodeGoSearchRouter(
            messages = fake(id = "m", answer = null, urls = emptyList()),
            responses = fake(id = "r", answer = "from-b"),
        )
        // deepseek 系优先 A，A 空结果则借道 B。
        val result = router.search("q", 5, config("deepseek-v4.1-flash"))
        assertEquals("from-b", result.answer)
    }

    @Test
    fun bothFailThrows() = runBlocking {
        val router = OpenCodeGoSearchRouter(
            messages = fake(id = "m", error = ChatApiException("A 挂了")),
            responses = fake(id = "r", error = ChatApiException("B 挂了")),
        )
        try {
            router.search("q", 5, config("grok-4.6"))
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun availabilityRequiresKeyAndValidUrl() {
        val router = OpenCodeGoSearchRouter()
        assertTrue(router.available("https://opencode.ai/zen/go/v1", "k"))
        assertTrue(!router.available("https://opencode.ai/zen/go/v1", ""))
        assertTrue(!router.available("", "k"))
    }
}
