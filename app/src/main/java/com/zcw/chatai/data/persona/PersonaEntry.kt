package com.zcw.chatai.data.persona

import com.zcw.chatai.data.prefs.ReasoningEffort

/**
 * 一份角色配置（DataStore `personas_json` 的持久化单元）。
 *
 * 角色 = 提示词 + 生成参数：切换角色即切换这组出站参数。
 * 图片精度 / 历史图片轮次 / 用量统计是应用行为，不属于角色，继续走全局设置。
 */
data class PersonaEntry(
    val name: String,
    val systemPrompt: String = "",
    val temperature: Double? = null,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.FOLLOW_DEFAULT,
    val maxTokens: Int? = null,
    val extraParams: String = "",
)
