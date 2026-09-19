package com.zcw.chatai.data

import androidx.room.withTransaction
import com.zcw.chatai.data.ai.AgentLoop
import com.zcw.chatai.data.ai.AttachmentRetention
import com.zcw.chatai.data.ai.ContextBuilder
import com.zcw.chatai.data.ai.DsmlStrip
import com.zcw.chatai.data.ai.StreamAccumulator
import com.zcw.chatai.data.ai.ToolBudget
import com.zcw.chatai.data.ai.ToolCallAccumulator
import com.zcw.chatai.data.ai.ToolFallbackChain
import com.zcw.chatai.data.ai.ToolImageInventory
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
import com.zcw.chatai.data.media.VideoUploadCoordinator
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.ConversationTitle
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.model.ToolKind
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolStatus
import com.zcw.chatai.data.net.ApiErrorMapper
import com.zcw.chatai.data.net.ChatApi
import com.zcw.chatai.data.net.ChatApiException
import com.zcw.chatai.data.net.ChatRequestVideo
import com.zcw.chatai.data.net.ChatStreamEvent
import com.zcw.chatai.data.prefs.ChatSettings
import com.zcw.chatai.data.prefs.SettingsRepository
import com.zcw.chatai.data.prefs.toChatConfig
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.provider.ToolBackendResolver
import com.zcw.chatai.data.web.HttpWebFetcher
import com.zcw.chatai.data.web.ImageSearchOutcome
import com.zcw.chatai.data.web.ImageSearchProvider
import com.zcw.chatai.data.web.WebFetcher
import com.zcw.chatai.data.web.WebSearchProvider
import com.zcw.chatai.data.web.WebSearchResult
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
import kotlinx.coroutines.flow.update
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

sealed interface SendResult {
    data object Started : SendResult

    /** 目标会话已有回合在跑：静默忽略，不弹文案（同一会话的连点/竞态）。 */
    data object Busy : SendResult

    data class Rejected(val reason: String) : SendResult
}

