package com.zcw.chatai.data.model

import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.provider.ThinkingWire

/**
 * 一次请求的全部可配置项。字段全部保持**厂商中立**：
 *
 * - [reasoningEffort] 用 OpenAI 标准的 `reasoning_effort`（`none` 即关闭思考），
 *   不发送厂商专有字段；为 null 时不带该字段，尊重服务端默认。
 * - [thinkingWire] 决定思考开关的**上行序列化风格**（由供应商预设提供）：
 *   标准面发 `reasoning_effort`，MiMo 发非标准 `thinking:{type}` 对象——差异走数据，不是 if-vendor。
 * - [maxTokens] 为 null 时不发送，让服务端用默认值（思考模式下默认 64K，设小了会被思维链吃光）。
 * - [imageDetail] 对应标准 `image_url.detail`（low/high/original/auto），null 时不发送。
 * - [extraParams] 是兼容逃生口：一段 JSON 对象，顶层键深度合并进请求体
 *   （`model`/`messages`/`stream` 三个键受保护，不允许被覆盖）。
 * - [historyImageTurns] 历史图片按**轮次**重发：-1 全部，0 只发当前轮，N = 当前轮 + 最近 N 轮；
 *   「最后一条带附件的消息」永远保留（它就是本轮内容）。
 */
data class ChatConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val temperature: Double?,
    val reasoningEffort: String? = null,
    val maxTokens: Int? = null,
    val imageDetail: String? = null,
    val includeUsage: Boolean = true,
    /** 上下文末尾是否追加当前时间尾条（设置开关，默认开；取值失败时不追加）。 */
    val includeEnvTime: Boolean = true,
    val historyImageTurns: Int = 1,
    val extraParams: String? = null,
    /** 本回合是否注入 web_search/web_fetch 工具。 */
    val webSearchEnabled: Boolean = false,
    /**
     * 本回合实际注入的工具名单（`WebTools` 的常量）。
     * 由仓库层按供应商能力与后端可用性算好，网络层只负责按名单组装 schema。
     */
    val enabledTools: List<String> = emptyList(),
    /** Agent 最多几轮工具调用（不含最后的无工具收尾步）。 */
    val maxAgentSteps: Int = 6,
    /** 本回合绑定哪个供应商（决定搜索/图搜后端与文件上传路由）。 */
    val providerId: String = ProviderCatalog.DEEPSEEK,
    /** 网关要求的稳定会话 id（配合 [sendSessionHeader]，Go 实测缺了直接 400）。 */
    val sessionId: String? = null,
    /** 是否发送会话头（由供应商预设决定，见 `ProviderPreset.sendSessionHeader`）。 */
    val sendSessionHeader: Boolean = false,
    /** Anthropic 工具面 v1 基址（已解析；空则搜索客户端按 layout 从 [baseUrl] 推导）。 */
    val anthropicBaseUrl: String = "",
    /** Responses 工具面 v1 基址（已解析；空则从 [baseUrl] 推导）。 */
    val responsesBaseUrl: String = "",
    /** 思考字段上行风格（由供应商预设决定，见 `ProviderPreset.thinkingWire`）。 */
    val thinkingWire: ThinkingWire = ThinkingWire.STANDARD_REASONING_EFFORT,
)
