package com.zcw.chatai.data.ai

import com.zcw.chatai.data.net.ChatStreamEvent

/**
 * 流式增量累加器（纯函数逻辑 + 显式时钟，JVM 单测覆盖）。
 *
 * 流式 chunk 极碎（实测每块 1~2 个字符），所以：
 * - 面向 UI 的状态发布节流 [uiThrottleMs]（默认 80ms ≈ 每秒 12 次重组，Markdown 解析不抖）
 * - 落库 checkpoint 更稀疏（默认 800ms），保证中途被杀也只丢少量内容
 */
class StreamAccumulator(
    private val uiThrottleMs: Long = 80,
    private val checkpointMs: Long = 800,
) {

    private val contentBuilder = StringBuilder()
    private val reasoningBuilder = StringBuilder()

    var finishReason: String? = null
        private set

    var usage: ChatStreamEvent.Usage? = null
        private set

    private var lastPublish = -uiThrottleMs
    private var lastCheckpoint = -checkpointMs

    val content: String get() = contentBuilder.toString()

    val reasoning: String get() = reasoningBuilder.toString()

    data class Update(val publish: Boolean, val checkpoint: Boolean)

    fun accept(event: ChatStreamEvent, now: Long): Update {
        var grew = false
        when (event) {
            is ChatStreamEvent.Delta -> {
                event.content?.takeIf { it.isNotEmpty() }?.let {
                    contentBuilder.append(it)
                    grew = true
                }
                event.reasoning?.takeIf { it.isNotEmpty() }?.let {
                    reasoningBuilder.append(it)
                    grew = true
                }
            }

            is ChatStreamEvent.Usage -> usage = event

            is ChatStreamEvent.Finished -> finishReason = event.reason

            ChatStreamEvent.Completed -> Unit
        }
        // 只有真的长出内容才值得推向 UI；usage/finish 走终值写库，不必额外重组一次。
        val publish = grew && shouldPublish(now)
        val checkpoint = grew && shouldCheckpoint(now)
        return Update(publish = publish, checkpoint = checkpoint)
    }

    private fun shouldPublish(now: Long): Boolean {
        if (now - lastPublish < uiThrottleMs) return false
        lastPublish = now
        return true
    }

    private fun shouldCheckpoint(now: Long): Boolean {
        if (now - lastCheckpoint < checkpointMs) return false
        lastCheckpoint = now
        return true
    }
}