/** 后台失败：带会话 id，UI 只显示给对应会话，不跨会话打扰。 */
data class RepoError(
    val conversationId: String?,
    val message: String,
)

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
    /** 按供应商 id 选搜索后端；会话绑定哪个供应商就用哪个。 */
    private val searchProviders: Map<String, WebSearchProvider> = emptyMap(),
    /** 按供应商 id 选图搜后端（文搜图/以图搜图）。 */
    private val imageProviders: Map<String, ImageSearchProvider> = emptyMap(),
    /** 视频附件解析器（内联/上传/崩溃恢复）；未注入时视频消息会给出可读错误。 */
    private val videoUploadCoordinator: VideoUploadCoordinator? = null,
    private val webFetcher: WebFetcher = HttpWebFetcher(),
    private val turnForeground: TurnForeground = TurnForeground.NoOp,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** 量时长用的**单调**时钟：墙钟被改 / NTP 跳一下会让时长算出负数或离谱值。 */
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /** 正在流式的消息，按会话索引：跨会话并发时互不覆盖。 */
    private val _streaming = MutableStateFlow<Map<String, StreamingMessage>>(emptyMap())

    val streaming: StateFlow<Map<String, StreamingMessage>> = _streaming.asStateFlow()

    /** 后台失败（例如界面已退出）通过这里抛给 UI，而不是静默丢掉。 */
    private val _errors = MutableStateFlow<RepoError?>(null)

    val errors: StateFlow<RepoError?> = _errors.asStateFlow()

    /** 视频上传进度（会话 → 一行提示）；非空时 UI 显示「正在上传视频…」。 */
    private val _videoUploads = MutableStateFlow<Map<String, String>>(emptyMap())

    val videoUploads: StateFlow<Map<String, String>> = _videoUploads.asStateFlow()

    fun consumeError() {
        _errors.value = null
    }

    /**
     * 回合调度：同一会话至多一个进行中的回合，跨会话不限并发。
     * 不能只看 [streaming] —— 工具执行期间它会被清空，那样并发发送会插进第二个回合。
     */
    private val turns = TurnRegistry()

    /** 哪些会话正在跑回合（含工具执行全程），UI 用它决定发送/停止键。 */
    val busyConversations: StateFlow<Set<String>> = turns.busy

    /** 统一收口后台失败：日志留堆栈，界面只给一句人话（按会话路由）。 */
    private fun reportError(conversationId: String?, t: Throwable, fallback: String) {
        Log.e(TAG, "后台操作失败", t)
        _errors.value = RepoError(
            conversationId = conversationId,
            message = t.message?.takeIf { it.isNotBlank() } ?: fallback,
        )
    }

    private fun publishStreaming(message: StreamingMessage) {
        _streaming.update { it + (message.conversationId to message) }
    }

    private fun clearStreaming(messageId: String) {
        _streaming.update { current ->
            if (current.values.none { it.messageId == messageId }) {
                current
            } else {
                current.filterValues { it.messageId != messageId }
            }
        }
    }

    // ---------- 观察 ----------

    fun observeConversations(): Flow<List<Conversation>> =
        db.conversationDao().observeAll().map { list -> list.map { it.toModel() } }

    fun observeConversation(id: String): Flow<Conversation?> =
        db.conversationDao().observeById(id).map { it?.toModel() }

    fun observeMessages(conversationId: String): Flow<List<Message>> =
        db.messageDao().observeByConversation(conversationId).map { list -> list.map { it.toModel() } }

    // ---------- 会话 ----------

    /**
     * 新建会话。[model] / [providerId] 用来把「会话还不存在时用户已经选好的绑定」一次性落库，
     * 缺省则跟随当前激活供应商。
     */
    suspend fun createConversation(model: String? = null, providerId: String? = null): String {
        val settings = settingsRepository.settings.first()
        val boundProviderId = providerId?.takeIf { it.isNotBlank() } ?: settings.activeProviderId
        val id = newId()
        val timestamp = nowMs()
        db.conversationDao().upsert(
            ConversationEntity(
                id = id,
                title = ConversationTitle.FALLBACK,
                model = model?.takeIf { it.isNotBlank() }
                    ?: ProviderCatalog.defaultModelFor(settings.providers, boundProviderId),
                systemPrompt = null,
                createdAt = timestamp,
                updatedAt = timestamp,
                lastMessagePreview = "",
                messageCount = 0,
                isPinned = false,
                providerId = boundProviderId,
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

    /**
     * 切换会话绑定的供应商，并把模型一起重置为该供应商的模型。
     * 连接参数不落库——`resolveConfig` 按 provider_id 实时取，所以这里只改绑定。
     */
    suspend fun setConversationProvider(id: String, providerId: String, model: String) {
        if (providerId.isBlank() || model.isBlank()) return
        db.conversationDao().updateProviderAndModel(id, providerId, model, nowMs())
    }

    /** 删除会话：文件与行一起清掉。跑在仓库自己的 scope 上，界面退出也不会半途而废。 */
    fun deleteConversation(id: String) {
        turns.cancel(id)
        scope.launch {
            runCatching {
                val messages = db.messageDao().getByConversation(id)
                attachmentStore.delete(messages.flatMap { it.toModel().attachments })
                attachmentStore.deleteConversation(id)
                db.conversationDao().delete(id)
            }.onFailure { reportError(id, it, "删除会话失败") }
        }
    }

    fun clearConversation(id: String) {
        turns.cancel(id)
        scope.launch {
            runCatching {
                val messages = db.messageDao().getByConversation(id)
                attachmentStore.delete(messages.flatMap { it.toModel().attachments })
                attachmentStore.deleteConversation(id)
                db.messageDao().deleteByConversation(id)
                refreshSummary(id)
            }.onFailure { reportError(id, it, "清空会话失败") }
        }
    }

    fun deleteMessage(messageId: String) {
        _streaming.value.values.firstOrNull { it.messageId == messageId }
            ?.let { stop(it.conversationId) }
        scope.launch {
            var conversationId: String? = null
            runCatching {
                val entity = db.messageDao().getById(messageId) ?: return@runCatching
                val message = entity.toModel()
                conversationId = message.conversationId
                val all = db.messageDao().getByConversation(message.conversationId)
                // 分组规则见 ToolTurnGrouping：删一半会留下孤立的 tool_calls / TOOL 行 → 下次 400。
                val victims = ToolTurnGrouping.deletionSetFor(all.map { it.toNode() }, message.seq)
                    .mapNotNull { seq -> all.firstOrNull { it.seq == seq } }
                attachmentStore.delete(victims.flatMap { it.toModel().attachments })
                db.messageDao().deleteByIds(victims.map { it.id })
                refreshSummary(message.conversationId)
            }.onFailure { reportError(conversationId, it, "删除消息失败") }
        }
    }

    // ---------- 发送 / 重新生成 ----------

    /**
     * 发送。校验是同步的（立刻能告诉界面「为什么不给发」），真正的落库与流式
     * 跑在仓库自己的 scope 上 —— 用户点了发送之后即使界面退出，消息与回答也不会丢。
     * 只有**目标会话**已有回合在跑时才拒绝；其他会话照常并发。
     */
    fun send(
        conversationId: String,
        text: String,
        attachments: List<Attachment> = emptyList(),
    ): SendResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return SendResult.Rejected("请输入内容")
        AttachmentLimits.validate(attachments)?.let { return SendResult.Rejected(it) }

        val job = turns.startIfIdle(conversationId) {
            launchTurn(conversationId, "发送失败") {
                startTurn(conversationId, trimmed, attachments)
            }
        }
        return if (job == null) SendResult.Busy else SendResult.Started
    }

    /**
     * 重新生成：删掉这条回答及其之后的所有消息，用剩余上下文重跑。
     * 从用户气泡触发时，保留该条用户消息，从它之后截断再重跑。
     *
     * Agent 回合是「assistant(tool_calls) + TOOL 结果」的整体。这里必须以**整组**为单位截断：
     * - 落在目标之前的孤立 assistant（工具结果在目标之后）也要带走；
     * - 否则会留下「搜索没结果 / 抓取失败」的旧步骤，模型继承后继续失败。
     */
    fun regenerate(conversationId: String, messageId: String): SendResult {
        turns.restart(conversationId) {
            launchTurn(conversationId, "重新生成失败") {
                val entity = db.messageDao().getById(messageId)
                    ?: return@launchTurn
                val message = entity.toModel()
                // 从 TOOL 行触发时，按它所属的 assistant 回合整组截断。
                val targetSeq = resolveTurnStart(message) ?: run {
                    _errors.value = RepoError(message.conversationId, "只能重新生成回答")
                    return@launchTurn
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
            }
        }
        return SendResult.Started
    }

    /** 找出这条消息所属 Agent 回合的起点 seq：assistant 是自己的 seq，用户气泡从下一条起切，TOOL 走配对回它的发起回合。 */
    private suspend fun resolveTurnStart(message: Message): Long? {
        if (message.role == Role.ASSISTANT) return message.seq
        // 保留用户原文，deleteFrom(seq >= user.seq + 1) 清掉它后面的整段回答。
        if (message.role == Role.USER) return message.seq + 1
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

    /** 停止指定会话的回合；其他会话照常运行。 */
    fun stop(conversationId: String) = turns.cancel(conversationId)

    /** 停止所有会话的回合（仅系统侧兜底：前台服务超时被拆时无保活可用）。 */
    fun stopAll() = turns.cancelAll()

    /**
     * 在调用线程（发送/重生成点按，通常是主线程）立刻抬前台服务，
     * 再把回合丢到仓库 scope。切走之后 freezer 才不会冻进程掐 TCP。
     * 并发回合靠 [ChatTurnForeground] 的代数计数保活，这里不需要单例句柄。
     */
    private fun launchTurn(conversationId: String, errorFallback: String, block: suspend () -> Unit): Job {
        turnForeground.acquire()
        return scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                reportError(conversationId, t, errorFallback)
            } finally {
                turnForeground.release()
            }
        }
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
            _errors.value = RepoError(conversationId, "会话不存在")
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
        val settings = settingsRepository.settings.first()
        val config = resolveConfig(settings, conversationId) ?: return
        // 工具后端与主对话供应商解耦（借道）：搜索/图搜各自的供应商与模型。
        val toolContexts = toolBackendContexts(settings, config)
        // 预检门禁：所有会出站的视频先归位（内联/复用/上传），全部成功才发请求。
        val resolvedVideos = resolvePendingVideos(conversationId, config) ?: return
        try {
            runAgentTurn(
                conversationId = conversationId,
                config = config.copy(enabledTools = resolveEnabledTools(config, toolContexts)),
                resolvedVideos = resolvedVideos,
                toolContexts = toolContexts,
            )
        } finally {
            // 回合收尾（含取消）也落定一次：不把缺应答的 tool_calls 留给下一条消息。
            withContext(NonCancellable) { reconcileUnansweredToolCalls(conversationId) }
        }
    }

    /** 工具后端（借道）的运行时上下文：供应商 id + 它自己的请求配置（含模型）。 */
    private data class ToolBackendContexts(
        /** 文本搜索候选后端（有序）：会话/显式优先，失败逐个借道。 */
        val text: List<ToolBackend>,
        val image: ToolBackend?,
    ) {
        data class ToolBackend(
            val providerId: String,
            val config: ChatConfig,
            /** 仅 Qwen 图搜：模型优先级链（空/错逐个回退）；其它后端为空。 */
            val models: List<String> = emptyList(),
        )
    }

    private fun toolBackendContexts(settings: ChatSettings, config: ChatConfig): ToolBackendContexts {
        val ids = ToolBackendResolver.resolve(
            providers = settings.providers,
            activeProviderId = settings.activeProviderId,
            conversationProviderId = config.providerId,
            preferredSearchProviderId = settings.searchProviderId,
        )
        fun backend(id: String?): ToolBackendContexts.ToolBackend? = id?.let { providerId ->
            settings.providers[providerId]?.let { entry ->
                ToolBackendContexts.ToolBackend(
                    providerId = providerId,
                    config = settings.toChatConfig(providerId).copy(
                        baseUrl = entry.baseUrl,
                        apiKey = entry.apiKey,
                        model = entry.model,
                    ),
                )
            }
        }
        // 图搜后端带模型链：与对话模型解耦，先 27b、空/错退 max。
        val image = backend(ids.imageProviderId)?.let { backend ->
            backend.copy(models = settings.imageSearchModels)
        }
        return ToolBackendContexts(text = ids.textProviderIds.mapNotNull(::backend), image = image)
    }

    /**
     * 本回合注入哪些工具：🌐 开着才注入；按**工具后端**（不是会话供应商）的可用性拼名单。
     * 文本搜索有多个候选后端，任一可用即可注入（运行时逐个回退）；图搜/抓取独立判定。
     */
    private fun resolveEnabledTools(config: ChatConfig, contexts: ToolBackendContexts): List<String> {
        if (!config.webSearchEnabled) return emptyList()
        val textAvailable = contexts.text.any { backend ->
            searchProviders[backend.providerId]?.available(backend.config.baseUrl, backend.config.apiKey) == true
        }
        val imageAvailable = contexts.image?.let { backend ->
            imageProviders[backend.providerId]?.available(backend.config.baseUrl, backend.config.apiKey) == true
        } == true
        if (!textAvailable && !imageAvailable) return emptyList()
        return buildList {
            if (textAvailable) add(WebTools.SEARCH)
            if (imageAvailable) {
                add(WebTools.SEARCH_IMAGES)
                add(WebTools.FIND_SIMILAR_IMAGES)
            }
            // 抓取是纯客户端实现：既然已经开了联网工具，就一并提供。
            add(WebTools.FETCH)
        }
    }

    /**
     * 预检门禁：本回合会出站的所有视频先归位（内联/复用/上传），全部成功才返回。
     * 任一步失败 → 落一条 ERROR 助手消息（**不发请求**），重试/重新生成会续传。
     */
    private suspend fun resolvePendingVideos(
        conversationId: String,
        config: ChatConfig,
    ): Map<String, ChatRequestVideo>? {
        val coordinator = videoUploadCoordinator
        if (coordinator == null) {
            val hasVideo = db.messageDao().getByConversation(conversationId)
                .any { entity -> AttachmentCodec.decode(entity.attachments).any { it.kind == AttachmentKind.VIDEO } }
            if (!hasVideo) return emptyMap()
            failTurn(conversationId, "当前版本不支持视频输入")
            return null
        }
        val messages = db.messageDao().getByConversation(conversationId).map { it.toModel() }
        val kept = AttachmentRetention.keptMessageIds(messages, config.historyImageTurns)
        val videoMessages = messages.filter { message ->
            message.id in kept && message.attachments.any { it.kind == AttachmentKind.VIDEO }
        }
        if (videoMessages.isEmpty()) return emptyMap()
        // 能力门禁：会话绑定的供应商不支持视频时，UI 本不该给出视频入口；
        // 这里兜底（例如切换过激活供应商/改过绑定），不发注定 400 的请求。
        if (!ProviderCatalog.supportsVideo(config.providerId)) {
            failTurn(
                conversationId,
                "当前供应商「${ProviderCatalog.displayName(config.providerId)}」不支持视频输入，请切换供应商或移除视频",
            )
            return null
        }

        val total = videoMessages.sumOf { message ->
            message.attachments.count { it.kind == AttachmentKind.VIDEO }
        }
        var done = 0
        val resolved = HashMap<String, ChatRequestVideo>()
        try {
            _videoUploads.update { it + (conversationId to "正在处理视频 0/$total…") }
            for (message in videoMessages) {
                var current = message.attachments
                for (original in message.attachments.filter { it.kind == AttachmentKind.VIDEO }) {
                    val attachment = current.firstOrNull { it.id == original.id } ?: continue
                    done++
                    _videoUploads.update { it + (conversationId to "正在处理视频 $done/$total…") }
                    val (wire, updated) = coordinator.resolve(config, attachment) { pending ->
                        current = current.replaceAttachment(attachment.id, pending)
                        db.messageDao().updateAttachments(message.id, AttachmentCodec.encode(current), nowMs())
                    }
                    resolved[attachment.id] = wire
                    if (updated != attachment) {
                        current = current.replaceAttachment(attachment.id, updated)
                        db.messageDao().updateAttachments(message.id, AttachmentCodec.encode(current), nowMs())
                    }
                }
            }
            return resolved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            failTurn(conversationId, t.message?.takeIf { it.isNotBlank() } ?: "视频处理失败")
            return null
        } finally {
            _videoUploads.update { it - conversationId }
        }
    }

    private fun List<Attachment>.replaceAttachment(id: String, updated: Attachment): List<Attachment> =
        map { if (it.id == id) updated else it }

    /** 预检失败：落一条可见的 ERROR 助手消息（消息还在，用户可直接重试）。 */
    private suspend fun failTurn(conversationId: String, text: String) {
        val timestamp = nowMs()
        db.messageDao().upsert(
            MessageEntity(
                id = newId(),
                conversationId = conversationId,
                role = Role.ASSISTANT.name,
                content = "",
                status = MessageStatus.ERROR.name,
                errorMessage = "$text（消息已保留，可直接重试）",
                reasoningContent = null,
                seq = db.messageDao().nextSeq(conversationId),
                model = null,
                promptTokens = null,
                completionTokens = null,
                createdAt = timestamp,
                updatedAt = timestamp,
            ),
        )
        refreshSummary(conversationId)
    }

    /**
     * 会话的请求配置：连接参数按会话绑定的供应商取，生成参数仍是全局。
     * 供应商已被删除 → 给可读错误并返回 null（不向错误端点发请求）。
     */
    private suspend fun resolveConfig(settings: ChatSettings, conversationId: String): ChatConfig? {
        val conversation = db.conversationDao().getById(conversationId)
        val providerId = conversation?.providerId?.takeIf { it.isNotBlank() }
            ?: settings.activeProviderId
        if (providerId !in settings.providers) {
            _errors.value = RepoError(
                conversationId,
                "该会话绑定的供应商「${ProviderCatalog.displayName(providerId)}」已被删除，请到设置里重新配置",
            )
            return null
        }
        val base = settings.toChatConfig(providerId)
        return base.copy(
            model = conversation?.model?.takeIf { it.isNotBlank() } ?: base.model,
            systemPrompt = conversation?.systemPrompt?.takeIf { it.isNotBlank() } ?: base.systemPrompt,
            webSearchEnabled = conversation?.webSearchEnabled == true,
            // 网关要求稳定会话 id（Go 实测缺了 400）；用会话 id 最自然。
            sessionId = conversationId,
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
    private suspend fun runAgentTurn(
        conversationId: String,
        config: ChatConfig,
        resolvedVideos: Map<String, ChatRequestVideo>,
        toolContexts: ToolBackendContexts,
    ) {
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
                config = if (forceFinal) config.copy(enabledTools = emptyList()) else config,
                history = history,
                resolvedVideos = resolvedVideos,
            )
            if (outcome.failed) return
            val decision = AgentLoop.decide(outcome.finishReason, outcome.toolCalls, steps, config.maxAgentSteps)
            when (decision) {
                is AgentLoop.Decision.Continue -> {
                    // 这批工具跑完后的剩余轮次（下一次请求若 >= maxAgentSteps 就是无工具收尾）。
                    val remaining = (config.maxAgentSteps - (steps + 1)).coerceAtLeast(0)
                    executeTools(conversationId, config, decision.toolCalls, toolContexts, remaining)
                    steps++
                }

                AgentLoop.Decision.ForceFinal -> {
                    // 预算已耗尽：这批工具是最后一批，预算提示固定 0。
                    executeTools(conversationId, config, outcome.toolCalls, toolContexts, remainingRounds = 0)
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
        resolvedVideos: Map<String, ChatRequestVideo>,
    ): TurnOutcome {
        val messages = withContext(Dispatchers.IO) {
            ContextBuilder.build(
                history = history,
                imageTurns = config.historyImageTurns,
                imageProvider = { attachment ->
                    attachmentStore.toRequestImage(attachment, config.imageDetail)
                },
                videoProvider = { attachment -> resolvedVideos[attachment.id] },
            )
        }
        // 计时从这里开始（含首 token 延迟），和 UI 上「已深度思考」的口径一致。
        val accumulator = StreamAccumulator(startedAt = elapsedMs())
        val toolCalls = ToolCallAccumulator()
        var status = MessageStatus.COMPLETE
        var errorMessage: String? = null

        publishStreaming(StreamingMessage(conversationId, messageId, "", ""))
        try {
            api.stream(config, messages).collect { event ->
                if (event is ChatStreamEvent.ToolCallDelta) toolCalls.accept(event)
                val update = accumulator.accept(event, elapsedMs())
                if (update.publish) {
                    publishStreaming(
                        StreamingMessage(
                            conversationId = conversationId,
                            messageId = messageId,
                            content = DsmlStrip.strip(accumulator.content),
                            reasoning = accumulator.reasoning,
                            reasoningMs = accumulator.reasoningMs,
                        ),
                    )
                }
                if (update.checkpoint) {
                    db.messageDao().updateContent(
                        id = messageId,
                        content = DsmlStrip.strip(accumulator.content),
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

    private suspend fun executeTools(
        conversationId: String,
        config: ChatConfig,
        calls: List<ToolCall>,
        toolContexts: ToolBackendContexts,
        remainingRounds: Int,
    ) {
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
                        ToolResult(
                            status = ToolStatus.RUNNING,
                            detail = activityLabel(call),
                            // 跑起来时也带上 kind，标题就不会先显示成「联网搜索」再跳成「文搜图」。
                            kind = kindOf(call),
                        ),
                    ),
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            )
            var cancellation: CancellationException? = null
            val raw = try {
                runTool(conversationId, config, call, toolContexts)
            } catch (c: CancellationException) {
                cancellation = c
                ToolResult(status = ToolStatus.FAILED, detail = call.name, text = TOOL_CANCELLED_TEXT)
            } catch (t: Throwable) {
                ToolResult(status = ToolStatus.FAILED, detail = call.name, text = t.message ?: "工具执行失败")
            }
            // 预算提示统一挂在这里：成功/失败都带，模型不会误以为还有机会。
            val result = raw.copy(
                modelNote = ToolBudget.merge(raw.modelNote, ToolBudget.note(remainingRounds)),
            )
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
    }

    private suspend fun runTool(
        conversationId: String,
        config: ChatConfig,
        call: ToolCall,
        toolContexts: ToolBackendContexts,
    ): ToolResult = when (call.name) {
        WebTools.SEARCH -> {
            val query = WebTools.queryOf(call.arguments)
                ?: return ToolResult(ToolStatus.FAILED, call.name, text = "缺少搜索词", kind = ToolKind.SEARCH)
            val backends = toolContexts.text
            if (backends.isEmpty()) {
                return ToolResult(ToolStatus.FAILED, query, text = "未配置联网搜索后端", kind = ToolKind.SEARCH)
            }
            // 会话/显式后端优先；空结果或报错自动借道下一个已配置后端。
            val attempts = mutableListOf<String>()
            val result = ToolFallbackChain.firstUsable(
                candidates = backends,
                isEmpty = { r: WebSearchResult -> r.sources.isEmpty() && r.answer.isNullOrBlank() },
            ) { backend ->
                attempts += backend.providerId
                searchProviders[backend.providerId]
                    ?.search(query, WebTools.DEFAULT_MAX_RESULTS, backend.config)
                    ?: throw ChatApiException("未配置联网搜索后端")
            }
            logFallback(call, attempts)
            val empty = result.sources.isEmpty() && result.answer.isNullOrBlank()
            ToolResult(
                status = ToolStatus.OK,
                detail = query,
                sources = result.sources,
                text = WebTools.formatSearchResult(query, result),
                kind = ToolKind.SEARCH,
                modelNote = if (empty) WebTools.emptySearchNote(query) else null,
            )
        }

        WebTools.FETCH -> {
            val url = WebTools.urlOf(call.arguments)
                ?: return ToolResult(ToolStatus.FAILED, call.name, text = "缺少 URL", kind = ToolKind.FETCH)
            val result = webFetcher.fetch(url)
            ToolResult(
                status = if (result.statusCode in 200..299) ToolStatus.OK else ToolStatus.FAILED,
                detail = url,
                text = WebTools.formatFetchResult(result),
                kind = ToolKind.FETCH,
            )
        }

        WebTools.SEARCH_IMAGES -> {
            val query = WebTools.queryOf(call.arguments)
                ?: return ToolResult(ToolStatus.FAILED, call.name, text = "缺少搜索词", kind = ToolKind.IMAGE_SEARCH)
            val backend = toolContexts.image
                ?: return ToolResult(ToolStatus.FAILED, query, text = "未配置图搜后端（需要通义千问）", kind = ToolKind.IMAGE_SEARCH)
            val provider = imageProviders[backend.providerId]
                ?: return ToolResult(ToolStatus.FAILED, query, text = "未配置图搜后端（需要通义千问）", kind = ToolKind.IMAGE_SEARCH)
            // 模型链：先 27b，空结果/报错退 max（与对话模型解耦）。
            val attempts = mutableListOf<String>()
            val outcome = ToolFallbackChain.firstUsable(
                candidates = backend.models.ifEmpty { listOf(backend.config.model) },
                isEmpty = { r: ImageSearchOutcome -> r.images.isEmpty() },
            ) { model ->
                attempts += model
                provider.searchByText(query, WebTools.DEFAULT_IMAGE_RESULTS, backend.config.copy(model = model))
            }
            logFallback(call, attempts)
            ToolResult(
                status = ToolStatus.OK,
                detail = query,
                text = WebTools.formatImageSearchResult(query, outcome.images),
                kind = ToolKind.IMAGE_SEARCH,
                images = outcome.images,
                modelNote = if (outcome.images.isEmpty()) WebTools.emptyImageSearchNote(query) else null,
            )
        }

        WebTools.FIND_SIMILAR_IMAGES -> {
            val backend = toolContexts.image
                ?: return ToolResult(
                    ToolStatus.FAILED,
                    "以图搜图",
                    text = "未配置图搜后端（需要通义千问）",
                    kind = ToolKind.IMAGE_SIMILAR,
                )
            val provider = imageProviders[backend.providerId]
                ?: return ToolResult(
                    ToolStatus.FAILED,
                    "以图搜图",
                    text = "未配置图搜后端（需要通义千问）",
                    kind = ToolKind.IMAGE_SIMILAR,
                )
            // 与上下文标注共用同一份可见图片清单：先按出站窗口裁剪，编号才不会错位。
            val images = ToolImageInventory.visibleImages(
                ContextBuilder.usableHistory(
                    db.messageDao().getByConversation(conversationId).map { it.toModel() },
                ),
                config.historyImageTurns,
            )
            if (images.isEmpty()) {
                return ToolResult(
                    ToolStatus.FAILED,
                    "以图搜图",
                    text = "当前会话里没有可用的图片，请先发送一张图片",
                    kind = ToolKind.IMAGE_SIMILAR,
                )
            }
            val requested = WebTools.imageIndexOf(call.arguments)
            val picked = if (requested == null) images.last() else images.getOrNull(requested - 1)
                ?: return ToolResult(
                    ToolStatus.FAILED,
                    "以图搜图",
                    text = "image_index=$requested 超出范围：本会话可见图片共 ${images.size} 张（1..${images.size}）",
                    kind = ToolKind.IMAGE_SIMILAR,
                )
            val dataUrl = attachmentStore.toRequestImage(picked.attachment, null)?.dataUrl
                ?: return ToolResult(
                    ToolStatus.FAILED,
                    "以图搜图",
                    text = "第 ${picked.globalIndex} 张图片不可用（文件缺失）",
                    kind = ToolKind.IMAGE_SIMILAR,
                )
            val attempts = mutableListOf<String>()
            val outcome = ToolFallbackChain.firstUsable(
                candidates = backend.models.ifEmpty { listOf(backend.config.model) },
                isEmpty = { r: ImageSearchOutcome -> r.images.isEmpty() },
            ) { model ->
                attempts += model
                provider.searchByImage(
                    dataUrl,
                    null,
                    WebTools.DEFAULT_IMAGE_RESULTS,
                    backend.config.copy(model = model),
                )
            }
            logFallback(call, attempts)
            val used = "Used image ${picked.globalIndex} of ${images.size} in this conversation."
            ToolResult(
                status = ToolStatus.OK,
                detail = "以图搜图",
                text = "$used\n" + WebTools.formatImageSearchResult("以图搜图", outcome.images),
                kind = ToolKind.IMAGE_SIMILAR,
                images = outcome.images,
                modelNote = if (outcome.images.isEmpty()) WebTools.emptyImageSimilarNote() else null,
            )
        }

        else -> ToolResult(ToolStatus.FAILED, call.name, text = "未知工具：${call.name}")
    }

    private fun kindOf(call: ToolCall): ToolKind = when (call.name) {
        WebTools.SEARCH -> ToolKind.SEARCH
        WebTools.FETCH -> ToolKind.FETCH
        WebTools.SEARCH_IMAGES -> ToolKind.IMAGE_SEARCH
        WebTools.FIND_SIMILAR_IMAGES -> ToolKind.IMAGE_SIMILAR
        else -> ToolKind.SEARCH
    }

    /** 只有真的发生回退才记一条：logcat 里没有 = 首选后端/模型直接成功。 */
    private fun logFallback(call: ToolCall, attempts: List<String>) {
        if (attempts.size > 1) Log.i(TAG, "${call.name} 回退：$attempts（末次生效）")
    }

    private fun activityLabel(call: ToolCall): String = when (call.name) {
        WebTools.SEARCH -> "正在联网搜索：${WebTools.queryOf(call.arguments).orEmpty()}"
        WebTools.FETCH -> "正在抓取网页：${WebTools.urlOf(call.arguments).orEmpty()}"
        WebTools.SEARCH_IMAGES -> "正在搜索图片：${WebTools.queryOf(call.arguments).orEmpty()}"
        WebTools.FIND_SIMILAR_IMAGES -> "正在以图搜图…"
        else -> "正在调用 ${call.name}"
    }

    /** 崩溃窗口兜底：assistant 已落 tool_calls 但 TOOL 行没写上时，补一条中断占位，避免下次请求 400。 */
    private suspend fun reconcileUnansweredToolCalls(conversationId: String) {
        val entities = db.messageDao().getByConversation(conversationId)
        val callsById = entities
            .filter { it.role == Role.ASSISTANT.name }
            .flatMap { ToolCallCodec.decodeCalls(it.toolCalls) }
            .associateBy { it.id }
        // 上次进程在工具执行中被杀，RUNNING 行会永远停在「搜索中」，这里落定为失败。
        entities.filter { it.role == Role.TOOL.name }.forEach { entity ->
            if (ToolCallCodec.decodeResult(entity.toolResult)?.status == ToolStatus.RUNNING) {
                db.messageDao().updateToolResultContent(
                    id = entity.id,
                    content = INTERRUPTED_TOOL_TEXT,
                    toolResult = ToolCallCodec.encodeResult(
                        ToolResult(
                            status = ToolStatus.FAILED,
                            detail = entity.toolCallId?.let(callsById::get)?.let(::interruptedDetail) ?: "已中断",
                            text = INTERRUPTED_TOOL_TEXT,
                        ),
                    ),
                    updatedAt = nowMs(),
                )
            }
        }
        val plan = ToolTurnGrouping.planMissingToolAnswers(entities.map { it.toNode() })
        if (plan.isEmpty()) return
        val timestamp = nowMs()
        db.withTransaction {
            plan.forEach { missing ->
                // 插在发起它的 assistant 的应答块之后（而不是队尾——那里可能有后来的用户消息）。
                db.messageDao().shiftSeqsFrom(conversationId, missing.afterSeq + 1)
                db.messageDao().upsert(
                    MessageEntity(
                        id = newId(),
                        conversationId = conversationId,
                        role = Role.TOOL.name,
                        content = INTERRUPTED_TOOL_TEXT,
                        status = MessageStatus.COMPLETE.name,
                        errorMessage = null,
                        reasoningContent = null,
                        seq = missing.afterSeq + 1,
                        model = null,
                        promptTokens = null,
                        completionTokens = null,
                        toolCallId = missing.call.id,
                        toolResult = ToolCallCodec.encodeResult(
                            ToolResult(
                                status = ToolStatus.FAILED,
                                detail = interruptedDetail(missing.call),
                                text = INTERRUPTED_TOOL_TEXT,
                            ),
                        ),
                        createdAt = timestamp,
                        updatedAt = timestamp,
                    ),
                )
            }
        }
        refreshSummary(conversationId)
    }

    /** 中断占位的可读标签：搜索显示词、抓取显示 URL，其余显示工具名。 */
    private fun interruptedDetail(call: ToolCall): String = when (call.name) {
        WebTools.SEARCH, WebTools.SEARCH_IMAGES -> WebTools.queryOf(call.arguments) ?: call.name
        WebTools.FETCH -> WebTools.urlOf(call.arguments) ?: call.name
        WebTools.FIND_SIMILAR_IMAGES -> "以图搜图"
        else -> call.name
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
        // 兜底剥离 DeepSeek 漏进正文的原生工具标记（渲染与落库用同一份清洗后的文本）。
        val content = DsmlStrip.strip(accumulator.content)
        val hasContent = content.isNotBlank()
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
            content = content,
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
        clearStreaming(messageId)
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
