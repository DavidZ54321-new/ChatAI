package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.SearchedImage
import com.zcw.chatai.data.model.ToolSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Qwen Responses API 的非流式响应解析（纯函数，JVM 可测）。
 *
 * 结构容错：任何层级异常都退化为空结果/跳过该项，绝不抛异常——
 * 工具失败应该表现为「没有结果」，而不是把整轮对话标红。
 */
object QwenResponsesParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 文本联网搜索。实测响应形状：
     * `output[*]` 是 `reasoning | web_search_call | message` 的混合序列；
     * 来源在 `web_search_call.action.sources`，元素为 `{"type":"url","url":…}`——**没有 title**；
     * 答案取最后一个 `message` 项的 `output_text`（文档明确没有 annotations 角标）。
     */
    fun parseTextSearch(raw: String): WebSearchResult {
        val output = outputItems(raw) ?: return WebSearchResult()
        val sources = LinkedHashMap<String, ToolSource>()
        output.forEach { item ->
            when (item.str("type")) {
                "web_search_call" -> {
                    val action = item["action"] as? JsonObject ?: return@forEach
                    val list = action["sources"] as? JsonArray ?: return@forEach
                    list.forEach { element ->
                        val source = element as? JsonObject ?: return@forEach
                        val url = source.str("url")?.takeIf { it.isNotBlank() } ?: return@forEach
                        if (url !in sources) {
                            sources[url] = ToolSource(url = url, title = source.str("title"))
                        }
                    }
                }
            }
        }
        return WebSearchResult(answer = messageText(output), sources = sources.values.toList())
    }

    /**
     * 图搜结果：`web_search_image_call` / `image_search_call` 的 `output` 是 **JSON 字符串**，
     * parse 后为 `[{index,title,url}]`（实测 t2i 30 条带 title、i2i 10 条同形状）。
     * `output[]` 里可能有多次调用，按 URL 去重、先见先留。
     */
    fun parseImages(raw: String, callTypes: List<String>): List<SearchedImage> {
        val output = outputItems(raw) ?: return emptyList()
        val types = callTypes.toSet()
        val images = LinkedHashMap<String, SearchedImage>()
        output.forEach { item ->
            if (item.str("type") !in types) return@forEach
            val encoded = item.str("output") ?: return@forEach
            val array = try {
                json.parseToJsonElement(encoded) as? JsonArray
            } catch (t: Exception) {
                null
            } ?: return@forEach
            array.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val url = obj.str("url")?.takeIf { it.isNotBlank() } ?: return@forEach
                if (url !in images) {
                    images[url] = SearchedImage(
                        index = obj.str("index")?.toIntOrNull() ?: (images.size + 1),
                        title = obj.str("title").orEmpty(),
                        url = url,
                    )
                }
            }
        }
        return images.values.toList()
    }

    /** 最后一个 `message` 项的 `output_text`（`output[]` 顺序不保证，取最后出现的）。 */
    fun lastMessageText(raw: String): String? {
        val output = outputItems(raw) ?: return null
        return messageText(output)
    }

    /** Responses 的用量里带着工具调用次数（`usage.x_tools.web_search.count`）。 */
    fun toolCallCount(raw: String, tool: String): Int? {
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (t: Exception) {
            return null
        }
        val count = root["usage"]
            ?.jsonObject
            ?.get("x_tools")
            ?.jsonObject
            ?.get(tool)
            ?.jsonObject
            ?.get("count")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
        return count?.toIntOrNull()
    }

    private fun outputItems(raw: String): List<JsonObject>? {
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (t: Exception) {
            return null
        }
        val output = root["output"] as? JsonArray ?: return null
        return output.mapNotNull { it as? JsonObject }
    }

    private fun messageText(output: List<JsonObject>): String? {
        var answer: String? = null
        output.forEach { item ->
            if (item.str("type") != "message") return@forEach
            val parts = item["content"] as? JsonArray ?: return@forEach
            parts.forEach { element ->
                val part = element as? JsonObject ?: return@forEach
                if (part.str("type") == "output_text") {
                    part.str("text")?.takeIf { it.isNotBlank() }?.let { answer = it }
                }
            }
        }
        return answer
    }

    /** 只有真正是 JsonPrimitive 时才取值，结构异常不会被 `jsonPrimitive` 抛异常打断。 */
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
