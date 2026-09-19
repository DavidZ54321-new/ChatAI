package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.EndpointUrl
import kotlinx.coroutines.CancellationException

/**
 * OpenCode Go 搜索路由：同一个 providerId 下按**模型**选面。
 *
 * - Luna/Grok/Muse（[GoSearchFace.prefersResponses]）优先 `/responses`；
 * - 其余（默认 deepseek 系）优先 `/messages`；
 * - 首选面报错或空结果时借道另一面一次；两面都空/错才把结果（或异常）交出去，
 *   外层 [com.zcw.chatai.data.ChatRepository] 的跨供应商回退链会继续借道
 *   其它已配置后端（DeepSeek 官方/Qwen 等）。
 *
 * 换实现不动主回路：对仓库层它就是一个普通的 [WebSearchProvider]。
 */
class OpenCodeGoSearchRouter(
    private val messages: WebSearchProvider = DeepSeekNativeSearchProvider(),
    private val responses: WebSearchProvider = ResponsesWebSearchProvider(),
) : WebSearchProvider {

    override val id: String = "opencode-go-search"

    override fun available(baseUrl: String, apiKey: String): Boolean =
        apiKey.isNotBlank() && EndpointUrl.originOf(baseUrl) != null

    override suspend fun search(query: String, maxResults: Int, config: ChatConfig): WebSearchResult {
        val responsesFirst = GoSearchFace.prefersResponses(config.model)
        return if (responsesFirst) {
            tryFace(query, maxResults, config, Face.RESPONSES, Face.MESSAGES)
        } else {
            tryFace(query, maxResults, config, Face.MESSAGES, Face.RESPONSES)
        }
    }

    private enum class Face { MESSAGES, RESPONSES }

    private suspend fun tryFace(
        query: String,
        maxResults: Int,
        config: ChatConfig,
        first: Face,
        second: Face,
    ): WebSearchResult {
        val firstResult = runCatching { searchOn(first, query, maxResults, config) }
        val firstValue = firstResult.getOrNull()
        if (firstResult.isSuccess && !isEmpty(firstValue)) return firstValue as WebSearchResult
        // 首选面空结果/报错：借道另一面。取消必须原样传播，不借道。
        val firstError = firstResult.exceptionOrNull()
        if (firstError is CancellationException) throw firstError
        try {
            return searchOn(second, query, maxResults, config)
        } catch (secondError: CancellationException) {
            throw secondError
        } catch (secondError: Exception) {
            // 两面都失败：优先抛出面内的原始错误（信息更具体），而不是吞掉。
            // 若首选面是空结果、无异常，则抛次选面的异常；否则抛首选面的异常。
            if (firstResult.isSuccess) throw secondError
            throw (firstError ?: secondError)
        }
    }

    private suspend fun searchOn(
        face: Face,
        query: String,
        maxResults: Int,
        config: ChatConfig,
    ): WebSearchResult = when (face) {
        Face.MESSAGES -> messages.search(query, maxResults, config)
        Face.RESPONSES -> responses.search(query, maxResults, config)
    }

    private fun isEmpty(result: WebSearchResult?): Boolean =
        result == null || (result.sources.isEmpty() && result.answer.isNullOrBlank())
}
