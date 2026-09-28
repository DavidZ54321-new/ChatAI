package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.Message

/**
 * 合流里会话 id 和消息列表各走一拍：id 已经换成下一条时，消息可能还是上一条的。
 * 对不上就当空列表，定位门继续等，不会拿上一份行号去滚新会话。
 */
fun messagesFor(conversationId: String?, messages: List<Message>): List<Message> {
    if (conversationId.isNullOrBlank()) return emptyList()
    if (messages.any { it.conversationId != conversationId }) return emptyList()
    return messages
}
