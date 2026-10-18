package com.zcw.chatai.data.provider

import com.zcw.chatai.data.web.WebTools

/** 某类工具相对会话联网按钮的策略。缺省 [FOLLOW]，与旧版本「开了联网就全给」一致。 */
enum class ToolFollow {
    /** 联网按钮开着，并且后端可用时注入。 */
    FOLLOW,

    /** 联网按钮开着也不注入。 */
    OFF,
}

/** 网页抓取走本机还是 DeepSeek 官方 Fetch。缺省 [LOCAL]。 */
enum class FetchBackend {
    LOCAL,
    DEEPSEEK,
}

/** 四类工具的启用策略，加上抓取走哪一边。缺省等于旧行为：全跟随、本机抓取。 */
data class ToolPolicy(
    val search: ToolFollow = ToolFollow.FOLLOW,
    val fetch: ToolFollow = ToolFollow.FOLLOW,
    val imageSearch: ToolFollow = ToolFollow.FOLLOW,
    val imageSimilar: ToolFollow = ToolFollow.FOLLOW,
    val fetchBackend: FetchBackend = FetchBackend.LOCAL,
)

/**
 * 本回合注入哪些联网工具。纯函数。
 *
 * 联网按钮关着时什么都不注入。开着时每类工具还要自己的策略是 [ToolFollow.FOLLOW]，
 * 并且对应后端可用。抓取不依赖搜索或图搜后端：策略为跟随时始终注入
 * （DeepSeek 没配 Key 时运行时退回本机，所以这里仍算可用）。
 *
 * [textAvailable] 含托管搜索：托管 `web_search` 也吃网页搜索这一档，关掉就不挂。
 */
object ToolGates {

    fun enabledNames(
        webSearchOn: Boolean,
        policy: ToolPolicy,
        textAvailable: Boolean,
        imageAvailable: Boolean,
    ): List<String> {
        if (!webSearchOn) return emptyList()
        return buildList {
            if (policy.search == ToolFollow.FOLLOW && textAvailable) add(WebTools.SEARCH)
            if (policy.imageSearch == ToolFollow.FOLLOW && imageAvailable) add(WebTools.SEARCH_IMAGES)
            if (policy.imageSimilar == ToolFollow.FOLLOW && imageAvailable) add(WebTools.FIND_SIMILAR_IMAGES)
            if (policy.fetch == ToolFollow.FOLLOW) add(WebTools.FETCH)
        }
    }

    /** 输入栏的联网按钮是否还能点：至少有一类「跟随」的工具真的能跑。 */
    fun anyAvailable(
        policy: ToolPolicy,
        textAvailable: Boolean,
        imageAvailable: Boolean,
    ): Boolean = enabledNames(
        webSearchOn = true,
        policy = policy,
        textAvailable = textAvailable,
        imageAvailable = imageAvailable,
    ).isNotEmpty()
}
