package com.zcw.chatai.data.ai

/** 一次出站请求在历史之外的形状。流式函数只执行它，不自己决定音频策略。 */
data class OutboundPlan(
    val omitMedia: Boolean = false,
    val trailingUserNote: String? = null,
)
