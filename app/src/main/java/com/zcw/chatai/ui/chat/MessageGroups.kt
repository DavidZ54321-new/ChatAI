package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.Role

/**
 * 消息列表的视觉分组：一个 agent 回合（连续的 ASSISTANT/TOOL 行）折叠成一块，
 * 用户消息各自成组。纯数据，JVM 单测覆盖。
 */
sealed interface MessageGroup {
    val key: String
    val items: List<ChatMessageItem>

    data class User(override val items: List<ChatMessageItem>) : MessageGroup {
        override val key: String get() = items.first().id
    }

    data class Assistant(override val items: List<ChatMessageItem>) : MessageGroup {
        override val key: String get() = items.first().id
    }
}

object MessageGroups {

    /**
     * 存储层一个 agent 回合本来是多行（每步一条 ASSISTANT + 若干 TOOL），这是工具协议
     * 要求的；展示上要把它们折成一块，操作按钮才只出现在整段回答的末尾。
     * key 取组内首行 id：流式追加新步骤时原组不重建。
     */
    fun of(messages: List<ChatMessageItem>): List<MessageGroup> {
        val groups = mutableListOf<MessageGroup>()
        val run = mutableListOf<ChatMessageItem>()
        fun flushRun() {
            if (run.isNotEmpty()) {
                groups += MessageGroup.Assistant(run.toList())
                run.clear()
            }
        }

        for (message in messages) {
            if (message.role == Role.USER) {
                flushRun()
                groups += MessageGroup.User(listOf(message))
            } else {
                run += message
            }
        }
        flushRun()
        return groups
    }
}
