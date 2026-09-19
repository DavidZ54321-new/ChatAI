package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.provider.ProviderCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FailoverChatApiTest {

    private fun config(model: String) = ChatConfig(
        baseUrl = "https://opencode.ai/zen/go/v1",
        apiKey = "k",
        model = model,
        systemPrompt = "",
        temperature = null,
        providerId = ProviderCatalog.OPENCODE_GO,
    )

    private fun face(
        events: List<ChatStreamEvent> = emptyList(),
        error: Throwable? = null,
        onCall: (() -> Unit)? = null,
    ) = object : ChatApi {
        override fun stream(config: ChatConfig, messages: List<ChatRequestMessage>): Flow<ChatStreamEvent> = flow {
            onCall?.invoke()
            events.forEach { emit(it) }
            error?.let { throw it }
        }

        override suspend fun listModels(config: ChatConfig): List<String> = emptyList()
    }

    private fun apiOf(primary: ChatApi, fallback: ChatApi? = null) = FailoverChatApi(
        primary = primary,
        responsesFallbacks = fallback?.let { mapOf(ProviderCatalog.OPENCODE_GO to it) } ?: emptyMap(),
        log = {},
    )

    private fun endpointError() = ChatApiException("Upstream request failed: Endpoint is unavailable.")

    @Test
    fun grokGoesResponsesFirstAndNeverTouchesChat() = runBlocking {
        var chatCalled = false
        val api = apiOf(
            primary = face(events = listOf(ChatStreamEvent.Delta("chat")), onCall = { chatCalled = true }),
            fallback = face(events = listOf(ChatStreamEvent.Delta("resp"))),
        )
        val events = api.stream(config("grok-4.6"), emptyList()).toList()
        assertEquals("resp", events.filterIsInstance<ChatStreamEvent.Delta>().joinToString("") { it.content.orEmpty() })
        assertTrue(!chatCalled)
    }

    @Test
    fun deepseekStaysOnChatEvenWhenChatFails() = runBlocking {
        var fallbackCalled = false
        val api = apiOf(
            primary = face(error = endpointError()),
            fallback = face(onCall = { fallbackCalled = true }),
        )
        try {
            api.stream(config("deepseek-v4.1-flash"), emptyList()).toList()
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("Endpoint is unavailable"))
        }
        // flash 的 Responses 面会空转：换过去更糟，所以不换。
        assertTrue(!fallbackCalled)
    }

    @Test
    fun responsesEndpointErrorFallsBackToChat() = runBlocking {
        val api = apiOf(
            primary = face(events = listOf(ChatStreamEvent.Delta("chat"))),
            fallback = face(error = endpointError()),
        )
        val events = api.stream(config("gpt-5.6-luna"), emptyList()).toList()
        assertEquals("chat", events.filterIsInstance<ChatStreamEvent.Delta>().joinToString("") { it.content.orEmpty() })
    }

    @Test
    fun midStreamFailureDoesNotFailover() = runBlocking {
        var chatCalled = false
        val api = apiOf(
            primary = face(onCall = { chatCalled = true }),
            fallback = face(
                events = listOf(ChatStreamEvent.Delta("half")),
                error = ChatApiException("连接中断，请重试"),
            ),
        )
        try {
            api.stream(config("grok-4.6"), emptyList()).toList()
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("中断"))
        }
        assertTrue(!chatCalled)
    }

    @Test
    fun nonEndpointErrorDoesNotFailover() = runBlocking {
        var chatCalled = false
        val api = apiOf(
            primary = face(onCall = { chatCalled = true }),
            fallback = face(error = ChatApiException("API Key 无效或已过期")),
        )
        try {
            api.stream(config("grok-4.6"), emptyList()).toList()
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("API Key"))
        }
        // key 错了换面也没用：不换。
        assertTrue(!chatCalled)
    }

    @Test
    fun cancellationPropagatesWithoutFailover() = runBlocking {
        var chatCalled = false
        val api = apiOf(
            primary = face(onCall = { chatCalled = true }),
            fallback = face(error = CancellationException("stop")),
        )
        try {
            api.stream(config("grok-4.6"), emptyList()).toList()
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }
        assertTrue(!chatCalled)
    }

    @Test
    fun otherProvidersNeverFailover() = runBlocking {
        var fallbackCalled = false
        val api = apiOf(
            primary = face(error = endpointError()),
            fallback = face(onCall = { fallbackCalled = true }),
        )
        try {
            api.stream(config("grok-4.6").copy(providerId = ProviderCatalog.DEEPSEEK), emptyList()).toList()
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
        assertTrue(!fallbackCalled)
    }

    @Test
    fun listModelsAlwaysUsesPrimary() = runBlocking {
        val api = apiOf(
            primary = object : ChatApi {
                override fun stream(config: ChatConfig, messages: List<ChatRequestMessage>): Flow<ChatStreamEvent> =
                    flowOf()

                override suspend fun listModels(config: ChatConfig): List<String> = listOf("m")
            },
        )
        assertEquals(listOf("m"), api.listModels(config("grok-4.6")))
    }
}
