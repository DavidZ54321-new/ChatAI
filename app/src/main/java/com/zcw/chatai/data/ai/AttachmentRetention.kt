package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Message

/**
 * 历史附件保留规则（纯函数，JVM 可测）：单位是**轮次**，不是消息条数。
 *
 * - 最后一条带附件的消息**永远保留**：它就是本轮内容（附件只挂在 user 消息上），
 *   无论怎么配置都不该丢，否则用户刚发的图模型根本看不到。
 * - [retainTurns] 是「再往前保留几轮」：N = 当前轮 + 最近 N 轮，0 = 只发当前轮，-1 = 全部。
 *
 * ContextBuilder 与视频上传预检**共用**这一条规则——上传只针对真正会出站的视频，
 * 不会为已被省略的历史视频白白上传。
 */
object AttachmentRetention {

    fun keptMessageIds(messages: List<Message>, retainTurns: Int): Set<String> {
        val attachmentMessages = messages.filter { it.attachments.isNotEmpty() }
        if (attachmentMessages.isEmpty()) return emptySet()
        if (retainTurns < 0) return attachmentMessages.mapTo(HashSet()) { it.id }
        val limit = retainTurns + 1
        val kept = HashSet<String>()
        for (message in attachmentMessages.asReversed()) {
            if (kept.size >= limit) break
            kept += message.id
        }
        return kept
    }
}
