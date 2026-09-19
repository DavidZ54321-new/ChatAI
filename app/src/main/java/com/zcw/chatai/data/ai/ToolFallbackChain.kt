package com.zcw.chatai.data.ai

import kotlinx.coroutines.CancellationException

/**
 * 工具后端的「逐个尝试」执行器（纯逻辑 + 可注入 suspend lambda，JVM 单测覆盖）。
 *
 * 两种用法共用同一条语义：
 * - 图搜：候选是**模型链**（`qwen3.8-27b` → `qwen3.8-max`），空结果或报错都换下一个；
 * - 文本搜索：候选是**后端列表**（会话供应商优先，其余按 preset 顺序补），失败再借道。
 *
 * 规则：
 * - 命中第一个「非空」结果即返回（[isEmpty] 由调用方给，例如图搜看 `images.isEmpty()`）；
 * - 某个候选抛异常 → 记下并试下一个（`CancellationException` 必须原样向上抛，不能吞）；
 * - 全部为空 → 返回最后一次成功结果（让上层照常给「无结果」提示，而不是报错）；
 * - 全部报错 → 抛最后一个异常（保持可读失败）；
 * - 只有一个候选 → 只调用一次，行为与直接用该后端完全一致。
 */
object ToolFallbackChain {

    suspend fun <C, T> firstUsable(
        candidates: List<C>,
        isEmpty: (T) -> Boolean,
        call: suspend (C) -> T,
    ): T {
        require(candidates.isNotEmpty()) { "candidates must not be empty" }
        var lastResult: T? = null
        var lastError: Throwable? = null
        for (candidate in candidates) {
            val result = try {
                call(candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                lastError = t
                continue
            }
            if (!isEmpty(result)) return result
            lastResult = result
        }
        lastResult?.let { return it }
        throw lastError ?: IllegalStateException("all candidates produced no result")
    }
}
