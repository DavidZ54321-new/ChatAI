package com.zcw.chatai.data.provider

/** 工具后端选择结果：null 表示该工具没有可用后端（不注入对应工具）。 */
data class ToolBackends(
    val textProviderId: String? = null,
    val imageProviderId: String? = null,
)

/**
 * 工具后端与主对话供应商**解耦**：主回路可以是任意模型（DeepSeek/GLM/…），
 * 联网搜索/图搜这些"高级工具"借道对应供应商的 API 执行。
 *
 * 规则（纯函数，JVM 可测）：
 * - 文本搜索：显式选择 → 会话供应商（caps 支持）→ 按 preset 顺序回退到第一个已配置且支持的；
 * - 图搜：固定取已配置且支持的 Qwen（目前唯一实现）；
 * - "已配置" = 出现在 providers 表里；key/URL 的可用性由调用方用 `available()` 再判。
 */
object ToolBackendResolver {

    fun resolve(
        providers: Map<String, ProviderEntry>,
        activeProviderId: String?,
        conversationProviderId: String?,
        preferredSearchProviderId: String?,
    ): ToolBackends = ToolBackends(
        textProviderId = resolveText(providers, conversationProviderId ?: activeProviderId, preferredSearchProviderId),
        imageProviderId = resolveImage(providers),
    )

    private fun resolveText(
        providers: Map<String, ProviderEntry>,
        conversationProviderId: String?,
        preferredSearchProviderId: String?,
    ): String? {
        preferredSearchProviderId
            ?.takeIf { supportsTextSearch(it) && hasKey(providers, it) }
            ?.let { return it }
        conversationProviderId
            ?.takeIf { supportsTextSearch(it) && hasKey(providers, it) }
            ?.let { return it }
        return ProviderCatalog.presets
            .firstOrNull { it.caps.textSearch && hasKey(providers, it.id) }
            ?.id
    }

    private fun resolveImage(providers: Map<String, ProviderEntry>): String? =
        ProviderCatalog.presets
            .firstOrNull { it.caps.imageSearch && hasKey(providers, it.id) }
            ?.id

    /** 有 key 才算"配置好了"：只有空条目的供应商不能遮蔽后面真正可用的后端。 */
    private fun hasKey(providers: Map<String, ProviderEntry>, id: String): Boolean =
        providers[id]?.apiKey?.isNotBlank() == true

    private fun supportsTextSearch(id: String): Boolean =
        ProviderCatalog.byId(id)?.caps?.textSearch == true
}
