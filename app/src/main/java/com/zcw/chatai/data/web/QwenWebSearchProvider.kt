package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.EndpointUrl
import com.zcw.chatai.data.net.QwenResponsesClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Qwen 联网搜索：主回路发起的函数调用，内部用 Responses API 的 `web_search` 工具执行。
 *
 * - 模型 agent 式多轮检索（一次提问可能触发多次搜索），天然慢，客户端超时给到 200s；
 * - 来源是 `web_search_call.action.sources`（无 title），工具结果沿用统一格式（URL 列表 + 答案）。
 */
class QwenWebSearchProvider(
    private val client: QwenResponsesClient = QwenResponsesClient(),
) : WebSearchProvider {

    override val id: String = "qwen-responses"

    override fun available(baseUrl: String, apiKey: String): Boolean =
        apiKey.isNotBlank() && EndpointUrl.responses(baseUrl) != null

    override suspend fun search(query: String, maxResults: Int, config: ChatConfig): WebSearchResult {
        val payload = buildJsonObject {
            put("model", config.model)
            put(
                "input",
                "请联网搜索以下内容：调用 web_search 工具获取最新信息，" +
                    "然后用中文简要总结关键事实。\n\n搜索内容：$query",
            )
            putJsonArray("tools") {
                add(buildJsonObject { put("type", "web_search") })
            }
            // 纯工具调用不需要思维链：实测关掉后明显更快，也不影响搜索质量。
            put("enable_thinking", false)
        }
        val raw = client.execute(config, payload)
        val result = QwenResponsesParser.parseTextSearch(raw)
        // Responses 的 web_search 没有 max_results/max_uses 参数，这里按预算裁掉多余来源，
        // 避免工具结果把上下文撑爆。
        return if (maxResults in 1 until result.sources.size) {
            result.copy(sources = result.sources.take(maxResults))
        } else {
            result
        }
    }
}
