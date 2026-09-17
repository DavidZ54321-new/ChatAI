package com.zcw.chatai.data.model

/**
 * 一次请求的全部可配置项。字段全部保持**厂商中立**：
 *
 * - [reasoningEffort] 用 OpenAI 标准的 `reasoning_effort`（`none` 即关闭思考），
 *   不发送厂商专有字段；为 null 时不带该字段，尊重服务端默认。
 * - [maxTokens] 为 null 时不发送，让服务端用默认值（思考模式下默认 64K，设小了会被思维链吃光）。
 * - [imageDetail] 对应标准 `image_url.detail`（low/high/original/auto），null 时不发送。
 * - [extraParams] 是兼容逃生口：一段 JSON 对象，顶层键深度合并进请求体
 *   （`model`/`messages`/`stream` 三个键受保护，不允许被覆盖）。
 * - [historyImageLimit] 历史带图消息的重发上限：-1 全部，0 不发历史图片，N 只发最近 N 条。
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
    val historyImageLimit: Int = 2,
    val extraParams: String? = null,
)
