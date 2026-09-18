package com.zcw.chatai.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigCodecTest {

    @Test
    fun roundTripKeepsAllEntries() {
        val providers = mapOf(
            ProviderCatalog.DEEPSEEK to ProviderEntry("https://api.deepseek.com/v1", "sk-ds", "deepseek-flash"),
            ProviderCatalog.QWEN to ProviderEntry("https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-qw", "qwen3.8-max"),
        )
        assertEquals(providers, ProviderConfigCodec.decode(ProviderConfigCodec.encode(providers)))
    }

    @Test
    fun malformedOrMissingJsonDecodesEmpty() {
        assertTrue(ProviderConfigCodec.decode(null).isEmpty())
        assertTrue(ProviderConfigCodec.decode("").isEmpty())
        assertTrue(ProviderConfigCodec.decode("   ").isEmpty())
        assertTrue(ProviderConfigCodec.decode("{").isEmpty())
        assertTrue(ProviderConfigCodec.decode("[1,2,3]").isEmpty())
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val decoded = ProviderConfigCodec.decode(
            """{"qwen":{"baseUrl":"u","apiKey":"k","model":"m","future_field":1}}""",
        )
        assertEquals(ProviderEntry("u", "k", "m"), decoded.getValue(ProviderCatalog.QWEN))
    }

    @Test
    fun missingFieldsFallBackToEmptyStrings() {
        val decoded = ProviderConfigCodec.decode("""{"custom":{"baseUrl":"u"}}""")
        assertEquals(ProviderEntry("u", "", ""), decoded.getValue(ProviderCatalog.CUSTOM))
    }

    @Test
    fun legacyConfigIsMappedByHost() {
        val deepseek = ProviderConfigCodec.fromLegacy("https://api.deepseek.com/v1", "sk", "deepseek-flash")
        assertEquals(setOf(ProviderCatalog.DEEPSEEK), deepseek.keys)
        assertEquals("deepseek-flash", deepseek.getValue(ProviderCatalog.DEEPSEEK).model)

        val qwen = ProviderConfigCodec.fromLegacy(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "sk",
            "qwen3.8-max",
        )
        assertEquals(setOf(ProviderCatalog.QWEN), qwen.keys)

        val custom = ProviderConfigCodec.fromLegacy("https://my.proxy/v1", "sk", "m")
        assertEquals(setOf(ProviderCatalog.CUSTOM), custom.keys)

        val go = ProviderConfigCodec.fromLegacy("https://opencode.ai/zen/go/v1", "sk", "deepseek-v4.1-flash")
        assertEquals(setOf(ProviderCatalog.OPENCODE_GO), go.keys)
    }

    @Test
    fun legacyBlankValuesFillPresetDefaults() {
        val blankModel = ProviderConfigCodec.fromLegacy("https://api.deepseek.com/v1", "sk", "")
        assertEquals("deepseek-flash", blankModel.getValue(ProviderCatalog.DEEPSEEK).model)

        // 全新安装（没有旧 key）：必须落回 DeepSeek 默认，而不是空的 custom。
        val freshInstall = ProviderConfigCodec.fromLegacy("", "", "")
        assertEquals(setOf(ProviderCatalog.DEEPSEEK), freshInstall.keys)
        val entry = freshInstall.getValue(ProviderCatalog.DEEPSEEK)
        assertEquals("https://api.deepseek.com/v1", entry.baseUrl)
        assertEquals("deepseek-flash", entry.model)
        assertEquals("", entry.apiKey)
    }
}
