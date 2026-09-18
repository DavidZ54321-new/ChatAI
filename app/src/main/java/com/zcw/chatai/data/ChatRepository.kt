package com.zcw.chatai.data

import com.zcw.chatai.data.ai.AgentLoop
import com.zcw.chatai.data.ai.ContextBuilder
import com.zcw.chatai.data.ai.StreamAccumulator
import com.zcw.chatai.data.ai.ToolCallAccumulator
import com.zcw.chatai.data.ai.ToolTurnGrouping
import com.zcw.chatai.data.db.AppDatabase
import com.zcw.chatai.data.db.AttachmentCodec
import com.zcw.chatai.data.db.ConversationEntity
import com.zcw.chatai.data.db.MessageEntity
import com.zcw.chatai.data.db.ToolCallCodec
import com.zcw.chatai.data.db.toModel
import com.zcw.chatai.data.media.AttachmentLimits
import com.zcw.chatai.data.media.AttachmentStore
import com.zcw.chatai.data.media.ImageCodec
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.ConversationTitle
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolStatus
import com.zcw.chatai.data.net.ApiErrorMapper
import com.zcw.chatai.data.net.ChatApi
import com.zcw.chatai.data.net.ChatApiException
import com.zcw.chatai.data.net.ChatStreamEvent
import com.zcw.chatai.data.prefs.SettingsRepository
import com.zcw.chatai.data.web.HttpWebFetcher
import com.zcw.chatai.data.web.WebFetcher
import com.zcw.chatai.data.web.WebSearchProvider
import com.zcw.chatai.data.web.WebTools
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 正在流式生成的消息（内容刻意不进数据库，等 checkpoint / 结束才写）。 */
private const val TAG = "ChatRepository"

/** 工具执行被中断（崩溃窗口兜底）时占位，保持 assistant 的 tool_calls 有应答。 */
private const val INTERRUPTED_TOOL_TEXT = "[工具执行被中断，无返回内容]"

/** 用户主动停止时工具行的落定文本。 */
private const val TOOL_CANCELLED_TEXT = "[已停止]"

/** 与 OpenAI 兼容面一致的中间态字面量。 */
private const val FINISH_TOOL_CALLS = "tool_calls"

data class StreamingMessage(
    val conversationId: String,
    val messageId: String,
    val content: String,
    val reasoning: String,
    /** 思考耗时（毫秒）；null = 还没有思考增量。和落库的值同一个来源。 */
    val reasoningMs: Long? = null,
)

/** 工具活动带归属会话：UI 只显示当前会话的活动，避免切会话后串台。 */
data class AgentActivity(val conversationId: String, val text: String)

sealed interface SendResult {
    data object Started : SendResult

    data class Rejected(val reason: String) : SendResult
}

/**
 * 唯一业务入口：落库 → 组上下文 → 流式 → 节流发布 / checkpoint → 终值写回。
 *
 * 流式任务跑在仓库自己的 scope 上，所以「停止生成」「切会话」不受调用方生命周期影响；
 * 生成结果一律写进消息行（status/errorMessage），UI 只观察 Room + [streaming]。
 */
