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

        val keepImages = messageIdsKeepingImages(usable, imageLimit)

        // 服务端硬校验：assistant(tool_calls) 后面必须**连续**跟着每个 call 的应答。
        // DB 行序可能被打乱（进程死在工具执行中，事后补的占位应答落在用户消息之后），
        // 这里按 call id 把应答提回它的 assistant 后面；无主的 TOOL 行直接丢弃。
        val answers = HashMap<String, ArrayDeque<Message>>()
        for (message in usable) {
            val callId = message.toolCallId
            if (message.role == Role.TOOL && callId != null) {
                answers.getOrPut(callId) { ArrayDeque() }.addLast(message)
            }
        }

        val wire = ArrayList<ChatRequestMessage>(usable.size)
        for (message in usable) {
            when (message.role) {
                // TOOL 行只在对应 assistant 的分支里按 tool_calls 顺序取出，这里跳过。
                Role.TOOL -> Unit

                Role.ASSISTANT -> {
                    wire += toWire(message, keepImages, imageProvider)
                    for (call in message.toolCalls) {
                        val answer = answers[call.id]?.removeFirstOrNull()
                        wire += if (answer != null) {
                            toWire(answer, keepImages, imageProvider)
                        } else {
                            // assistant 已落 tool_calls 但应答行缺失（崩溃窗口）：
                            // 合成占位应答，绝不让请求非法。
                            ChatRequestMessage(
                                role = Role.TOOL.wire,
                                content = TOOL_EMPTY,
                                toolCallId = call.id,
                            )
                        }
                    }
                }

                else -> wire += toWire(message, keepImages, imageProvider)
            }
        }
        return wire
    }

    private fun toWire(
        message: Message,
        keepImages: Set<String>,
        imageProvider: (Attachment) -> ChatRequestImage?,
    ): ChatRequestMessage {
        if (message.role == Role.TOOL) {
            // 空白工具结果也必须保留：它对应的 assistant tool_calls 需要被应答，
            // 否则服务端会因找不到 tool_call_id 而 400（例如进程在 RUNNING 与更新之间被杀）。
            val text = (message.toolResult?.text?.takeIf { it.isNotBlank() } ?: message.content)
                .ifBlank { TOOL_EMPTY }
            return ChatRequestMessage(
                role = message.role.wire,
                content = text,
                toolCallId = message.toolCallId,
            )
        }
        if (message.attachments.isEmpty()) {
            return ChatRequestMessage(
                role = message.role.wire,
                content = message.content,
                toolCalls = message.toolCalls,
                // 只有带 tool_calls 的回合必须回传思考内容，否则思考模式 400。
                reasoning = message.reasoningContent?.takeIf {
                    it.isNotBlank() && message.toolCalls.isNotEmpty()
                },
            )
        }
        val keep = message.id in keepImages
        val images = if (keep) message.attachments.mapNotNull(imageProvider) else emptyList()
        val note = when {
            !keep -> IMAGE_OMITTED
            images.isEmpty() -> IMAGE_MISSING
            else -> null
        }
        return ChatRequestMessage(
            role = message.role.wire,
            content = withNote(message.content, note),
            images = images,
        )
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
