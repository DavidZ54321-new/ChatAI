package com.zcw.chatai.data.ai

import com.zcw.chatai.data.net.ChatStreamEvent

/**
 * 流式增量累加器（纯函数逻辑 + 显式时钟，JVM 单测覆盖）。
 *
 * 流式 chunk 极碎（实测每块 1~2 个字符），所以：
 * - 面向 UI 的状态发布节流 [uiThrottleMs]（默认 80ms ≈ 每秒 12 次重组，Markdown 解析不抖）
 * - 落库 checkpoint 更稀疏（默认 800ms），保证中途被杀也只丢少量内容
 *
 * [now] 由调用方传入，应当是**单调**时钟（`SystemClock.elapsedRealtime`）：它同时用于节流，
 * 也用于量 [reasoningMs]，墙钟被改会让时长算出负数或离谱值。
 */
class StreamAccumulator(
    private val uiThrottleMs: Long = 80,
    private val checkpointMs: Long = 800,
    /** 回合开始（请求发出）的时刻。不传则退化成「第一个增量到达时」。 */
    private val startedAt: Long? = null,
) {

    private val contentBuilder = StringBuilder()
    private val reasoningBuilder = StringBuilder()

    var finishReason: String? = null
        private set

    var usage: ChatStreamEvent.Usage? = null
        private set

    /**
     * 思考耗时：回合开始 → **最后一个** reasoning 增量。
     *
     * 取「最后一个思考增量」而不是「第一个正文增量」：输出顺序不保证（有的模型先给正文
     * 再补思考），那样会算出 0；取最后一段也天然在思考结束后定住，不会跟着正文一直涨。
     */
    var reasoningMs: Long? = null
        private set

    private var firstEventAt: Long? = null
    private var lastReasoningAt: Long? = null

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
                    lastReasoningAt = now
                    grew = true
                }
            }

            is ChatStreamEvent.Usage -> usage = event

            is ChatStreamEvent.Finished -> finishReason = event.reason

            is ChatStreamEvent.ToolCallDelta -> Unit

            ChatStreamEvent.Completed -> Unit
        }
        if (grew) {
            if (firstEventAt == null) firstEventAt = now
            updateReasoningMs()
        }
        // 只有真的长出内容才值得推向 UI；usage/finish 走终值写库，不必额外重组一次。
        val publish = grew && shouldPublish(now)
        val checkpoint = grew && shouldCheckpoint(now)
        return Update(publish = publish, checkpoint = checkpoint)
    }

    private fun updateReasoningMs() {
        val from = startedAt ?: firstEventAt ?: return
        val to = lastReasoningAt ?: return
        reasoningMs = (to - from).coerceAtLeast(0)
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
