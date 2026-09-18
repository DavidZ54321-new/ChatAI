package com.zcw.chatai.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolBackendResolverTest {

    private val ds = ProviderEntry("https://api.deepseek.com/v1", "k-ds", "deepseek-flash")
    private val qw = ProviderEntry("https://dashscope.aliyuncs.com/compatible-mode/v1", "k-qw", "qwen3.8-flash")

    @Test
    fun explicitSearchChoiceWins() {
        val backends = ToolBackendResolver.resolve(
            providers = mapOf(ProviderCatalog.DEEPSEEK to ds, ProviderCatalog.QWEN to qw),
            activeProviderId = ProviderCatalog.DEEPSEEK,
            conversationProviderId = ProviderCatalog.DEEPSEEK,
            preferredSearchProviderId = ProviderCatalog.QWEN,
        )
        assertEquals(ProviderCatalog.QWEN, backends.textProviderId)
    }

    @Test
    fun followsConversationWhenCapable() {
        val backends = ToolBackendResolver.resolve(
            providers = mapOf(ProviderCatalog.DEEPSEEK to ds, ProviderCatalog.QWEN to qw),
            activeProviderId = ProviderCatalog.DEEPSEEK,
            conversationProviderId = ProviderCatalog.QWEN,
            preferredSearchProviderId = null,
        )
        assertEquals(ProviderCatalog.QWEN, backends.textProviderId)
    }

    /** 会话是自定义（无能力）→ 回退到 preset 顺序里第一个已配置的（DeepSeek）。 */
    @Test
    fun fallsBackWhenConversationIsNotCapable() {
        val backends = ToolBackendResolver.resolve(
            providers = mapOf(
                ProviderCatalog.CUSTOM to ProviderEntry("https://proxy/v1", "k", "m"),
                ProviderCatalog.QWEN to qw,
            ),
            activeProviderId = ProviderCatalog.CUSTOM,
            conversationProviderId = ProviderCatalog.CUSTOM,
            preferredSearchProviderId = null,
        )
        assertEquals(ProviderCatalog.QWEN, backends.textProviderId)
    }

    @Test
    fun fallsBackToDeepseekFirstWhenBothConfigured() {
        val backends = ToolBackendResolver.resolve(
            providers = mapOf(ProviderCatalog.DEEPSEEK to ds, ProviderCatalog.QWEN to qw),
            activeProviderId = ProviderCatalog.CUSTOM,
            conversationProviderId = ProviderCatalog.CUSTOM,
            preferredSearchProviderId = null,
        )
        assertEquals(ProviderCatalog.DEEPSEEK, backends.textProviderId)
        assertEquals(ProviderCatalog.QWEN, backends.imageProviderId)
    }

    @Test
    fun staleExplicitChoiceIsIgnored() {
        val backends = ToolBackendResolver.resolve(
            providers = mapOf(ProviderCatalog.DEEPSEEK to ds),
            activeProviderId = ProviderCatalog.DEEPSEEK,
            conversationProviderId = ProviderCatalog.DEEPSEEK,
            preferredSearchProviderId = ProviderCatalog.QWEN,
        )
        assertEquals(ProviderCatalog.DEEPSEEK, backends.textProviderId)
        assertNull(backends.imageProviderId)
    }

    @Test
    fun explicitCustomChoiceIsIgnoredBecauseItHasNoBackend() {
        val backends = ToolBackendResolver.resolve(
            providers = mapOf(
                ProviderCatalog.CUSTOM to ProviderEntry("https://proxy/v1", "k", "m"),
                ProviderCatalog.DEEPSEEK to ds,
            ),
            activeProviderId = ProviderCatalog.CUSTOM,
            conversationProviderId = ProviderCatalog.CUSTOM,
            preferredSearchProviderId = ProviderCatalog.CUSTOM,
        )
        assertEquals(ProviderCatalog.DEEPSEEK, backends.textProviderId)
    }

    @Test
    fun nullConversationUsesActiveProvider() {
        val backends = ToolBackendResolver.resolve(
            providers = mapOf(ProviderCatalog.DEEPSEEK to ds, ProviderCatalog.QWEN to qw),
            activeProviderId = ProviderCatalog.QWEN,
            conversationProviderId = null,
            preferredSearchProviderId = null,
        )
        assertEquals(ProviderCatalog.QWEN, backends.textProviderId)
    }

    @Test
    fun imageBackendIsQwenWheneverConfigured() {
        val withQwen = ToolBackendResolver.resolve(
            providers = mapOf(ProviderCatalog.QWEN to qw),
            activeProviderId = ProviderCatalog.QWEN,
            conversationProviderId = ProviderCatalog.QWEN,
            preferredSearchProviderId = null,
        )
        assertEquals(ProviderCatalog.QWEN, withQwen.imageProviderId)

        val withoutQwen = ToolBackendResolver.resolve(
            providers = mapOf(ProviderCatalog.DEEPSEEK to ds),
            activeProviderId = ProviderCatalog.DEEPSEEK,
            conversationProviderId = ProviderCatalog.DEEPSEEK,
            preferredSearchProviderId = null,
        )
        assertNull(withoutQwen.imageProviderId)
    }

    @Test
    fun emptyTablesGiveNoBackends() {
        val backends = ToolBackendResolver.resolve(
            providers = emptyMap(),
            activeProviderId = ProviderCatalog.DEEPSEEK,
            conversationProviderId = null,
            preferredSearchProviderId = ProviderCatalog.QWEN,
        )
        assertEquals(ToolBackends(), backends)
    }

    /** 实机踩过：DeepSeek 有条目但没 key，会遮蔽后面配好的 Qwen，导致 web_search 不注入。 */
    @Test
    fun keylessEntryDoesNotShadowConfiguredBackend() {
        val backends = ToolBackendResolver.resolve(
            providers = mapOf(
                ProviderCatalog.DEEPSEEK to ProviderEntry("https://api.deepseek.com/v1", "", "deepseek-flash"),
                ProviderCatalog.QWEN to qw,
            ),
            activeProviderId = ProviderCatalog.DEEPSEEK,
            conversationProviderId = ProviderCatalog.DEEPSEEK,
            preferredSearchProviderId = null,
        )
        assertEquals(ProviderCatalog.QWEN, backends.textProviderId)
        assertEquals(ProviderCatalog.QWEN, backends.imageProviderId)
    }

    @Test
    fun keylessExplicitChoiceFallsThroughToNextCandidate() {
        val backends = ToolBackendResolver.resolve(
            providers = mapOf(
                ProviderCatalog.DEEPSEEK to ds,
                ProviderCatalog.QWEN to ProviderEntry(
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "",
                    "qwen3.8-flash",
                ),
            ),
            activeProviderId = ProviderCatalog.DEEPSEEK,
            conversationProviderId = ProviderCatalog.DEEPSEEK,
            preferredSearchProviderId = ProviderCatalog.QWEN,
        )
        assertEquals(ProviderCatalog.DEEPSEEK, backends.textProviderId)
        assertNull(backends.imageProviderId)
    }
}
