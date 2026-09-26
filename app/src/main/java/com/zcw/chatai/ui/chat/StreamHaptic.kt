package com.zcw.chatai.ui.chat

/**
 * 流式正文轻震的节拍（纯逻辑，不碰马达）。
 *
 * 只认**同一条流式消息的正文变长**。空壳、思考增量、变短都不算。
 * 换消息的第一帧只记住长度。人一直停在聊天页时，空壳会先被看到，
 * 紧接着的第一段正文才是变长。
 *
 * [armed] 为 false（设置关掉、离开聊天页、退后台）时记下当前长度并解除武装：
 * 重新武装的第一帧只作基线，不把离开期间吐出的字补震一遍。
 *
 * 间隔与 UI 发布节流对齐（80ms）：每个可见吐字批次最多一下。
 */
class StreamHapticClock(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) {
    private var messageId: String? = null
    private var contentLength: Int = 0
    private var primed: Boolean = false
    private var lastTickAtMs: Long? = null

    /**
     * @param messageId 当前会话正在流式的消息；没有流式时传 null（清状态）。
     * @param armed 这一帧允不允许震。false 时仍跟踪正文，但下一帧武装后先记基线。
     * @return 这一帧该震一下。
     */
    fun onFrame(
        messageId: String?,
        content: String,
        nowMs: Long,
        armed: Boolean = true,
    ): Boolean {
        if (messageId == null) {
            reset()
            return false
        }
        val length = content.length
        if (!armed) {
            this.messageId = messageId
            contentLength = length
            primed = false
            return false
        }
        if (!primed || messageId != this.messageId) {
            this.messageId = messageId
            contentLength = length
            primed = true
            return false
        }
        val grew = length > contentLength
        contentLength = length
        if (!grew) return false
        val previous = lastTickAtMs
        if (previous != null && nowMs - previous < minIntervalMs) return false
        lastTickAtMs = nowMs
        return true
    }

    private fun reset() {
        messageId = null
        contentLength = 0
        primed = false
        lastTickAtMs = null
    }

    companion object {
        const val MIN_INTERVAL_MS = 80L
    }
}
