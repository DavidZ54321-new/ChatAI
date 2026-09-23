package com.zcw.chatai.data.provider

import com.zcw.chatai.data.media.AttachmentLimits

/** 供应商能力（纯数据，驱动 UI 默认行为；不硬性拦截请求，不支持时给可读报错）。 */
data class ProviderCaps(
    val image: Boolean = true,
    val video: Boolean = false,
    val textSearch: Boolean = false,
    val imageSearch: Boolean = false,
    /** 音频理解（附件面板门禁与发送前预检共用；MiMo 首发）。 */
    val audio: Boolean = false,
)

/**
 * 从 Chat 兼容 Base URL 推导 Anthropic 工具面的规则。
 * 主对话仍走 Chat Completions；这两面只给搜索等侧信道工具。
 */
enum class AnthropicBaseLayout {
    /** DeepSeek / MiMo：`{origin}/anthropic/v1`。 */
    ORIGIN_ANTHROPIC,
    /** 通义千问：`{origin}/apps/anthropic/v1`（不是 compatible-mode）。 */
    ORIGIN_APPS_ANTHROPIC,
    /** OpenCode Go / 自定义：与 Chat 同一个 `/v1` 根。 */
    SAME_V1,
}

/**
 * 思考字段的上行风格（数据驱动，不在请求层写 if-vendor）。
 * 标准面发 OpenAI 的 `reasoning_effort`；MiMo 只认自家 `thinking:{type}` 对象。
 */
enum class ThinkingWire {
    /** 标准 `reasoning_effort`（`none` 即关闭思考）。 */
    STANDARD_REASONING_EFFORT,
    /** MiMo：非标准 `thinking:{type:"enabled"|"disabled"}` 对象。 */
    MIMO_THINKING_OBJECT,
}

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
    val anthropicBaseLayout: AnthropicBaseLayout = AnthropicBaseLayout.SAME_V1,
    /**
     * 视频走内联 base64 的字节上限；null = 沿用全局 `AttachmentLimits.VIDEO_INLINE_MAX_BYTES`（5MiB）。
     * MiMo 无 DashScope 上传路由，只认 base64 ≤50MB 编码线 → 单独抬到 ~35MiB。
     */
    val videoInlineMaxBytes: Long? = null,
    /** 是否允许 DashScope `oss://` 大文件上传路由（当前仅 Qwen 为 true；MiMo 等无此服务）。 */
    val videoUploadViaDashScope: Boolean = false,
    /** 思考字段上行风格，决定网络层怎么序列化思考开关。 */
    val thinkingWire: ThinkingWire = ThinkingWire.STANDARD_REASONING_EFFORT,
)

/** 一套供应商连接配置（DataStore `providers_json` 的持久化单元）。 */
data class ProviderEntry(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** Anthropic 工具面 Base；空 = 按 [ProviderPreset.anthropicBaseLayout] 从 [baseUrl] 推导。 */
    val anthropicBaseUrl: String = "",
    /** Responses 工具面 Base；空 = 与 Chat 同一个 `/v1` 根。 */
    val responsesBaseUrl: String = "",
)

/**
 * 内置供应商表：厂商差异靠数据消化，新增供应商主要在这里加一条。
 * id 会写进 `conversations.provider_id`，**不可随意改**。
 */
object ProviderCatalog {

    const val DEEPSEEK = "deepseek"
    const val QWEN = "qwen"
    const val OPENCODE_GO = "opencode-go"
    const val MIMO = "mimo"
    const val CUSTOM = "custom"

    val presets: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = DEEPSEEK,
            displayName = "DeepSeek",
            defaultBaseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-flash",
            caps = ProviderCaps(textSearch = true),
            anthropicBaseLayout = AnthropicBaseLayout.ORIGIN_ANTHROPIC,
        ),
        ProviderPreset(
            id = QWEN,
            displayName = "通义千问",
            defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen3.8-max",
            caps = ProviderCaps(video = true, textSearch = true, imageSearch = true),
            // 图搜借道 Qwen 时优先 27b，空结果/报错再退 max（实测 flash 对部分图返回空）。
            toolModels = listOf("qwen3.8-27b", "qwen3.8-max"),
            anthropicBaseLayout = AnthropicBaseLayout.ORIGIN_APPS_ANTHROPIC,
            // DashScope 临时上传路由仅 Qwen 有（oss:// + X-DashScope-OssResourceResolve 头）。
            videoUploadViaDashScope = true,
        ),
        ProviderPreset(
            id = OPENCODE_GO,
            displayName = "OpenCode Go",
            defaultBaseUrl = "https://opencode.ai/zen/go/v1",
            defaultModel = "deepseek-v4.1-flash",
            // 文本搜索走同一 v1 根的 Anthropic `/messages`（与 DeepSeek 同协议）；不支持视频。
            caps = ProviderCaps(image = true, textSearch = true),
            sendSessionHeader = true,
            anthropicBaseLayout = AnthropicBaseLayout.SAME_V1,
        ),
        ProviderPreset(
            id = MIMO,
            displayName = "MiMo",
            defaultBaseUrl = "https://api.xiaomimimo.com/v1",
            defaultModel = "mimo-v2.6-flash",
            // 全模态（图片/视频/音频）+ Chat 面 web_search 插件；图搜无原生支持，仍借道 Qwen。
            caps = ProviderCaps(image = true, video = true, textSearch = true, audio = true),
            // Anthropic 面 = {origin}/anthropic/v1；Responses 面与 Chat 同 /v1 根（均由推导命中）。
            anthropicBaseLayout = AnthropicBaseLayout.ORIGIN_ANTHROPIC,
            // base64 编码后 ≤50MB 硬线，原始字节留头 35MiB；无 DashScope 上传路由。
            videoInlineMaxBytes = 35L * 1024 * 1024,
            videoUploadViaDashScope = false,
            thinkingWire = ThinkingWire.MIMO_THINKING_OBJECT,
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

    /** 该供应商是否支持音频输入（附件面板门禁与发送前预检共用同一条判定）。 */
    fun supportsAudio(id: String): Boolean = byId(id)?.caps?.audio == true

    /** 视频内联字节上限；未配置的供应商回落到全局默认（5MiB）。 */
    fun videoInlineMaxBytesFor(id: String): Long =
        byId(id)?.videoInlineMaxBytes ?: AttachmentLimits.VIDEO_INLINE_MAX_BYTES

    /** 是否走 DashScope `oss://` 上传路由（仅 Qwen；其余供应商超内联上限只能拒绝）。 */
    fun videoUploadViaDashScope(id: String): Boolean =
        byId(id)?.videoUploadViaDashScope == true

    /** 该供应商思考字段的上行风格。 */
    fun thinkingWireFor(id: String): ThinkingWire =
        byId(id)?.thinkingWire ?: ThinkingWire.STANDARD_REASONING_EFFORT

    /** 由基址推断供应商（旧单配置懒迁移用）；命中不了归 custom。 */
    fun matchByBaseUrl(baseUrl: String): String {
        val lower = baseUrl.lowercase()
        return when {
            lower.contains("deepseek") -> DEEPSEEK
            lower.contains("dashscope") || lower.contains("aliyuncs.com") -> QWEN
            lower.contains("xiaomimimo.com") -> MIMO
            lower.contains("opencode.ai") -> OPENCODE_GO
            else -> CUSTOM
        }
    }
}
