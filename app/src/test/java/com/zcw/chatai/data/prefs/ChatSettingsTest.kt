package com.zcw.chatai.data.prefs

import com.zcw.chatai.data.persona.PersonaConfigCodec
import com.zcw.chatai.data.persona.PersonaEntry
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.provider.ProviderEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSettingsTest {

    private val personas = mapOf(
        "default" to PersonaEntry(
            name = "默认",
            systemPrompt = "be nice",
            temperature = 0.7,
            reasoningEffort = ReasoningEffort.OFF,
            maxTokens = 1024,
        ),
        "translator" to PersonaEntry(name = "翻译官", systemPrompt = "translate"),
    )

    private val settings = ChatSettings.Default.copy(
        providers = mapOf(
            ProviderCatalog.DEEPSEEK to ProviderEntry("https://ds.example/v1", "sk-ds", "deepseek-flash"),
            ProviderCatalog.QWEN to ProviderEntry("https://qw.example/v1", "sk-qw", "qwen3.8-max"),
        ),
        activeProviderId = ProviderCatalog.DEEPSEEK,
        personas = personas,
        activePersonaId = "default",
        historyImageTurns = 5,
    )

    @Test
    fun toChatConfigUsesBoundProviderConnection() {
        val qwen = settings.toChatConfig(ProviderCatalog.QWEN)
        assertEquals("https://qw.example/v1", qwen.baseUrl)
        assertEquals("sk-qw", qwen.apiKey)
        assertEquals("qwen3.8-max", qwen.model)
        assertEquals(ProviderCatalog.QWEN, qwen.providerId)
        // 生成参数来自激活角色，与供应商无关
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
    fun toChatConfigUsesBoundPersona() {
        val bound = settings.toChatConfig(ProviderCatalog.DEEPSEEK, "translator")
        assertEquals("translate", bound.systemPrompt)
        assertNull(bound.temperature)
        // 空串 = 跟随激活角色
        val follow = settings.toChatConfig(ProviderCatalog.DEEPSEEK, "")
        assertEquals("be nice", follow.systemPrompt)
        // 绑定了已删除的角色 → 回退激活角色
        val deleted = settings.toChatConfig(ProviderCatalog.DEEPSEEK, "deleted-id")
        assertEquals("be nice", deleted.systemPrompt)
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
        val withExtra = settings.copy(
            personas = mapOf("p" to PersonaEntry(name = "P", extraParams = "{\"top_k\":20}")),
            activePersonaId = "p",
        )
        assertEquals("{\"top_k\":20}", withExtra.toChatConfig().extraParams)
    }

    @Test
    fun defaultSettingsHaveDeepseekActiveAndDefaultPersona() {
        assertEquals(ProviderCatalog.DEEPSEEK, ChatSettings.Default.activeProviderId)
        assertEquals(ChatSettings.DEFAULT_BASE_URL, ChatSettings.Default.activeProvider.baseUrl)
        assertEquals(ChatSettings.DEFAULT_MODEL, ChatSettings.Default.activeProvider.model)
        assertEquals(PersonaConfigCodec.DEFAULT_ID, ChatSettings.Default.activePersonaId)
        assertEquals(PersonaConfigCodec.DEFAULT_NAME, ChatSettings.Default.activePersona.name)
    }

    @Test
    fun defaultThemeIsClaudeFollowSystem() {
        assertEquals(ThemeMode.SYSTEM, ChatSettings.Default.themeMode)
        assertEquals(ThemeFamily.CLAUDE, ChatSettings.Default.themeFamily)
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
    fun mimoDerivesAnthropicAndResponsesBasesFromChatBase() {
        val mimo = settings.copy(
            providers = mapOf(
                ProviderCatalog.MIMO to ProviderEntry(
                    "https://api.xiaomimimo.com/v1",
                    "sk-mimo",
                    "mimo-v2.6-flash",
                ),
            ),
            activeProviderId = ProviderCatalog.MIMO,
        )
        val config = mimo.toChatConfig()
        assertEquals("https://api.xiaomimimo.com/anthropic/v1", config.anthropicBaseUrl)
        // responsesBaseUrl 存的是 v1 根，完整端点由 EndpointUrl.responses 再拼 /responses。
        assertEquals("https://api.xiaomimimo.com/v1", config.responsesBaseUrl)
        assertEquals(
            com.zcw.chatai.data.provider.ThinkingWire.MIMO_THINKING_OBJECT,
            config.thinkingWire,
        )
        // 其余供应商仍走标准 reasoning_effort。
        assertEquals(
            com.zcw.chatai.data.provider.ThinkingWire.STANDARD_REASONING_EFFORT,
            settings.toChatConfig(ProviderCatalog.DEEPSEEK).thinkingWire,
        )
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
