package com.zcw.chatai.data.image.openai

import com.zcw.chatai.data.image.ImageBackend
import com.zcw.chatai.data.image.ImageGenerateRequest
import com.zcw.chatai.data.image.ImageModelOption
import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.ChatApi
import com.zcw.chatai.data.net.OpenAiImageClient
import com.zcw.chatai.data.provider.ProviderCatalog
import kotlinx.coroutines.CancellationException

/** OpenAI 图像后端：Images API 出图，`GET /v1/models` 刷模型名单。 */
class OpenAiImageBackend(
    private val client: OpenAiImageClient,
    private val chatApi: ChatApi,
) : ImageBackend {

    override val providerId: String = ProviderCatalog.OPENAI

    /** Images API 编辑端点单次最多 16 张参考图。 */
    override val maxInputImages: Int = 16

    override fun builtinModels(): List<ImageModelOption> = OpenAiImageModels.builtin

    override suspend fun listModels(config: ChatConfig): List<ImageModelOption> = try {
        OpenAiImageModels.filter(chatApi.listModels(config))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        emptyList()
    }

    override suspend fun generate(config: ChatConfig, request: ImageGenerateRequest): List<ByteArray> =
        client.generate(
            config = config,
            model = request.model,
            prompt = request.prompt,
            images = request.images,
            options = request.openAi,
        )
}
