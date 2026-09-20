package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.wire
import com.zcw.chatai.data.net.ChatRequestImage
import com.zcw.chatai.data.net.ChatRequestMessage
import com.zcw.chatai.data.net.ChatRequestVideo

/**
 * 出站上下文组装（纯函数，JVM 单测覆盖）。
 *
 * - 只保留最近 [MAX_MESSAGES] 条有效消息（system 提示由网络层单独追加）。
 * - 历史附件降级：只有最近 N 条带附件的消息重发附件（规则见 [AttachmentRetention]），
 *   更早的**在出站文本里**替换为 [IMAGE_OMITTED]/[VIDEO_OMITTED] 占位（数据库不动）。
 */
object ContextBuilder {

    const val MAX_MESSAGES = 40

    const val IMAGE_OMITTED = "［图片已省略］"
    const val VIDEO_OMITTED = "［视频已省略］"
    const val IMAGE_MISSING = "［图片不可用］"
    const val VIDEO_MISSING = "［视频不可用］"
    const val DOCUMENT_MISSING = "［文档不可用］"

    /** 工具返回空文本时的占位：保留该行以应答对应的 assistant tool_calls。 */
    const val TOOL_EMPTY = "[工具无返回内容]"

    /**
     * 出站上下文里**模型真正能看到**的消息窗口。
     *
     * 图片编号/来源标注与 `find_similar_images` 的 `image_index` 必须基于同一份窗口
     * （`ToolImageInventory` 的唯一真相源），否则长会话截断后编号会错位。
     */
    fun usableHistory(history: List<Message>): List<Message> = history
        .filter { it.role == Role.USER || it.role == Role.ASSISTANT || it.role == Role.TOOL }
        .filter { it.status != MessageStatus.STREAMING }
        .filter {
            it.content.isNotBlank() || it.attachments.isNotEmpty() ||
                it.toolCalls.isNotEmpty() || it.toolCallId != null
        }
        .takeLast(MAX_MESSAGES)

    fun build(
        history: List<Message>,
        imageTurns: Int,
        imageProvider: (Attachment) -> ChatRequestImage?,
        videoProvider: (Attachment) -> ChatRequestVideo? = { null },
        documentProvider: (Attachment) -> String? = { null },
    ): List<ChatRequestMessage> {
        val usable = usableHistory(history)

        val keepAttachments = AttachmentRetention.keptMessageIds(usable, imageTurns)
        // 图片编号/来源标注的唯一真相源：与 image_index、保留规则共用同一份清单。
        val labels = ToolImageInventory.visibleImages(usable, imageTurns)
            .associate { it.attachment.id to ToolImageInventory.label(it) }

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
                    wire += toWire(message, keepAttachments, labels, imageProvider, videoProvider, documentProvider)
                    for (call in message.toolCalls) {
                        val answer = answers[call.id]?.removeFirstOrNull()
                        wire += if (answer != null) {
                            toWire(answer, keepAttachments, labels, imageProvider, videoProvider, documentProvider)
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

                else -> wire += toWire(message, keepAttachments, labels, imageProvider, videoProvider, documentProvider)
            }
        }
        return wire
    }

    private fun toWire(
        message: Message,
        keepAttachments: Set<String>,
        labels: Map<String, String>,
        imageProvider: (Attachment) -> ChatRequestImage?,
        videoProvider: (Attachment) -> ChatRequestVideo?,
        documentProvider: (Attachment) -> String?,
    ): ChatRequestMessage {
        if (message.role == Role.TOOL) {
            // 空白工具结果也必须保留：它对应的 assistant tool_calls 需要被应答，
            // 否则服务端会因找不到 tool_call_id 而 400（例如进程在 RUNNING 与更新之间被杀）。
            val body = (message.toolResult?.text?.takeIf { it.isNotBlank() } ?: message.content)
                .ifBlank { TOOL_EMPTY }
            // modelNote 只发给模型（工具预算/空结果提示），UI 只渲染 text。
            val note = message.toolResult?.modelNote?.takeIf { it.isNotBlank() }
            return ChatRequestMessage(
                role = message.role.wire,
                content = if (note == null) body else "$body\n\n$note",
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
        val keep = message.id in keepAttachments
        val images = if (keep) {
            message.attachments.filter { it.kind == AttachmentKind.IMAGE }.mapNotNull { attachment ->
                // 逐张带上来源标注（全局编号 + 轮次），避免历史图与本轮图混淆。
                imageProvider(attachment)?.let { wire -> wire.copy(label = labels[attachment.id]) }
            }
        } else {
            emptyList()
        }
        val videos = if (keep) {
            message.attachments.filter { it.kind == AttachmentKind.VIDEO }.mapNotNull(videoProvider)
        } else {
            emptyList()
        }
        // 文档以纯文本出站：有多少发多少，不截断、不看保留轮次（配额在发送前拦）。
        // 图片/视频仍按保留规则走。
        val documents = message.attachments.filter { it.kind == AttachmentKind.DOCUMENT }
        val docText = documentBlocks(documents, documentProvider)
        val hasVideo = message.attachments.any { it.kind == AttachmentKind.VIDEO }
        val hasImage = message.attachments.any { it.kind == AttachmentKind.IMAGE }
        val hasDocument = documents.isNotEmpty()
        // 纯文档消息不受保留影响；图文混排沿用旧规则（已有单测锁定）。
        // sidecar 丢失的文档必须亮牌：不能因为同条消息里有图就静默吞掉。
        val note = when {
            !keep && (hasVideo || hasImage) -> if (hasVideo) VIDEO_OMITTED else IMAGE_OMITTED
            hasDocument && docText.isBlank() -> DOCUMENT_MISSING
            images.isEmpty() && videos.isEmpty() && docText.isBlank() -> when {
                hasVideo -> VIDEO_MISSING
                else -> IMAGE_MISSING
            }
            else -> null
        }
        return ChatRequestMessage(
            role = message.role.wire,
            content = withNote(withNote(message.content, docText.ifBlank { null }), note),
            images = images,
            videos = videos,
        )
    }

    private fun withNote(content: String, note: String?): String = when {
        note == null -> content
        content.isBlank() -> note
        else -> "$content\n\n$note"
    }

    /**
     * 文档块（纯函数，JVM 单测覆盖）：`【文档：report.pdf，共 8 页】<正文>`，
     * 多文档用空行分隔；有多少拼多少，不截断（配额在发送前按会话拦）。
     */
    internal fun documentBlocks(
        documents: List<Attachment>,
        documentProvider: (Attachment) -> String?,
    ): String {
        val blocks = documents.mapNotNull { attachment ->
            val text = documentProvider(attachment)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = attachment.displayName?.takeIf { it.isNotBlank() }
                ?: attachment.relativePath.substringAfterLast('/')
            val meta = attachment.extractedMeta
            buildString {
                append("【文档：").append(name)
                if (!meta.isNullOrBlank()) append("，").append(meta)
                append("】\n").append(text)
            }
        }
        if (blocks.isEmpty()) return ""
        return blocks.joinToString("\n\n")
    }
}