class ChatRepository(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val api: ChatApi,
    private val attachmentStore: AttachmentStore,
    private val searchProvider: WebSearchProvider? = null,
    private val webFetcher: WebFetcher = HttpWebFetcher(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** 量时长用的**单调**时钟：墙钟被改 / NTP 跳一下会让时长算出负数或离谱值。 */
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    private val _streaming = MutableStateFlow<StreamingMessage?>(null)

    val streaming: StateFlow<StreamingMessage?> = _streaming.asStateFlow()

    /** 后台失败（例如界面已退出）通过这里抛给 UI，而不是静默丢掉。 */
    private val _errors = MutableStateFlow<String?>(null)

    val errors: StateFlow<String?> = _errors.asStateFlow()

    fun consumeError() {
        _errors.value = null
    }

    /**
     * 整回合的任务句柄：覆盖「流式 + 工具执行 + 再流式」全过程。
     * 不能只看 [streaming] —— 工具执行期间它会被清空，那样并发发送会插进第二个回合。
     */
    private var turnJob: Job? = null

    /** 正在跑回合的会话；用于「清空/删除会话时顺手停掉」。
     *  `@Volatile`：写入在 IO 线程，读取可能来自主线程。 */
    @Volatile
    private var activeConversationId: String? = null

    /** 当前工具活动（如「正在联网搜索：xxx」）。独立于 [streaming]：assistant 步结束后 streaming 会被清空。 */
    private val _activity = MutableStateFlow<AgentActivity?>(null)

    val activity: StateFlow<AgentActivity?> = _activity.asStateFlow()

    /** 统一收口后台失败：日志留堆栈，界面只给一句人话。 */
    private fun reportError(t: Throwable, fallback: String) {
        Log.e(TAG, "后台操作失败", t)
        _errors.value = t.message?.takeIf { it.isNotBlank() } ?: fallback
    }

    // ---------- 观察 ----------

    fun observeConversations(): Flow<List<Conversation>> =
        db.conversationDao().observeAll().map { list -> list.map { it.toModel() } }

    fun observeConversation(id: String): Flow<Conversation?> =
        db.conversationDao().observeById(id).map { it?.toModel() }

    fun observeMessages(conversationId: String): Flow<List<Message>> =
        db.messageDao().observeByConversation(conversationId).map { list -> list.map { it.toModel() } }

    // ---------- 会话 ----------

    suspend fun createConversation(model: String? = null): String {
        val settings = settingsRepository.settings.first()
        val id = newId()
        val timestamp = nowMs()
        db.conversationDao().upsert(
            ConversationEntity(
                id = id,
                title = ConversationTitle.FALLBACK,
                model = model?.takeIf { it.isNotBlank() } ?: settings.model,
                systemPrompt = null,
                createdAt = timestamp,
                updatedAt = timestamp,
                lastMessagePreview = "",
                messageCount = 0,
                isPinned = false,
            ),
        )
        return id
    }

    suspend fun renameConversation(id: String, title: String) {
        val clean = title.trim().ifEmpty { ConversationTitle.FALLBACK }
        db.conversationDao().rename(id, clean, nowMs())
    }

    suspend fun setConversationModel(id: String, model: String) {
        if (model.isBlank()) return
        db.conversationDao().updateModel(id, model, nowMs())
    }

    /** 删除会话：文件与行一起清掉。跑在仓库自己的 scope 上，界面退出也不会半途而废。 */
    fun deleteConversation(id: String) {
        stopIfStreaming(id)
        scope.launch {
            runCatching {
                val messages = db.messageDao().getByConversation(id)
                attachmentStore.delete(messages.flatMap { it.toModel().attachments })
                attachmentStore.deleteConversation(id)
                db.conversationDao().delete(id)
            }.onFailure { reportError(it, "删除会话失败") }
        }
    }

    fun clearConversation(id: String) {
        stopIfStreaming(id)
        scope.launch {
            runCatching {
                val messages = db.messageDao().getByConversation(id)
                attachmentStore.delete(messages.flatMap { it.toModel().attachments })
                attachmentStore.deleteConversation(id)
                db.messageDao().deleteByConversation(id)
                refreshSummary(id)
            }.onFailure { reportError(it, "清空会话失败") }
        }
    }

    fun deleteMessage(messageId: String) {
        if (_streaming.value?.messageId == messageId) stop()
        scope.launch {
            runCatching {
                val entity = db.messageDao().getById(messageId) ?: return@runCatching
                val message = entity.toModel()
                val all = db.messageDao().getByConversation(message.conversationId)
                // 分组规则见 ToolTurnGrouping：删一半会留下孤立的 tool_calls / TOOL 行 → 下次 400。
                val victims = ToolTurnGrouping.deletionSetFor(all.map { it.toNode() }, message.seq)
                    .mapNotNull { seq -> all.firstOrNull { it.seq == seq } }
                attachmentStore.delete(victims.flatMap { it.toModel().attachments })
                db.messageDao().deleteByIds(victims.map { it.id })
                refreshSummary(message.conversationId)
            }.onFailure { reportError(it, "删除消息失败") }
        }
    }

    // ---------- 发送 / 重新生成 ----------

    /**
     * 发送。校验是同步的（立刻能告诉界面「为什么不给发」），真正的落库与流式
     * 跑在仓库自己的 scope 上 —— 用户点了发送之后即使界面退出，消息与回答也不会丢。
     */
    fun send(
        conversationId: String,
        text: String,
        attachments: List<Attachment> = emptyList(),
    ): SendResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return SendResult.Rejected("请输入内容")
        AttachmentLimits.validate(attachments)?.let { return SendResult.Rejected(it) }
        if (turnJob?.isActive == true) return SendResult.Rejected("正在生成中，请先停止")

        turnJob = scope.launch {
            try {
                startTurn(conversationId, trimmed, attachments)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                reportError(t, "发送失败")
            }
        }
        return SendResult.Started
    }

    /**
     * 重新生成：删掉这条回答及其之后的所有消息，用剩余上下文重跑。
     *
     * Agent 回合是「assistant(tool_calls) + TOOL 结果」的整体。这里必须以**整组**为单位截断：
     * - 落在目标之前的孤立 assistant（工具结果在目标之后）也要带走；
     * - 否则会留下「搜索没结果 / 抓取失败」的旧步骤，模型继承后继续失败。
     */
    fun regenerate(messageId: String): SendResult {
        if (turnJob?.isActive == true) stop()
        turnJob = scope.launch {
            try {
                val entity = db.messageDao().getById(messageId)
                    ?: return@launch
                val message = entity.toModel()
                // 从 TOOL 行触发时，按它所属的 assistant 回合整组截断。
                val targetSeq = resolveTurnStart(message) ?: run {
                    _errors.value = "只能重新生成回答"
                    return@launch
                }
                val all = db.messageDao().getByConversation(message.conversationId)
                // 除 [targetSeq] 起的整段外，还要带走「应答落在该点之后」的孤儿 assistant（见 ToolTurnGrouping）。
                val orphans = ToolTurnGrouping.orphanAssistantSeqsBefore(all.map { it.toNode() }, targetSeq)
                val victims = all.filter { it.seq >= targetSeq || it.seq in orphans }
                attachmentStore.delete(victims.flatMap { it.toModel().attachments })
                db.messageDao().deleteByIds(
                    all.filter { it.seq in orphans }.map { it.id },
                )
                db.messageDao().deleteFrom(message.conversationId, targetSeq)
                refreshSummary(message.conversationId)
                startAssistant(message.conversationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                reportError(t, "重新生成失败")
            }
        }
        return SendResult.Started
    }

    /** 找出这条消息所属 Agent 回合的起点 seq：assistant 是自己的 seq，TOOL 走配对回它的发起回合。 */
    private suspend fun resolveTurnStart(message: Message): Long? {
        if (message.role == Role.ASSISTANT) return message.seq
        if (message.role != Role.TOOL) return null
        val callId = message.toolCallId ?: return message.seq
        // 与 ToolTurnGrouping 用同一条解码路径：不能对原始 JSON 做子串匹配，
        // 否则 id 恰好出现在别的调用 arguments 里会选错发起回合。
        val owner = db.messageDao().getByConversation(message.conversationId)
            .lastOrNull { entity ->
                entity.role == Role.ASSISTANT.name &&
                    entity.seq <= message.seq &&
                    ToolCallCodec.decodeCalls(entity.toolCalls).any { it.id == callId }
            }
        return owner?.seq ?: message.seq
    }

    /** Room 实体 → 分组规则需要的纯数据（`ToolTurnGrouping.Node`）。 */
    private fun MessageEntity.toNode(): ToolTurnGrouping.Node = ToolTurnGrouping.Node(
        seq = seq,
        role = Role.entries.firstOrNull { it.name == role } ?: Role.SYSTEM,
        toolCalls = ToolCallCodec.decodeCalls(toolCalls),
        toolCallId = toolCallId,
    )

    fun stop() {
        turnJob?.cancel()
        turnJob = null
    }

    private fun stopIfStreaming(conversationId: String) {
        if (activeConversationId == conversationId) stop()
    }

    // ---------- 附件清理 ----------

    /** 冷启动调用：删掉不再被任何消息引用、且超过 6 小时的孤儿文件。 */
    suspend fun sweepOrphanAttachments() {
        val referenced = db.messageDao().getAllAttachmentJson()
            .flatMap { AttachmentCodec.decode(it) }
            .map { it.relativePath }
            .toSet()
        attachmentStore.sweepOrphans(referenced + referenced.map { ImageCodec.thumbRelativePath(it) })
    }

    // ---------- 内部 ----------

    /** 落用户消息 → 起标题/摘要 → 建助手占位并开流。 */
    private suspend fun startTurn(
        conversationId: String,
        text: String,
        attachments: List<Attachment>,
    ) {
        val conversation = db.conversationDao().getById(conversationId)
        if (conversation == null) {
            _errors.value = "会话不存在"
            return
        }
        val timestamp = nowMs()
        db.messageDao().upsert(
            MessageEntity(
                id = newId(),
                conversationId = conversationId,
                role = Role.USER.name,
                content = text,
                status = MessageStatus.COMPLETE.name,
                errorMessage = null,
                reasoningContent = null,
                seq = db.messageDao().nextSeq(conversationId),
                model = conversation.model,
                promptTokens = null,
                completionTokens = null,
                attachments = AttachmentCodec.encode(attachments),
                createdAt = timestamp,
                updatedAt = timestamp,
            ),
        )
        if (conversation.messageCount == 0 && conversation.title == ConversationTitle.FALLBACK) {
            val titleSource = text.ifBlank { "图片" }
            db.conversationDao().rename(conversationId, ConversationTitle.fromFirstMessage(titleSource), timestamp)
        }
        refreshSummary(conversationId)
        startAssistant(conversationId)
    }

    private suspend fun startAssistant(conversationId: String) {
        val config = resolveConfig(conversationId)
        val toolsUsable = config.webSearchEnabled &&
            searchProvider?.available(config.baseUrl, config.apiKey) == true
        activeConversationId = conversationId
        try {
            runAgentTurn(conversationId, config.copy(webSearchEnabled = toolsUsable))
        } finally {
            // 只在仍属于本会话时清空：被 stop + 新回合接管的竞态下不能误清新回合的标记。
            if (activeConversationId == conversationId) activeConversationId = null
        }
    }

    private suspend fun resolveConfig(conversationId: String): ChatConfig {
        val base = settingsRepository.chatConfig().first()
        val conversation = db.conversationDao().getById(conversationId)
        return base.copy(
            model = conversation?.model?.takeIf { it.isNotBlank() } ?: base.model,
            systemPrompt = conversation?.systemPrompt?.takeIf { it.isNotBlank() } ?: base.systemPrompt,
            webSearchEnabled = conversation?.webSearchEnabled == true,
        )
    }

    /** 会话级联网开关：写回 `conversations.web_search_enabled`。 */
    suspend fun setConversationWebSearch(conversationId: String, enabled: Boolean) {
        db.conversationDao().updateWebSearchEnabled(conversationId, enabled)
    }

    private data class TurnOutcome(
        val finishReason: String?,
        val toolCalls: List<ToolCall>,
        val failed: Boolean,
    )

    /**
     * 有界 Agent 循环：每一步一条 assistant 消息；模型要工具就串行执行、落 TOOL 行、再流式。
     * 预算耗尽时先执行完最后一批工具（保持历史合法），再不带工具强制收尾。
     */
    private suspend fun runAgentTurn(conversationId: String, config: ChatConfig) {
        reconcileUnansweredToolCalls(conversationId)
        var steps = 0
        while (true) {
            val forceFinal = steps >= config.maxAgentSteps
            val messageId = newId()
            val timestamp = nowMs()
            db.messageDao().upsert(
                MessageEntity(
                    id = messageId,
                    conversationId = conversationId,
                    role = Role.ASSISTANT.name,
                    content = "",
                    status = MessageStatus.STREAMING.name,
                    errorMessage = null,
                    reasoningContent = null,
                    seq = db.messageDao().nextSeq(conversationId),
                    model = config.model,
                    promptTokens = null,
                    completionTokens = null,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            )
            refreshSummary(conversationId)
            val history = db.messageDao().getByConversation(conversationId).map { it.toModel() }
            val outcome = streamOnce(
                conversationId = conversationId,
                messageId = messageId,
                config = if (forceFinal) config.copy(webSearchEnabled = false) else config,
                history = history,
            )
            if (outcome.failed) return
            val decision = AgentLoop.decide(outcome.finishReason, outcome.toolCalls, steps, config.maxAgentSteps)
            when (decision) {
                is AgentLoop.Decision.Continue -> {
                    executeTools(conversationId, config, decision.toolCalls)
                    steps++
                }

                AgentLoop.Decision.ForceFinal -> {
                    executeTools(conversationId, config, outcome.toolCalls)
                    steps++
                }

                AgentLoop.Decision.Finish -> return

                // 协议异常已由 finalize 标成可见错误，直接结束本回合（再循环也没东西可执行）。
                AgentLoop.Decision.Malformed -> return
            }
            // 收尾步无论如何都结束，杜绝模型在没有工具时仍回 tool_calls 造成死循环。
            if (forceFinal) return
        }
    }

    private suspend fun streamOnce(
        conversationId: String,
        messageId: String,
        config: ChatConfig,
        history: List<Message>,
    ): TurnOutcome {
        val messages = withContext(Dispatchers.IO) {
            ContextBuilder.build(history, config.historyImageLimit) { attachment ->
                attachmentStore.toRequestImage(attachment, config.imageDetail)
            }
        }
        // 计时从这里开始（含首 token 延迟），和 UI 上「已深度思考」的口径一致。
        val accumulator = StreamAccumulator(startedAt = elapsedMs())
        val toolCalls = ToolCallAccumulator()
        var status = MessageStatus.COMPLETE
        var errorMessage: String? = null

        _streaming.value = StreamingMessage(conversationId, messageId, "", "")
        try {
            api.stream(config, messages).collect { event ->
                if (event is ChatStreamEvent.ToolCallDelta) toolCalls.accept(event)
                val update = accumulator.accept(event, elapsedMs())
                if (update.publish) {
                    _streaming.value = StreamingMessage(
                        conversationId = conversationId,
                        messageId = messageId,
                        content = accumulator.content,
                        reasoning = accumulator.reasoning,
                        reasoningMs = accumulator.reasoningMs,
                    )
                }
                if (update.checkpoint) {
                    db.messageDao().updateContent(
                        id = messageId,
                        content = accumulator.content,
                        reasoning = accumulator.reasoning,
                        reasoningMs = accumulator.reasoningMs,
                        updatedAt = nowMs(),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            status = MessageStatus.CANCELLED
            throw cancelled
        } catch (t: ChatApiException) {
            status = MessageStatus.ERROR
            errorMessage = t.message ?: "请求失败"
        } catch (t: Throwable) {
            status = MessageStatus.ERROR
            errorMessage = t.message ?: "请求失败"
        } finally {
            withContext(NonCancellable) {
                val assembled = toolCalls.assemble()
                finalize(
                    conversationId = conversationId,
                    messageId = messageId,
                    accumulator = accumulator,
                    status = status,
                    errorMessage = errorMessage,
                    assembledToolCalls = assembled.size,
                )
                if (assembled.isNotEmpty()) {
                    db.messageDao().updateToolCalls(messageId, ToolCallCodec.encodeCalls(assembled), nowMs())
                }
            }
        }
        return TurnOutcome(
            finishReason = accumulator.finishReason,
            toolCalls = toolCalls.assemble(),
            failed = status == MessageStatus.ERROR,
        )
    }

    private suspend fun executeTools(conversationId: String, config: ChatConfig, calls: List<ToolCall>) {
        try {
            for (call in calls) {
                val toolMessageId = newId()
                val timestamp = nowMs()
                db.messageDao().upsert(
                    MessageEntity(
                        id = toolMessageId,
                        conversationId = conversationId,
                        role = Role.TOOL.name,
                        content = "",
                        status = MessageStatus.COMPLETE.name,
                        errorMessage = null,
                        reasoningContent = null,
                        seq = db.messageDao().nextSeq(conversationId),
                        model = config.model,
                        promptTokens = null,
                        completionTokens = null,
                        toolCallId = call.id,
                        toolResult = ToolCallCodec.encodeResult(
                            ToolResult(status = ToolStatus.RUNNING, detail = activityLabel(call)),
                        ),
                        createdAt = timestamp,
                        updatedAt = timestamp,
                    ),
                )
                _activity.value = AgentActivity(conversationId, activityLabel(call))
                var cancellation: CancellationException? = null
                val result = try {
                    runTool(call, config)
                } catch (c: CancellationException) {
                    cancellation = c
                    ToolResult(status = ToolStatus.FAILED, detail = call.name, text = TOOL_CANCELLED_TEXT)
                } catch (t: Throwable) {
                    ToolResult(status = ToolStatus.FAILED, detail = call.name, text = t.message ?: "工具执行失败")
                }
                // 取消时也要把 RUNNING 行落定，否则界面会永远停在「搜索中」。
                withContext(NonCancellable) {
                    db.messageDao().updateToolResultContent(
                        id = toolMessageId,
                        content = result.text.ifBlank { result.detail },
                        toolResult = ToolCallCodec.encodeResult(result),
                        updatedAt = nowMs(),
                    )
                    refreshSummary(conversationId)
                }
                if (cancellation != null) throw cancellation
            }
        } finally {
            _activity.value = null
        }
    }

    private suspend fun runTool(call: ToolCall, config: ChatConfig): ToolResult = when (call.name) {
        WebTools.SEARCH -> {
            val query = WebTools.queryOf(call.arguments)
                ?: return ToolResult(ToolStatus.FAILED, call.name, text = "缺少搜索词")
            val provider = searchProvider
                ?: return ToolResult(ToolStatus.FAILED, query, text = "未配置联网搜索后端")
            val result = provider.search(query, WebTools.DEFAULT_MAX_RESULTS, config)
            ToolResult(
                status = ToolStatus.OK,
                detail = query,
                sources = result.sources,
                text = WebTools.formatSearchResult(query, result),
            )
        }

        WebTools.FETCH -> {
            val url = WebTools.urlOf(call.arguments)
                ?: return ToolResult(ToolStatus.FAILED, call.name, text = "缺少 URL")
            val result = webFetcher.fetch(url)
            ToolResult(
                status = if (result.statusCode in 200..299) ToolStatus.OK else ToolStatus.FAILED,
                detail = url,
                text = WebTools.formatFetchResult(result),
            )
        }

        else -> ToolResult(ToolStatus.FAILED, call.name, text = "未知工具：${call.name}")
    }

    private fun activityLabel(call: ToolCall): String = when (call.name) {
        WebTools.SEARCH -> "正在联网搜索：${WebTools.queryOf(call.arguments).orEmpty()}"
        WebTools.FETCH -> "正在抓取网页：${WebTools.urlOf(call.arguments).orEmpty()}"
        else -> "正在调用 ${call.name}"
    }

    /** 崩溃窗口兜底：assistant 已落 tool_calls 但 TOOL 行没写上时，补一条中断占位，避免下次请求 400。 */
    private suspend fun reconcileUnansweredToolCalls(conversationId: String) {
        val entities = db.messageDao().getByConversation(conversationId)
        // 上次进程在工具执行中被杀，RUNNING 行会永远停在「搜索中」，这里落定为失败。
        entities.filter { it.role == Role.TOOL.name }.forEach { entity ->
            if (ToolCallCodec.decodeResult(entity.toolResult)?.status == ToolStatus.RUNNING) {
                db.messageDao().updateToolResultContent(
                    id = entity.id,
                    content = INTERRUPTED_TOOL_TEXT,
                    toolResult = ToolCallCodec.encodeResult(
                        ToolResult(
                            status = ToolStatus.FAILED,
                            detail = entity.toolCallId.orEmpty(),
                            text = INTERRUPTED_TOOL_TEXT,
                        ),
                    ),
                    updatedAt = nowMs(),
                )
            }
        }
        val answered = entities.filter { it.role == Role.TOOL.name }.mapNotNull { it.toolCallId }.toSet()
        val unanswered = entities
            .filter { it.role == Role.ASSISTANT.name }
            .flatMap { ToolCallCodec.decodeCalls(it.toolCalls) }
            .filter { it.id !in answered }
            .distinctBy { it.id }
        if (unanswered.isEmpty()) return
        var seq = db.messageDao().nextSeq(conversationId)
        val timestamp = nowMs()
        unanswered.forEach { call ->
            db.messageDao().upsert(
                MessageEntity(
                    id = newId(),
                    conversationId = conversationId,
                    role = Role.TOOL.name,
                    content = INTERRUPTED_TOOL_TEXT,
                    status = MessageStatus.COMPLETE.name,
                    errorMessage = null,
                    reasoningContent = null,
                    seq = seq++,
                    model = null,
                    promptTokens = null,
                    completionTokens = null,
                    toolCallId = call.id,
                    toolResult = ToolCallCodec.encodeResult(
                        ToolResult(status = ToolStatus.FAILED, detail = call.name, text = INTERRUPTED_TOOL_TEXT),
                    ),
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            )
        }
        refreshSummary(conversationId)
    }

    private suspend fun finalize(
        conversationId: String,
        messageId: String,
        accumulator: StreamAccumulator,
        status: MessageStatus,
        errorMessage: String?,
        assembledToolCalls: Int,
    ) {
        // `tool_calls` 但没有可执行的调用是协议异常：给出可见提示，而不是留一个空白气泡。
        val malformed = accumulator.finishReason == FINISH_TOOL_CALLS && assembledToolCalls == 0
        val finishNote = if (malformed) {
            "模型未能给出可执行的工具调用，本次生成已中断，可直接重试"
        } else {
            ApiErrorMapper.finishReasonMessage(accumulator.finishReason)
        }
        val hasContent = accumulator.content.isNotBlank()
        val resolvedStatus = when {
            status == MessageStatus.CANCELLED -> MessageStatus.CANCELLED
            status == MessageStatus.ERROR -> MessageStatus.ERROR
            finishNote != null && !hasContent -> MessageStatus.ERROR
            else -> MessageStatus.COMPLETE
        }
        val resolvedError = when {
            status == MessageStatus.ERROR -> errorMessage
            finishNote != null -> finishNote
            else -> null
        }
        db.messageDao().finalize(
            id = messageId,
            content = accumulator.content,
            reasoning = accumulator.reasoning.ifEmpty { null },
            status = resolvedStatus.name,
            errorMessage = resolvedError,
            promptTokens = accumulator.usage?.promptTokens,
            completionTokens = accumulator.usage?.completionTokens,
            reasoningTokens = accumulator.usage?.reasoningTokens,
            cachedTokens = accumulator.usage?.cachedTokens,
            reasoningMs = accumulator.reasoningMs,
            updatedAt = nowMs(),
        )
        if (_streaming.value?.messageId == messageId) {
            _streaming.value = null
        }
        refreshSummary(conversationId)
    }

    private suspend fun refreshSummary(conversationId: String) {
        val messages = db.messageDao().getByConversation(conversationId)
        val preview = messages.lastOrNull { it.role != Role.TOOL.name }?.toModel()?.let { message ->
            when {
                message.content.isNotBlank() -> ConversationTitle.preview(message.content)
                message.attachments.isNotEmpty() -> "［图片］"
                else -> ""
            }
        }.orEmpty()
        db.conversationDao().updateSummary(conversationId, preview, messages.size, nowMs())
    }
}
