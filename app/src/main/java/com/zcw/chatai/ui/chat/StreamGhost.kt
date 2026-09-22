package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.StreamingMessage

/**
 * 流式覆盖的「幽灵」：流式正文只活在内存 overlay 里，DB 行是异步落库、异步发射的。
 *
 * 助手行是**先 upsert 成空壳（content=""）再开流**的，`finalize` 写全量内容与
 * `clearStreaming` 之间隔着一次 Room 发射。若 overlay 已撤而 Room 还没吐出定稿内容，
 * 这一帧助手 item 的 content 是空/半截（checkpoint 最多滞后 800ms）→ 渲染塌成 ~0 高 →
 * 整列 maxScroll 骤缩 → 正贴底的 scrollOffset 被钳回用户气泡那里（看起来「滚回用户气泡」）。
 *
 * 幽灵按**内容是否追上**续命：只要流式快照比 DB 现有内容更长，就继续覆盖那一行，
 * 高度纹丝不动，等 Room 发射后再无痕撤掉。行被删（重生成/删除）时立刻作废。
 *
 * 纯逻辑，JVM 单测覆盖。
 */
data class StreamGhost(
    val message: StreamingMessage? = null,
    /** 幽灵出生时的 DB 行数；此后行不在而又换了一版快照 = 行被删了。 */
    private val birthDbSize: Int = -1,
) {

    /** 幽灵的文本长度（正文 + 思考）：DB 追平它才算「定稿内容已发射到位」。 */
    private val textLength: Int
        get() = message?.let { it.content.length + it.reasoning.length } ?: 0

    /**
     * 推进一帧。[dbTextLengths] 是**数据库观察**到的每一行文本长度（正文 + 思考）。
     */
    fun step(
        conversationId: String?,
        activeStream: StreamingMessage?,
        dbTextLengths: Map<String, Int>,
    ): StreamGhost {
        if (activeStream != null) {
            // 总是记住最后一帧流式内容：DB 行可能已在，但 content 还是空壳/半截。
            return StreamGhost(activeStream, dbTextLengths.size)
        }
        val ghost = message ?: return StreamGhost(null)
        if (ghost.conversationId != conversationId) return StreamGhost(null)
        val dbLen = dbTextLengths[ghost.messageId]
        if (dbLen != null) {
            // 行在：内容追上（≥ 流式快照）才撤，否则 finalize 的 Room 发射还没到。
            return if (dbLen >= textLength) StreamGhost(null) else this
        }
        // 行不在：DB 快照完全没变才认为 Room 还没吐出行；变了 = 行被删了。
        if (dbTextLengths.size != birthDbSize) return StreamGhost(null)
        return this
    }

    /** 这一帧应该渲染的流式内容：活跃流优先，其次幽灵。 */
    fun overlay(activeStream: StreamingMessage?): StreamingMessage? = activeStream ?: message
}
