package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.SearchedImage
import com.zcw.chatai.data.model.ToolSource
import com.zcw.chatai.data.net.dto.ChatTool
import com.zcw.chatai.data.net.dto.FunctionSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 一次搜索的结果：可选的 provider 综合回答 + 可引用来源。 */
data class WebSearchResult(val answer: String? = null, val sources: List<ToolSource> = emptyList())

/** 一次抓取的结果；非 2xx 也是结果，不抛异常。 */
data class WebFetchResult(
    val url: String,
    val statusCode: Int,
    val text: String,
    val truncated: Boolean,
)

/** 模型可见的工具：schema、参数解析、结果文本格式化。 */
object WebTools {

    const val SEARCH = "web_search"
    const val FETCH = "web_fetch"
    const val SEARCH_IMAGES = "search_images"
    const val FIND_SIMILAR_IMAGES = "find_similar_images"
    const val DEFAULT_MAX_RESULTS = 5
    const val DEFAULT_IMAGE_RESULTS = 10
    const val MAX_FETCH_CHARS = 20_000
    const val MAX_SEARCH_ANSWER_CHARS = 8_000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun specs(): List<ChatTool> = listOf(searchSpec(), fetchSpec(), searchImagesSpec(), findSimilarImagesSpec())

    /** 按本回合允许的工具名单挑选 schema（顺序稳定，便于测试）。 */
    fun specsFor(names: List<String>): List<ChatTool> {
        if (names.isEmpty()) return emptyList()
        val wanted = names.toSet()
        return specs().filter { it.function.name in wanted }
    }

    fun queryOf(arguments: String): String? = stringArg(arguments, "query")

    fun urlOf(arguments: String): String? = stringArg(arguments, "url")

    private fun stringArg(arguments: String, key: String): String? {
        if (arguments.isBlank()) return null
        val obj = try {
            json.parseToJsonElement(arguments) as? JsonObject
        } catch (t: Exception) {
            null
        } ?: return null
        return (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    fun formatSearchResult(query: String, result: WebSearchResult): String {
        val builder = StringBuilder()
        builder.append("Search results for \"").append(query).append("\":\n")
        result.answer?.takeIf { it.isNotBlank() }?.let { answer ->
            val body = if (answer.length > MAX_SEARCH_ANSWER_CHARS) {
                answer.take(MAX_SEARCH_ANSWER_CHARS) + "(answer truncated)"
            } else {
                answer
            }
            builder.append(body).append("\n\n")
        }
        if (result.sources.isEmpty() && result.answer.isNullOrBlank()) {
            builder.append("No results found.\n")
        } else if (result.sources.isNotEmpty()) {
            builder.append("Sources:\n")
            result.sources.forEach { source ->
                builder.append("- ").append(source.title ?: source.url).append(" — ").append(source.url)
                source.snippet?.takeIf { it.isNotBlank() }?.let { builder.append("\n  ").append(it) }
                builder.append("\n")
            }
            builder.append("Cite the relevant URLs above as markdown links in your answer.")
        }
        return builder.toString()
    }

    fun formatFetchResult(result: WebFetchResult): String {
        val header = "Fetched ${result.url} (HTTP ${result.statusCode}):\n"
        val body = result.text.take(MAX_FETCH_CHARS)
        val note = if (result.truncated || result.text.length > MAX_FETCH_CHARS) "\n[content truncated]" else ""
        return header + body + note
    }

    /** 图搜结果给模型：图片 URL 列表 + 在正文里直接用 markdown 图片的提示。 */
    fun formatImageSearchResult(query: String, images: List<SearchedImage>): String {
        if (images.isEmpty()) return "No images found for \"$query\"."
        val builder = StringBuilder()
        builder.append("Image search results for \"").append(query).append("\":\n")
        images.forEach { image ->
            builder.append("- ").append(image.title.ifBlank { "image" })
            builder.append(" — ").append(image.url).append("\n")
        }
        builder.append("Use the relevant URLs in your answer as markdown images: ![title](url).")
        return builder.toString()
    }

    private fun searchSpec() = ChatTool(
        function = FunctionSpec(
            name = SEARCH,
            description = "Search the web for current information. Use it when the answer needs up-to-date or external facts.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "The search query.")
                    }
                }
                put("required", JsonArray(listOf(JsonPrimitive("query"))))
                put("additionalProperties", false)
            },
        ),
    )

    private fun fetchSpec() = ChatTool(
        function = FunctionSpec(
            name = FETCH,
            description = "Fetch the full text of one http(s) URL, e.g. to read a specific search result.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Absolute http(s) URL to read.")
                    }
                }
                put("required", JsonArray(listOf(JsonPrimitive("url"))))
                put("additionalProperties", false)
            },
        ),
    )

    private fun searchImagesSpec() = ChatTool(
        function = FunctionSpec(
            name = SEARCH_IMAGES,
            description = "Search the web for images matching a text description (text-to-image search). " +
                "Use it when the user asks to find images, pictures, wallpapers or visual references.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Image search query, e.g. '科技感 PPT 封面背景'.")
                    }
                }
                put("required", JsonArray(listOf(JsonPrimitive("query"))))
                put("additionalProperties", false)
            },
        ),
    )

    private fun findSimilarImagesSpec() = ChatTool(
        function = FunctionSpec(
            name = FIND_SIMILAR_IMAGES,
            description = "Search the web for images visually similar to the most recent image the user " +
                "sent in this conversation (image-to-image search). Only call it when the conversation " +
                "already contains a user image; it always uses that image, no arguments needed.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
                put("additionalProperties", false)
            },
        ),
    )
}
