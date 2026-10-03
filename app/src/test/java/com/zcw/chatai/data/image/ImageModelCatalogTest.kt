package com.zcw.chatai.data.image

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.provider.ProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ImageModelCatalogTest {

    private val qwen = fake(
        ProviderCatalog.QWEN,
        maxImages = 3,
        ImageModelOption("qwen-image-3.0-pro", "qwen-image-3.0-pro", true),
    )
    private val openai = fake(
        ProviderCatalog.OPENAI,
        maxImages = 16,
        ImageModelOption("gpt-image-2.5-sunburst", "gpt-image-2.5-sunburst", true),
    )
    private val backends = listOf(qwen, openai)

    @Test
    fun emptyRemoteKeepsBuiltinSectionsInRegistrationOrder() {
        val sections = ImageModelCatalog.sections(backends, emptyMap())
        assertEquals(listOf(ProviderCatalog.QWEN, ProviderCatalog.OPENAI), sections.map { it.providerId })
        assertEquals("通义千问", sections[0].title)
        assertEquals("OpenAI", sections[1].title)
        assertEquals("qwen-image-3.0-pro", sections[0].models.single().id)
    }

    @Test
    fun remoteReplacesOnlyThatProvider() {
        val remote = mapOf(
            ProviderCatalog.QWEN to listOf(
                ImageModelOption("qwen-image-max", "Qwen-Image-Max", acceptsImageInput = false),
            ),
        )
        val sections = ImageModelCatalog.sections(backends, remote)
        assertEquals("qwen-image-max", sections[0].models.single().id)
        assertEquals("gpt-image-2.5-sunburst", sections[1].models.single().id)
        assertEquals(ProviderCatalog.QWEN, ImageModelCatalog.providerOf("qwen-image-max", backends, remote))
        assertFalse(
            ImageModelCatalog.acceptsImageInput(ProviderCatalog.QWEN, "qwen-image-max", backends, remote),
        )
    }

    @Test
    fun unknownModelFallsBackToQwen() {
        assertEquals(
            ProviderCatalog.QWEN,
            ImageModelCatalog.providerOf("custom-sketch", backends, emptyMap()),
        )
    }

    @Test
    fun storedProviderWinsOverModelLookup() {
        val selection = ImageModelCatalog.selection(
            backends,
            emptyMap(),
            model = "gpt-image-2.5-sunburst",
            storedProviderId = ProviderCatalog.QWEN,
        )
        assertEquals(ProviderCatalog.QWEN, selection.providerId)
        assertEquals(3, selection.maxInputImages)
    }

    @Test
    fun missingKeyNamesTheProvider() {
        assertEquals(
            "生图需要通义千问的 API Key，请到设置里配置",
            ImageModelCatalog.missingKey(ProviderCatalog.QWEN),
        )
        assertEquals(
            "生图需要OpenAI的 API Key，请到设置里配置",
            ImageModelCatalog.missingKey(ProviderCatalog.OPENAI),
        )
    }

    private fun fake(id: String, maxImages: Int, vararg models: ImageModelOption) = object : ImageBackend {
        override val providerId = id
        override val maxInputImages = maxImages
        override fun builtinModels() = models.toList()
        override suspend fun listModels(config: ChatConfig) = emptyList<ImageModelOption>()
        override suspend fun generate(config: ChatConfig, request: ImageGenerateRequest) = emptyList<ByteArray>()
    }
}
