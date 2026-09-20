package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.EndpointUrl
import com.zcw.chatai.data.net.QwenResponsesClient
import com.zcw.chatai.data.web.qwen.QwenResponsesParser
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 通用 Responses 面文本搜索：OpenCode Go 的 Luna/Grok/Muse 走这里。
 *
 * 与 [QwenWebSearchProvider] 的区别：
 * - 不发 Qwen 专有字段（`enable_thinking`），只发标准 `model/input/tools`；
 * - 不预设 `tool_choice`（Qwen 实测传了也当没有，先默认不带，靠提示词触发）；
 * - 网关头（`x-api-key` / `x-opencode-session` / UA）由 [QwenResponsesClient]
 *   按 [ChatConfig.sendSessionHeader] 统一补，不在这里拼。
 *
 * 解析复用 [com.zcw.chatai.data.web.qwen.QwenResponsesParser.parseTextSearch]（`web_search_call.action.sources`
 * + 最后一个 `message.output_text`）；形状不对会退化为空结果，外层回退链再借道
 * 其它已配置后端（DeepSeek 官方等），不把整轮标红。
 */
class ResponsesWebSearchProvider(
    private val client: QwenResponsesClient = QwenResponsesClient(),
) : WebSearchProvider {

    override val id: String = "responses-search"

    override fun available(baseUrl: String, apiKey: String): Boolean =
        apiKey.isNotBlank() && EndpointUrl.responses(baseUrl) != null

    override suspend fun search(query: String, maxResults: Int, config: ChatConfig): WebSearchResult {
        val payload = buildJsonObject {
            put("model", config.model)
            put(
                "input",
                "Use the web_search tool to find current information, " +
                    "then summarize the key facts briefly.\n\nQuery: $query",
            )
            putJsonArray("tools") {
                add(buildJsonObject { put("type", "web_search") })
            }
        }
        val raw = client.execute(config, payload)
        val result = QwenResponsesParser.parseTextSearch(raw)
        // Responses 的 web_search 没有 max_uses 参数，按预算裁掉多余来源，避免撑爆上下文。
        return if (maxResults in 1 until result.sources.size) {
            result.copy(sources = result.sources.take(maxResults))
        } else {
            result
        }
    }
}
