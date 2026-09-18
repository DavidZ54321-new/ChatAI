package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Message

/**
 * 历史附件保留规则（纯函数，JVM 可测）：只有最近 N 条带附件的消息重发附件，
 * 更早的在出站文本里替换为占位符，避免每轮都重发全部图片/视频导致 token 与体积线性上涨。
 *
 * ContextBuilder 与视频上传预检**共用**这一条规则——上传只针对真正会出站的视频，
 * 不会为已被省略的历史视频白白上传。
 */
object AttachmentRetention {

    fun keptMessageIds(messages: List<Message>, limit: Int): Set<String> {
        if (limit == 0) return emptySet()
        val kept = HashSet<String>()
        var count = 0
        for (message in messages.asReversed()) {
            if (message.attachments.isEmpty()) continue
            if (limit < 0 || count < limit) {
                kept += message.id
                count++
            }
        }
        return kept
    }
}
