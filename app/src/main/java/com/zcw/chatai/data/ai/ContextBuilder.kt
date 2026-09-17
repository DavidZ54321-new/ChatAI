package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.wire
import com.zcw.chatai.data.net.ChatRequestImage
import com.zcw.chatai.data.net.ChatRequestMessage

/**
 * 出站上下文组装（纯函数，JVM 单测覆盖）。
 *
 * - 只保留最近 [MAX_MESSAGES] 条有效消息（system 提示由网络层单独追加）。
 * - 历史图片降级：只有最近 N 条带图消息重发图片，更早的**在出站文本里**替换为
 *   [IMAGE_OMITTED] 占位（数据库不动），避免每轮都重发全部图片导致 token/体积线性上涨。
 */
object ContextBuilder {

    const val MAX_MESSAGES = 40

    const val IMAGE_OMITTED = "［图片已省略］"
    const val IMAGE_MISSING = "［图片不可用］"

    /** 工具返回空文本时的占位：保留该行以应答对应的 assistant tool_calls。 */
    const val TOOL_EMPTY = "[工具无返回内容]"

    fun build(
        history: List<Message>,
        imageLimit: Int,
        imageProvider: (Attachment) -> ChatRequestImage?,
    ): List<ChatRequestMessage> {
        val usable = history
            .filter { it.role == Role.USER || it.role == Role.ASSISTANT || it.role == Role.TOOL }
            .filter { it.status != MessageStatus.STREAMING }
            .filter {
                it.content.isNotBlank() || it.attachments.isNotEmpty() ||
                    it.toolCalls.isNotEmpty() || it.toolCallId != null
            }
            .takeLast(MAX_MESSAGES)
            // 窗口可能恰好切在 assistant(tool_calls) 与其 TOOL 结果之间；开头孤立的
            // TOOL 行没有前置 tool_calls，发到服务端会 400。TOOL 只会紧跟在自己的
            // assistant 回合之后，因此任何开头的 TOOL 行都是被截断的孤儿。
            .dropWhile { it.role == Role.TOOL }

        val keepImages = messageIdsKeepingImages(usable, imageLimit)

        return usable.map { message ->
            if (message.role == Role.TOOL) {
                // 空白工具结果也必须保留：它对应的 assistant tool_calls 需要被应答，
                // 否则服务端会因找不到 tool_call_id 而 400（例如进程在 RUNNING 与更新之间被杀）。
                val text = (message.toolResult?.text?.takeIf { it.isNotBlank() } ?: message.content)
                    .ifBlank { TOOL_EMPTY }
                ChatRequestMessage(
                    role = message.role.wire,
                    content = text,
                    toolCallId = message.toolCallId,
                )
            } else if (message.attachments.isEmpty()) {
                ChatRequestMessage(
                    role = message.role.wire,
                    content = message.content,
                    toolCalls = message.toolCalls,
                    // 只有带 tool_calls 的回合必须回传思考内容，否则思考模式 400。
                    reasoning = message.reasoningContent?.takeIf {
                        it.isNotBlank() && message.toolCalls.isNotEmpty()
                    },
                )
            } else {
                val keep = message.id in keepImages
                val images = if (keep) message.attachments.mapNotNull(imageProvider) else emptyList()
                val note = when {
                    !keep -> IMAGE_OMITTED
                    images.isEmpty() -> IMAGE_MISSING
                    else -> null
                }
                ChatRequestMessage(
                    role = message.role.wire,
                    content = withNote(message.content, note),
                    images = images,
                )
            }
        }
    }

    private fun messageIdsKeepingImages(messages: List<Message>, imageLimit: Int): Set<String> {
        if (imageLimit == 0) return emptySet()
        val kept = HashSet<String>()
        var count = 0
        for (message in messages.asReversed()) {
            if (message.attachments.isEmpty()) continue
            if (imageLimit < 0 || count < imageLimit) {
                kept += message.id
                count++
            }
        }
        return kept
    }

    private fun withNote(content: String, note: String?): String = when {
        note == null -> content
        content.isBlank() -> note
        else -> "$content\n\n$note"
    }
}
