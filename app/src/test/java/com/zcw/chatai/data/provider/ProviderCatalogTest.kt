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
        assertEquals(4, ProviderCatalog.presets.size)
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
    }
}
