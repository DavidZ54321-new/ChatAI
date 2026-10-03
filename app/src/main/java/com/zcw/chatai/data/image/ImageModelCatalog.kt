package com.zcw.chatai.data.image

import com.zcw.chatai.data.provider.ProviderCatalog

/**
 * 图像模型目录（纯函数）。
 *
 * 某个供应商的远程列表非空时盖住它的内置名单；空则留内置。
 * 对不上任何名单的模型 id 归到通义千问（设置页自由文本的旧口径）。
 */
object ImageModelCatalog {

    fun sections(
        backends: List<ImageBackend>,
        remote: Map<String, List<ImageModelOption>>,
    ): List<ImageModelSection> = backends.map { backend ->
        ImageModelSection(
            providerId = backend.providerId,
            title = ProviderCatalog.displayName(backend.providerId),
            models = modelsOf(backend, remote),
        )
    }

    fun providerOf(
        model: String,
        backends: List<ImageBackend>,
        remote: Map<String, List<ImageModelOption>>,
    ): String {
        val id = model.trim()
        if (id.isEmpty()) return ProviderCatalog.QWEN
        for (backend in backends) {
            if (modelsOf(backend, remote).any { it.id == id }) return backend.providerId
        }
        return ProviderCatalog.QWEN
    }

    fun selection(
        backends: List<ImageBackend>,
        remote: Map<String, List<ImageModelOption>>,
        model: String,
        storedProviderId: String?,
    ): ImageSelection {
        val known = storedProviderId?.takeIf { id -> backends.any { it.providerId == id } }
        val providerId = known ?: providerOf(model, backends, remote)
        val backend = backends.firstOrNull { it.providerId == providerId } ?: backends.firstOrNull()
        val accepts = option(providerId, model, backends, remote)?.acceptsImageInput ?: true
        return ImageSelection(
            providerId = providerId,
            providerLabel = ProviderCatalog.displayName(providerId),
            maxInputImages = backend?.maxInputImages ?: ImageInputs.MAX_IMAGES,
            acceptsImageInput = accepts,
        )
    }

    fun option(
        providerId: String,
        model: String,
        backends: List<ImageBackend>,
        remote: Map<String, List<ImageModelOption>>,
    ): ImageModelOption? {
        val backend = backends.firstOrNull { it.providerId == providerId } ?: return null
        val id = model.trim()
        return modelsOf(backend, remote).firstOrNull { it.id == id }
    }

    fun acceptsImageInput(
        providerId: String,
        model: String,
        backends: List<ImageBackend>,
        remote: Map<String, List<ImageModelOption>>,
    ): Boolean = option(providerId, model, backends, remote)?.acceptsImageInput ?: true

    fun missingKey(providerId: String): String {
        val name = ProviderCatalog.displayName(providerId)
        return "生图需要${name}的 API Key，请到设置里配置"
    }

    private fun modelsOf(
        backend: ImageBackend,
        remote: Map<String, List<ImageModelOption>>,
    ): List<ImageModelOption> =
        remote[backend.providerId]?.takeIf { it.isNotEmpty() } ?: backend.builtinModels()
}
