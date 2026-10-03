package com.zcw.chatai.data.image.qwen

import com.zcw.chatai.data.image.ImageBackend
import com.zcw.chatai.data.image.ImageGenerateRequest
import com.zcw.chatai.data.image.ImageInputs
import com.zcw.chatai.data.image.ImageModelOption
import com.zcw.chatai.data.image.ImageModels
import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.DashScopeImageClient
import com.zcw.chatai.data.net.QwenImageRequest
import com.zcw.chatai.data.provider.ProviderCatalog
import kotlinx.coroutines.CancellationException

/**
 * 通义千问图像后端。生成仍走现有的 DashScope 同步客户端；
 * 模型列表走百炼 `GET /api/v1/models`。
 */
class QwenImageBackend(
    private val client: DashScopeImageClient,
) : ImageBackend {

    override val providerId: String = ProviderCatalog.QWEN

    override val maxInputImages: Int = ImageInputs.MAX_IMAGES

    override fun builtinModels(): List<ImageModelOption> =
        ImageModels.presets.map { id -> ImageModelOption(id, id, acceptsImageInput = true) }

    override suspend fun listModels(config: ChatConfig): List<ImageModelOption> {
        val collected = LinkedHashMap<String, ImageModelOption>()
        var page = 1
        var total = Int.MAX_VALUE
        while (page <= QwenImageModelList.MAX_PAGES && collected.size < total) {
            val url = QwenImageModelList.url(config.baseUrl, page) ?: break
            val text = try {
                client.getText(url, config.apiKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                break
            }
            when (val outcome = QwenImageModelList.parse(text)) {
                is QwenModelListOutcome.Page -> {
                    if (outcome.models.isEmpty()) break
                    total = outcome.total
                    outcome.models.forEach { option -> collected.putIfAbsent(option.id, option) }
                    page++
                }
                else -> break
            }
        }
        return collected.values.toList()
    }

    override suspend fun generate(config: ChatConfig, request: ImageGenerateRequest): List<ByteArray> {
        val qwen = QwenImageRequest(
            model = request.model,
            prompt = request.prompt,
            images = request.images,
            n = 1,
            size = if (request.images.isEmpty()) TEXT_TO_IMAGE_SIZE else null,
            promptExtend = request.promptExtend,
            watermark = false,
        )
        return client.generate(config, qwen).map { url -> client.download(url) }
    }

    companion object {
        private const val TEXT_TO_IMAGE_SIZE = "1024*1024"
    }
}
