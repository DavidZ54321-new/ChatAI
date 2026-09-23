package com.zcw.chatai.data.web.mimo

import com.zcw.chatai.data.model.ToolSource
import com.zcw.chatai.data.web.WebSearchResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 解析 MiMo Chat 面 `web_search` 插件的响应。
 *
 * 来源在 `choices[0].message.annotations[]`（`type=url_citation`），
 * 不是 Anthropic/Responses 的工具结果块；正文在同级 `message.content`。
 * 畸形/缺字段一律降级为空结果（让回退链借道下一个后端），从不抛。纯函数，JVM 可测。
 */
object MiMoSearchParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String, maxResults: Int): WebSearchResult {
        val response = try {
            json.decodeFromString(ChatCompletionResponse.serializer(), raw)
        } catch (t: Exception) {
            return WebSearchResult()
        }
        val message = response.choices.firstOrNull()?.message ?: return WebSearchResult()
        val answer = message.content?.takeIf { it.isNotBlank() }
        val sources = mutableListOf<ToolSource>()
        val seen = HashSet<String>()
        message.annotations.orEmpty().forEach { annotation ->
            if (annotation.type?.takeIf { it.isNotBlank() }?.let { it != "url_citation" } == true) {
                return@forEach
            }
            val url = annotation.url.orEmpty()
            if (url.isBlank() || !seen.add(url)) return@forEach
            sources += ToolSource(
                url = url,
                title = annotation.title,
                snippet = annotation.summary,
                publishedAt = annotation.publishTime,
            )
        }
        val capped = if (maxResults in 1 until sources.size) sources.take(maxResults) else sources
        return WebSearchResult(answer = answer, sources = capped)
    }
}

@Serializable
private data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList(),
)

@Serializable
private data class Choice(
    val message: Message? = null,
)

@Serializable
private data class Message(
    val content: String? = null,
    val annotations: List<Annotation>? = null,
)

@Serializable
private data class Annotation(
    val type: String? = null,
    val url: String? = null,
    val title: String? = null,
    val summary: String? = null,
    @SerialName("publish_time") val publishTime: String? = null,
)
