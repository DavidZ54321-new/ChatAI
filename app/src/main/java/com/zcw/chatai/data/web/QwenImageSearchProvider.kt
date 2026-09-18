package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.EndpointUrl
import com.zcw.chatai.data.net.QwenResponsesClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Qwen 图搜：主回路发起函数调用，内部用 Responses API 的
 * `web_search_image`（文搜图）/ `image_search`（以图搜图）执行。
 *
 * - 两个工具都只在 Responses 面存在；图搜一次 ~30s，且按次计费（24/48 元每千次）；
 * - i2i 的 `input_image` 支持 base64 data URI（实测），所以本地图不用先上传。
 */
class QwenImageSearchProvider(
    private val client: QwenResponsesClient = QwenResponsesClient(),
) : ImageSearchProvider {

    override val id: String = "qwen-responses"

    override fun available(baseUrl: String, apiKey: String): Boolean =
        apiKey.isNotBlank() && EndpointUrl.responses(baseUrl) != null

    override suspend fun searchByText(
        query: String,
        maxResults: Int,
        config: ChatConfig,
    ): ImageSearchOutcome {
        val payload = buildJsonObject {
            put("model", config.model)
            put(
                "input",
                "请调用 web_search_image 工具，根据以下描述搜索互联网图片，" +
                    "然后用中文简要说明找到的图片适合什么场景。\n\n搜索内容：$query",
            )
            putJsonArray("tools") {
                add(buildJsonObject { put("type", "web_search_image") })
            }
            put("enable_thinking", false)
        }
        return outcome(client.execute(config, payload), maxResults)
    }

    override suspend fun searchByImage(
        imageDataUrl: String,
        hint: String?,
        maxResults: Int,
        config: ChatConfig,
    ): ImageSearchOutcome {
        val hintText = hint?.takeIf { it.isNotBlank() } ?: "请找出与这张图视觉相似的图片"
        val payload = buildJsonObject {
            put("model", config.model)
            putJsonArray("input") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "input_text")
                                    put("text", hintText)
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "input_image")
                                    put("image_url", imageDataUrl)
                                },
                            )
                        }
                    },
                )
            }
            putJsonArray("tools") {
                add(buildJsonObject { put("type", "image_search") })
            }
            put("enable_thinking", false)
        }
        return outcome(client.execute(config, payload), maxResults)
    }

    private fun outcome(raw: String, maxResults: Int): ImageSearchOutcome {
        val images = QwenResponsesParser.parseImages(
            raw,
            callTypes = listOf("web_search_image_call", "image_search_call"),
        )
        val capped = if (maxResults in 1 until images.size) images.take(maxResults) else images
        return ImageSearchOutcome(images = capped, answer = QwenResponsesParser.lastMessageText(raw))
    }
}
