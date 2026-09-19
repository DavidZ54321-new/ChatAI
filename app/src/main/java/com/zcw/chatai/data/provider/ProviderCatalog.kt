package com.zcw.chatai.data.provider

/** 供应商能力（纯数据，驱动 UI 默认行为；不硬性拦截请求，不支持时给可读报错）。 */
data class ProviderCaps(
    val image: Boolean = true,
    val video: Boolean = false,
    val textSearch: Boolean = false,
    val imageSearch: Boolean = false,
)

data class ProviderPreset(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val caps: ProviderCaps,
    /** 网关要求每段对话带稳定的会话头（如 OpenCode Go 的 x-opencode-session）。 */
    val sendSessionHeader: Boolean = false,
    /**
     * 该供应商原生工具的默认模型优先级（可被设置覆盖）。
     * 图搜（文搜图/图搜图）用它，与对话模型解耦——见 `ToolModels`。
     */
    val toolModels: List<String> = emptyList(),
)

/** 一套供应商连接配置（DataStore `providers_json` 的持久化单元）。 */
data class ProviderEntry(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

/**
 * 内置供应商表：厂商差异靠数据消化，新增供应商主要在这里加一条。
 * id 会写进 `conversations.provider_id`，**不可随意改**。
 */
object ProviderCatalog {

    const val DEEPSEEK = "deepseek"
    const val QWEN = "qwen"
    const val OPENCODE_GO = "opencode-go"
    const val CUSTOM = "custom"

    val presets: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = DEEPSEEK,
            displayName = "DeepSeek",
            defaultBaseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-flash",
            caps = ProviderCaps(textSearch = true),
        ),
        ProviderPreset(
            id = QWEN,
            displayName = "通义千问",
            defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen3.8-max",
            caps = ProviderCaps(video = true, textSearch = true, imageSearch = true),
            // 图搜借道 Qwen 时优先 27b，空结果/报错再退 max（实测 flash 对部分图返回空）。
            toolModels = listOf("qwen3.8-27b", "qwen3.8-max"),
        ),
        ProviderPreset(
            id = OPENCODE_GO,
            displayName = "OpenCode Go",
            defaultBaseUrl = "https://opencode.ai/zen/go/v1",
            defaultModel = "deepseek-v4.1-flash",
            // 聚合网关：不带原生搜索/图搜工具；vision-exp 模型支持图片，不支持视频。
            caps = ProviderCaps(image = true),
            sendSessionHeader = true,
        ),
        ProviderPreset(
            id = CUSTOM,
            displayName = "自定义",
            defaultBaseUrl = "",
            defaultModel = "",
            caps = ProviderCaps(),
        ),
    )

    fun byId(id: String): ProviderPreset? = presets.firstOrNull { it.id == id }

    fun displayName(id: String): String = byId(id)?.displayName ?: id

    /**
     * 该供应商实际生效的对话模型：条目里填过的优先，没填则回落到预设默认。
     * 切换会话供应商时用它重置模型（纯函数，JVM 可测）。
     */
    fun defaultModelFor(providers: Map<String, ProviderEntry>, id: String): String =
        providers[id]?.model?.takeIf { it.isNotBlank() } ?: byId(id)?.defaultModel.orEmpty()

    /** 该供应商是否支持视频输入（附件面板门禁与发送前预检共用同一条判定）。 */
    fun supportsVideo(id: String): Boolean = byId(id)?.caps?.video == true

    /** 由基址推断供应商（旧单配置懒迁移用）；命中不了归 custom。 */
    fun matchByBaseUrl(baseUrl: String): String {
        val lower = baseUrl.lowercase()
        return when {
            lower.contains("deepseek") -> DEEPSEEK
            lower.contains("dashscope") || lower.contains("aliyuncs.com") -> QWEN
            lower.contains("opencode.ai") -> OPENCODE_GO
            else -> CUSTOM
        }
    }
}
