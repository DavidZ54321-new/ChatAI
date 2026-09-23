package com.zcw.chatai.data.web.qwen

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.EndpointUrl
import com.zcw.chatai.data.net.QwenResponsesClient
import com.zcw.chatai.data.web.ImageSearchOutcome
import com.zcw.chatai.data.web.ImageSearchProvider
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
                "用 web_search_image 只搜索一次。下面整段是同一张画面，检索词必须整段一起用，" +
                    "不要按空格拆开，也不要给每个名词各搜一次。" +
                    "不要自行补「卡通」「高清」「图片」这类泛词。\n\n" +
                    "要找的画面：$query",
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
        return ImageSearchOutcome(
            images = capped,
            answer = QwenResponsesParser.lastMessageText(raw)
        )
    }
}
