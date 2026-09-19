package com.zcw.chatai.data.provider

/** 工具后端选择结果：空列表/null 表示该工具没有可用后端（不注入对应工具）。 */
data class ToolBackends(
    /**
     * 文本搜索后端（**有序**）：显式设置/会话供应商优先，其余按 preset 顺序补。
     * 运行时按顺序尝试，失败（空结果或报错）再借道下一个。
     */
    val textProviderIds: List<String> = emptyList(),
    val imageProviderId: String? = null,
)

/**
 * 工具后端与主对话供应商**解耦**：主回路可以是任意模型（DeepSeek/GLM/…），
 * 联网搜索/图搜这些"高级工具"借道对应供应商的 API 执行。
 *
 * 规则（纯函数，JVM 可测）：
 * - 文本搜索：显式选择 → 会话供应商（caps 支持）→ 按 preset 顺序补其余已配置且支持的
 *   （**有序候选**，运行时逐个回退，所以显式/会话只是"首选"，失败仍会借道）；
 * - 图搜：固定取已配置且支持的 Qwen（目前唯一实现）；
 * - "已配置" = 出现在 providers 表里且 apiKey 非空；key/URL 的可用性由调用方用 `available()` 再判。
 */
object ToolBackendResolver {

    fun resolve(
        providers: Map<String, ProviderEntry>,
        activeProviderId: String?,
        conversationProviderId: String?,
        preferredSearchProviderId: String?,
    ): ToolBackends = ToolBackends(
        textProviderIds = resolveTextCandidates(
            providers,
            conversationProviderId ?: activeProviderId,
            preferredSearchProviderId,
        ),
        imageProviderId = resolveImage(providers),
    )

    private fun resolveTextCandidates(
        providers: Map<String, ProviderEntry>,
        conversationProviderId: String?,
        preferredSearchProviderId: String?,
    ): List<String> {
        val ordered = LinkedHashSet<String>()
        preferredSearchProviderId
            ?.takeIf { supportsTextSearch(it) && hasKey(providers, it) }
            ?.let { ordered += it }
        conversationProviderId
            ?.takeIf { supportsTextSearch(it) && hasKey(providers, it) }
            ?.let { ordered += it }
        ProviderCatalog.presets
            .filter { it.caps.textSearch && hasKey(providers, it.id) }
            .forEach { ordered += it.id }
        return ordered.toList()
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
