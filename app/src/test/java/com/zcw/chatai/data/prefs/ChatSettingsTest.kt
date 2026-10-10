package com.zcw.chatai.data.prefs

import com.zcw.chatai.data.persona.PersonaConfigCodec
import com.zcw.chatai.data.persona.PersonaEntry
import com.zcw.chatai.data.net.ChatApiException
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
        assertEquals(
            com.zcw.chatai.data.provider.MediaContentOrder.MEDIA_THEN_TEXT,
            config.mediaContentOrder,
        )
        assertEquals(
            com.zcw.chatai.data.provider.MediaContentOrder.TEXT_THEN_MEDIA,
            settings.toChatConfig(ProviderCatalog.DEEPSEEK).mediaContentOrder,
        )
        assertFalse(config.sendsImageDetail)
        assertTrue(settings.toChatConfig(ProviderCatalog.DEEPSEEK).sendsImageDetail)
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
    fun imageGenModelUsesOverrideOrBuiltInDefault() {
        assertEquals("qwen-image-3.0-pro", settings.imageGenModel)
        assertEquals("qwen-image-2.0-pro", settings.copy(imageGenModelRaw = " qwen-image-2.0-pro ").imageGenModel)
        assertTrue(ChatSettings.Default.imagePromptExtend)
    }

    @Test
    fun imageWorkspaceStaysOffTheActiveTextProvider() {
        assertEquals(ProviderCatalog.QWEN, settings.resolvedImageWorkspaceProvider)
        assertEquals("qwen-image-3.0-pro", settings.resolvedImageWorkspaceModel)
        val image = settings.copy(
            imageWorkspace = WorkspaceMemory(ProviderCatalog.OPENAI, "gpt-image-2.5-flare"),
            activeProviderId = ProviderCatalog.DEEPSEEK,
        )
        assertEquals(ProviderCatalog.OPENAI, image.resolvedImageWorkspaceProvider)
        assertEquals("gpt-image-2.5-flare", image.resolvedImageWorkspaceModel)
        assertEquals(ProviderCatalog.DEEPSEEK, image.activeProviderId)
        assertEquals(ProviderCatalog.QWEN, settings.resolvedVideoWorkspaceProvider)
    }

    @Test
    fun blankComposerRemembersTheTextWorkspace() {
        val picked = settings.copy(
            providers = settings.providers + (
                ProviderCatalog.OPENAI to ProviderEntry("https://oa.example/v1", "sk-oa", "gpt-6-astra")
                ),
            activeProviderId = ProviderCatalog.OPENAI,
            textWorkspace = WorkspaceMemory(ProviderCatalog.QWEN, "qwen3.8-27b"),
        )
        val (providerId, model) = picked.blankComposer(null, null)
        assertEquals(ProviderCatalog.QWEN, providerId)
        assertEquals("qwen3.8-27b", model)
    }

    @Test
    fun blankComposerUsesTheActiveProviderWhenTheWorkspaceIsEmpty() {
        val (providerId, model) = settings.blankComposer(null, null)
        assertEquals(ProviderCatalog.DEEPSEEK, providerId)
        assertEquals("deepseek-flash", model)
        val blank = settings.blankComposer("  ", "  ")
        assertEquals(ProviderCatalog.DEEPSEEK, blank.first)
        assertEquals("deepseek-flash", blank.second)
    }

    @Test
    fun blankComposerPrefersAnExplicitBinding() {
        val picked = settings.copy(
            textWorkspace = WorkspaceMemory(ProviderCatalog.QWEN, "qwen3.8-27b"),
        )
        val (providerId, model) = picked.blankComposer(ProviderCatalog.DEEPSEEK, "deepseek-v4-pro")
        assertEquals(ProviderCatalog.DEEPSEEK, providerId)
        assertEquals("deepseek-v4-pro", model)
    }

    @Test
    fun blankComposerKeepsAnotherProvidersConfiguredModel() {
        val picked = settings.copy(
            textWorkspace = WorkspaceMemory(ProviderCatalog.QWEN, "qwen3.8-27b"),
        )
        val (providerId, model) = picked.blankComposer(ProviderCatalog.DEEPSEEK, null)
        assertEquals(ProviderCatalog.DEEPSEEK, providerId)
        assertEquals("deepseek-flash", model)
    }

    @Test
    fun rewriteUsesTheTextModelNotTheImageProvider() {
        val image = settings.copy(
            providers = settings.providers + (
                ProviderCatalog.OPENAI to ProviderEntry("https://oa.example/v1", "sk-oa", "gptimage")
                ),
            imageWorkspace = WorkspaceMemory(ProviderCatalog.OPENAI, "gpt-image-2.5-flare"),
            activeProviderId = ProviderCatalog.DEEPSEEK,
        )
        val fallback = image.requireTextRewriteConfig()
        assertEquals(ProviderCatalog.DEEPSEEK, fallback.providerId)
        assertEquals("deepseek-flash", fallback.model)

        val picked = image.copy(
            textWorkspace = WorkspaceMemory(ProviderCatalog.QWEN, "qwen3.8-max"),
        )
        val config = picked.requireTextRewriteConfig()
        assertEquals(ProviderCatalog.QWEN, config.providerId)
        assertEquals("qwen3.8-max", config.model)
        assertEquals("sk-qw", config.apiKey)
        assertEquals("", config.systemPrompt)
        assertEquals("none", config.reasoningEffort)
        assertNull(config.temperature)
        assertNull(config.maxTokens)
        assertNull(config.extraParams)
        assertFalse(config.includeEnvTime)

        val missing = settings.copy(activeProviderId = ProviderCatalog.OPENAI)
        try {
            missing.requireTextRewriteConfig()
            throw AssertionError("expected missing key")
        } catch (error: ChatApiException) {
            assertTrue(error.message!!.contains("文本供应商"))
        }
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
