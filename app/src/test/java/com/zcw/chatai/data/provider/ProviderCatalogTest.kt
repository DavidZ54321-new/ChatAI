package com.zcw.chatai.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {

    @Test
    fun builtInPresetsAreStable() {
        assertEquals("DeepSeek", ProviderCatalog.byId(ProviderCatalog.DEEPSEEK)!!.displayName)
        assertEquals("通义千问", ProviderCatalog.byId(ProviderCatalog.QWEN)!!.displayName)
        assertEquals("OpenCode Go", ProviderCatalog.byId(ProviderCatalog.OPENCODE_GO)!!.displayName)
        assertEquals("MiMo", ProviderCatalog.byId(ProviderCatalog.MIMO)!!.displayName)
        assertEquals(5, ProviderCatalog.presets.size)
    }

    @Test
    fun openCodeGoPresetFollowsGatewayRequirements() {
        val go = ProviderCatalog.byId(ProviderCatalog.OPENCODE_GO)!!
        assertEquals("https://opencode.ai/zen/go/v1", go.defaultBaseUrl)
        assertEquals("deepseek-v4.1-flash", go.defaultModel)
        assertTrue("网关强制会话头", go.sendSessionHeader)
        assertTrue("vision-exp 模型能读图", go.caps.image)
        assertTrue(!go.caps.video)
        assertTrue("Go 文本搜索走 Anthropic /messages", go.caps.textSearch)
        assertTrue(!go.caps.imageSearch)
    }

    @Test
    fun mimoPresetMatchesOfficialEndpoints() {
        val mimo = ProviderCatalog.byId(ProviderCatalog.MIMO)!!
        assertEquals("https://api.xiaomimimo.com/v1", mimo.defaultBaseUrl)
        assertEquals("mimo-v2.6-flash", mimo.defaultModel)
        assertTrue(mimo.caps.image)
        assertTrue("全模态：视频", mimo.caps.video)
        assertTrue("Chat 面 web_search 插件", mimo.caps.textSearch)
        assertFalse("无图搜原生工具", mimo.caps.imageSearch)
        assertTrue("音频理解首发", mimo.caps.audio)
        assertEquals(
            "Anthropic 面 = {origin}/anthropic/v1",
            AnthropicBaseLayout.ORIGIN_ANTHROPIC,
            mimo.anthropicBaseLayout,
        )
        assertEquals(35L * 1024 * 1024, mimo.videoInlineMaxBytes)
        assertFalse("无 DashScope 上传路由", mimo.videoUploadViaDashScope)
        assertEquals(ThinkingWire.MIMO_THINKING_OBJECT, mimo.thinkingWire)
        assertFalse(mimo.sendSessionHeader)
    }

    @Test
    fun capabilityHelpersFollowPresetData() {
        assertTrue(ProviderCatalog.supportsAudio(ProviderCatalog.MIMO))
        assertFalse(ProviderCatalog.supportsAudio(ProviderCatalog.DEEPSEEK))
        assertFalse(ProviderCatalog.supportsAudio(ProviderCatalog.QWEN))
        assertTrue(ProviderCatalog.supportsVideo(ProviderCatalog.MIMO))
        assertEquals(35L * 1024 * 1024, ProviderCatalog.videoInlineMaxBytesFor(ProviderCatalog.MIMO))
        assertFalse(ProviderCatalog.videoUploadViaDashScope(ProviderCatalog.MIMO))
        assertTrue("Qwen 保留 DashScope 上传路由", ProviderCatalog.videoUploadViaDashScope(ProviderCatalog.QWEN))
        // 未单独配置的供应商回落全局默认。
        assertEquals(
            com.zcw.chatai.data.media.AttachmentLimits.VIDEO_INLINE_MAX_BYTES,
            ProviderCatalog.videoInlineMaxBytesFor(ProviderCatalog.DEEPSEEK),
        )
        assertEquals(
            ThinkingWire.MIMO_THINKING_OBJECT,
            ProviderCatalog.thinkingWireFor(ProviderCatalog.MIMO),
        )
        assertEquals(
            ThinkingWire.STANDARD_REASONING_EFFORT,
            ProviderCatalog.thinkingWireFor(ProviderCatalog.DEEPSEEK),
        )
    }

    @Test
    fun otherPresetsDoNotSendSessionHeader() {
        assertTrue(!ProviderCatalog.byId(ProviderCatalog.DEEPSEEK)!!.sendSessionHeader)
        assertTrue(!ProviderCatalog.byId(ProviderCatalog.QWEN)!!.sendSessionHeader)
    }

    @Test
    fun matchByBaseUrlMapsHosts() {
        assertEquals(
            ProviderCatalog.DEEPSEEK,
            ProviderCatalog.matchByBaseUrl("https://api.deepseek.com/v1"),
        )
        assertEquals(
            ProviderCatalog.QWEN,
            ProviderCatalog.matchByBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
        assertEquals(
            ProviderCatalog.QWEN,
            ProviderCatalog.matchByBaseUrl("https://ws-xxx.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"),
        )
        assertEquals(ProviderCatalog.CUSTOM, ProviderCatalog.matchByBaseUrl("https://my.proxy/v1"))
        assertEquals(ProviderCatalog.OPENCODE_GO, ProviderCatalog.matchByBaseUrl("https://opencode.ai/zen/go/v1"))
        assertEquals(
            ProviderCatalog.MIMO,
            ProviderCatalog.matchByBaseUrl("https://api.xiaomimimo.com/v1"),
        )
        assertEquals(ProviderCatalog.CUSTOM, ProviderCatalog.matchByBaseUrl(""))
    }

    @Test
    fun displayNameFallsBackToId() {
        assertEquals("unknown-id", ProviderCatalog.displayName("unknown-id"))
        assertNull(ProviderCatalog.byId("unknown-id"))
    }

    @Test
    fun defaultModelPrefersConfiguredEntryThenPresetDefault() {
        assertEquals(
            "my-deepseek",
            ProviderCatalog.defaultModelFor(
                mapOf(ProviderCatalog.DEEPSEEK to ProviderEntry("https://x/v1", "sk", "my-deepseek")),
                ProviderCatalog.DEEPSEEK,
            ),
        )
        assertEquals(
            "deepseek-flash",
            ProviderCatalog.defaultModelFor(
                mapOf(ProviderCatalog.DEEPSEEK to ProviderEntry("https://x/v1", "sk", "")),
                ProviderCatalog.DEEPSEEK,
            ),
        )
        assertEquals("", ProviderCatalog.defaultModelFor(emptyMap(), "unknown-id"))
    }

    @Test
    fun capabilitiesDescribeNativeTools() {
        val qwen = ProviderCatalog.byId(ProviderCatalog.QWEN)!!.caps
        assertTrue(qwen.textSearch)
        assertTrue(qwen.imageSearch)
        assertTrue(qwen.video)
        assertTrue(qwen.image)

        val deepseek = ProviderCatalog.byId(ProviderCatalog.DEEPSEEK)!!.caps
        assertTrue(deepseek.textSearch)
        assertFalse(deepseek.imageSearch)
        assertFalse(deepseek.video)

        val go = ProviderCatalog.byId(ProviderCatalog.OPENCODE_GO)!!.caps
        assertTrue(go.textSearch)
        assertFalse(go.imageSearch)
        assertFalse(go.video)

        val custom = ProviderCatalog.byId(ProviderCatalog.CUSTOM)!!.caps
        assertFalse(custom.textSearch)
        assertFalse(custom.video)

        val mimo = ProviderCatalog.byId(ProviderCatalog.MIMO)!!.caps
        assertTrue(mimo.textSearch)
        assertFalse(mimo.imageSearch)
        assertTrue(mimo.video)
        assertTrue(mimo.audio)
    }
}
