package com.zcw.chatai.data.prefs

import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.provider.ProviderEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSettingsTest {

    private val settings = ChatSettings.Default.copy(
        providers = mapOf(
            ProviderCatalog.DEEPSEEK to ProviderEntry("https://ds.example/v1", "sk-ds", "deepseek-flash"),
            ProviderCatalog.QWEN to ProviderEntry("https://qw.example/v1", "sk-qw", "qwen3.8-max"),
        ),
        activeProviderId = ProviderCatalog.DEEPSEEK,
        systemPrompt = "be nice",
        temperature = 0.7,
        reasoningEffort = ReasoningEffort.OFF,
        maxTokens = 1024,
        historyImageTurns = 5,
    )

    @Test
    fun toChatConfigUsesBoundProviderConnection() {
        val qwen = settings.toChatConfig(ProviderCatalog.QWEN)
        assertEquals("https://qw.example/v1", qwen.baseUrl)
        assertEquals("sk-qw", qwen.apiKey)
        assertEquals("qwen3.8-max", qwen.model)
        assertEquals(ProviderCatalog.QWEN, qwen.providerId)
        // 生成参数是全局的，与供应商无关
        assertEquals("be nice", qwen.systemPrompt)
        assertEquals("none", qwen.reasoningEffort)
        assertEquals(1024, qwen.maxTokens)
        assertEquals(5, qwen.historyImageTurns)
    }

    @Test
    fun toChatConfigDefaultsToActiveProvider() {
        val active = settings.toChatConfig()
        assertEquals("https://ds.example/v1", active.baseUrl)
        assertEquals(ProviderCatalog.DEEPSEEK, active.providerId)
    }

    @Test
    fun missingProviderFallsBackToActiveConnectionButKeepsRequestedId() {
        val deleted = settings.toChatConfig("deleted-id")
        assertEquals("https://ds.example/v1", deleted.baseUrl)
        assertEquals("deleted-id", deleted.providerId)
    }

    @Test
    fun blankExtraParamsBecomesNull() {
        assertNull(settings.toChatConfig().extraParams)
        assertEquals("{\"top_k\":20}", settings.copy(extraParams = "{\"top_k\":20}").toChatConfig().extraParams)
    }

    @Test
    fun defaultSettingsHaveDeepseekActive() {
        assertEquals(ProviderCatalog.DEEPSEEK, ChatSettings.Default.activeProviderId)
        assertEquals(ChatSettings.DEFAULT_BASE_URL, ChatSettings.Default.activeProvider.baseUrl)
        assertEquals(ChatSettings.DEFAULT_MODEL, ChatSettings.Default.activeProvider.model)
    }

    @Test
    fun sessionHeaderFlagComesFromProviderPreset() {
        val go = ChatSettings.Default.copy(
            providers = mapOf(
                ProviderCatalog.OPENCODE_GO to ProviderEntry(
                    "https://opencode.ai/zen/go/v1",
                    "k",
                    "deepseek-v4.1-flash",
                ),
            ),
            activeProviderId = ProviderCatalog.OPENCODE_GO,
        )
        assertTrue(go.toChatConfig().sendSessionHeader)
        assertFalse(settings.toChatConfig(ProviderCatalog.QWEN).sendSessionHeader)
        assertEquals("https://opencode.ai/zen/go/v1", go.toChatConfig().anthropicBaseUrl)
        assertEquals("https://ds.example/anthropic/v1", settings.toChatConfig(ProviderCatalog.DEEPSEEK).anthropicBaseUrl)
    }

    @Test
    fun searchProviderPreferenceIsReadFromSettings() {
        val configured = settings.copy(searchProviderId = ProviderCatalog.QWEN)
        assertEquals(ProviderCatalog.QWEN, configured.searchProviderId)
        assertEquals(null, settings.searchProviderId)
    }

    @Test
    fun imageSearchModelsUseOverrideOrBuiltInDefault() {
        assertEquals(listOf("qwen3.8-27b", "qwen3.8-max"), settings.imageSearchModels)
        val overridden = settings.copy(imageSearchModelsRaw = "qwen3.8-27b , qwen3.8-max, extra")
        assertEquals(listOf("qwen3.8-27b", "qwen3.8-max", "extra"), overridden.imageSearchModels)
    }

    @Test
    fun emptyProviderTableDegradesToBlankEntry() {
        val empty = ChatSettings.Default.copy(providers = emptyMap(), activeProviderId = "nothing")
        val config = empty.toChatConfig()
        assertEquals("", config.baseUrl)
        assertEquals("", config.apiKey)
        assertEquals("nothing", config.providerId)
    }
}
