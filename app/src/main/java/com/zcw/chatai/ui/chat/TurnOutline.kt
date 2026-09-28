package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolResult
import java.time.Instant
import java.time.ZoneId

/**
 * 链路页的一轮：一条用户消息，加上紧跟其后的整段助手回合（思考、工具、最终回复）。
 * 纯数据，JVM 单测覆盖。锚点 [anchorKey] 是主列表里要对齐的那一组的 key。
 */
data class TurnStop(
    val anchorKey: String,
    /** 助手组的 key；这一轮还没有回复时为 null。 */
    val assistantKey: String?,
    /** 锚点组在 [MessageGroups] 里的下标。 */
    val startGroupIndex: Int,
    val sentAt: Long,
    /** 已经折成单行并截断，直接给 Text。 */
    val userText: String,
    val assistantText: String,
    /** 没有附件时为 null。 */
    val attachmentsLabel: String?,
    val reasoning: List<TurnReasoning>,
    val tools: List<TurnTool>,
)

data class TurnReasoning(
    val messageId: String,
    val text: String,
    val reasoningMs: Long?,
)

data class TurnTool(
    val messageId: String,
    val result: ToolResult,
)

object TurnOutline {

    /** 交给 Text 之前的字符上限。3～4 行中文用不满这些字，长文不会整篇进布局。 */
    const val EXCERPT_CHARS = 240

    const val USER_LINES = 3

    const val ASSISTANT_LINES = 4

    fun of(messages: List<ChatMessageItem>): List<TurnStop> {
        val groups = MessageGroups.of(messages)
        if (groups.isEmpty()) return emptyList()
        val turns = mutableListOf<TurnStop>()
        var index = 0
        while (index < groups.size) {
            val group = groups[index]
            if (group is MessageGroup.User) {
                val next = groups.getOrNull(index + 1)
                val assistant = next as? MessageGroup.Assistant
                turns += stop(
                    user = group.items.first(),
                    assistant = assistant,
                    anchorKey = group.key,
                    startGroupIndex = index,
                )
                index += if (assistant != null) 2 else 1
            } else {
                turns += stop(
                    user = null,
                    assistant = group as MessageGroup.Assistant,
                    anchorKey = group.key,
                    startGroupIndex = index,
                )
                index += 1
            }
        }
        return turns
    }

    /**
     * 主列表 LazyColumn 下标。有分支横幅时第 0 项是横幅，组从 1 开始。
     * 找不到锚点返回 null。
     */
    fun lazyIndexOf(
        groupKey: String,
        groups: List<MessageGroup>,
        hasBranchHeader: Boolean,
    ): Int? {
        val index = groups.indexOfFirst { it.key == groupKey }
        if (index < 0) return null
        return index + if (hasBranchHeader) 1 else 0
    }

    /**
     * 主列表当前第一项落在哪一张卡片上。
     * 横幅归到第一轮，免责声明（组下标已经越过去）归到最后一轮。
     */
    fun turnIndexAtLazyIndex(
        firstVisible: Int,
        hasBranchHeader: Boolean,
        turns: List<TurnStop>,
    ): Int {
        if (turns.isEmpty()) return 0
        val groupIndex = firstVisible - if (hasBranchHeader) 1 else 0
        var found = 0
        for (i in turns.indices) {
            if (turns[i].startGroupIndex <= groupIndex) found = i else break
        }
        return found
    }

    /**
     * 正在生成、而且就是列表最后一轮。链路卡片用它显示「生成中」。
     * 点卡片本身仍回到用户气泡；进会话时的贴底不走这里。
     */
    fun sticksToBottom(
        stop: TurnStop,
        groups: List<MessageGroup>,
        isTurnActive: Boolean,
    ): Boolean {
        if (!isTurnActive) return false
        val lastKey = groups.lastOrNull()?.key ?: return false
        return lastKey == stop.anchorKey || lastKey == stop.assistantKey
    }

    /**
     * 折叠空白后截断。没超出 [maxChars] 不加省略号。空白返回空串。
     * 手写循环，不用 Regex（Android 的 ICU 引擎不认 Java 内联标志）。
     */
    fun excerpt(text: String, maxChars: Int = EXCERPT_CHARS): String {
        if (maxChars <= 0) return ""
        val collapsed = StringBuilder()
        var pendingSpace = false
        var truncated = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (collapsed.isNotEmpty()) pendingSpace = true
                continue
            }
            if (pendingSpace) {
                if (collapsed.length + 1 >= maxChars) {
                    truncated = true
                    break
                }
                collapsed.append(' ')
                pendingSpace = false
            }
            if (collapsed.length >= maxChars) {
                truncated = true
                break
            }
            collapsed.append(ch)
        }
        if (collapsed.isEmpty()) return ""
        return if (truncated) collapsed.toString() + "…" else collapsed.toString()
    }

    /**
     * 同一本地日只显示时刻，跨日带上月日。
     * 小时补零，月和日不补零：`14:05`、`9月28日 09:07`。
     */
    fun timeLabel(sentAtMillis: Long, nowMillis: Long, zone: ZoneId): String {
        val sent = Instant.ofEpochMilli(sentAtMillis).atZone(zone)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val time = "%02d:%02d".format(sent.hour, sent.minute)
        if (sent.toLocalDate() == now.toLocalDate()) return time
        return "${sent.monthValue}月${sent.dayOfMonth}日 $time"
    }

    /** 链路页不解码缩略图，只报个数。没有附件返回 null。 */
    fun attachmentLabel(kinds: List<AttachmentKind>): String? {
        if (kinds.isEmpty()) return null
        var images = 0
        var videos = 0
        var documents = 0
        var audios = 0
        for (kind in kinds) {
            when (kind) {
                AttachmentKind.IMAGE -> images++
                AttachmentKind.VIDEO -> videos++
                AttachmentKind.DOCUMENT -> documents++
                AttachmentKind.AUDIO -> audios++
            }
        }
        val parts = mutableListOf<String>()
        if (images > 0) parts += "$images 张图片"
        if (videos > 0) parts += "$videos 段视频"
        if (documents > 0) parts += "$documents 个文档"
        if (audios > 0) parts += "$audios 段音频"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun stop(
        user: ChatMessageItem?,
        assistant: MessageGroup.Assistant?,
        anchorKey: String,
        startGroupIndex: Int,
    ): TurnStop {
        val items = assistant?.items.orEmpty()
        val reply = items.lastOrNull { it.role == Role.ASSISTANT }?.content.orEmpty()
        return TurnStop(
            anchorKey = anchorKey,
            assistantKey = assistant?.key,
            startGroupIndex = startGroupIndex,
            sentAt = user?.createdAt ?: items.firstOrNull()?.createdAt ?: 0L,
            userText = excerpt(user?.content.orEmpty()),
            assistantText = excerpt(reply),
            attachmentsLabel = attachmentLabel(user?.images?.map { it.kind }.orEmpty()),
            reasoning = items.mapNotNull { item ->
                val text = item.reasoning?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TurnReasoning(messageId = item.id, text = text, reasoningMs = item.reasoningMs)
            },
            tools = items.mapNotNull { item ->
                val result = item.toolResult ?: return@mapNotNull null
                TurnTool(messageId = item.id, result = result)
            },
        )
    }
}
