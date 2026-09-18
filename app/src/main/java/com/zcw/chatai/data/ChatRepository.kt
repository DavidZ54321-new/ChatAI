package com.zcw.chatai.data

import com.zcw.chatai.data.ai.ContextBuilder
import com.zcw.chatai.data.ai.StreamAccumulator
import com.zcw.chatai.data.db.AppDatabase
import com.zcw.chatai.data.db.AttachmentCodec
import com.zcw.chatai.data.db.ConversationEntity
import com.zcw.chatai.data.db.MessageEntity
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
import com.zcw.chatai.data.net.ApiErrorMapper
import com.zcw.chatai.data.net.ChatApi
import com.zcw.chatai.data.net.ChatApiException
import com.zcw.chatai.data.prefs.SettingsRepository
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

    private var streamJob: Job? = null

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
                attachmentStore.delete(entity.toModel().attachments)
                db.messageDao().deleteById(messageId)
                refreshSummary(entity.conversationId)
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
        if (_streaming.value != null) return SendResult.Rejected("正在生成中，请先停止")

        scope.launch {
            runCatching { startTurn(conversationId, trimmed, attachments) }
                .onFailure { reportError(it, "发送失败") }
        }
        return SendResult.Started
    }

    /** 重新生成：删掉这条回答及其之后的所有消息，用剩余上下文重跑。 */
    fun regenerate(messageId: String): SendResult {
        if (_streaming.value != null) stop()
        scope.launch {
            runCatching {
                val entity = db.messageDao().getById(messageId)
                    ?: return@runCatching
                val message = entity.toModel()
                if (message.role != Role.ASSISTANT) {
                    _errors.value = "只能重新生成回答"
                    return@runCatching
                }
                val victims = db.messageDao().getFrom(message.conversationId, message.seq)
                attachmentStore.delete(victims.flatMap { it.toModel().attachments })
                db.messageDao().deleteFrom(message.conversationId, message.seq)
                refreshSummary(message.conversationId)
                startAssistant(message.conversationId)
            }.onFailure { reportError(it, "重新生成失败") }
        }
        return SendResult.Started
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

    private fun stopIfStreaming(conversationId: String) {
        if (_streaming.value?.conversationId == conversationId) stop()
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
        val timestamp = nowMs()
        val messageId = newId()
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
        streamJob = scope.launch {
            runStream(conversationId, messageId, config, history)
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

    private suspend fun runStream(
        conversationId: String,
        messageId: String,
        config: ChatConfig,
        history: List<Message>,
    ) {
        val messages = withContext(Dispatchers.IO) {
            ContextBuilder.build(history, config.historyImageLimit) { attachment ->
                attachmentStore.toRequestImage(attachment, config.imageDetail)
            }
        }
        // 计时从这里开始（含首 token 延迟），和 UI 上「已深度思考」的口径一致。
        val accumulator = StreamAccumulator(startedAt = elapsedMs())
        var status = MessageStatus.COMPLETE
        var errorMessage: String? = null

        _streaming.value = StreamingMessage(conversationId, messageId, "", "")
        try {
            api.stream(config, messages).collect { event ->
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
                finalize(conversationId, messageId, accumulator, status, errorMessage)
            }
        }
    }

    private suspend fun finalize(
        conversationId: String,
        messageId: String,
        accumulator: StreamAccumulator,
        status: MessageStatus,
        errorMessage: String?,
    ) {
        val finishNote = ApiErrorMapper.finishReasonMessage(accumulator.finishReason)
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
        val preview = messages.lastOrNull()?.toModel()?.let { message ->
            when {
                message.content.isNotBlank() -> ConversationTitle.preview(message.content)
                message.attachments.isNotEmpty() -> "［图片］"
                else -> ""
            }
        }.orEmpty()
        db.conversationDao().updateSummary(conversationId, preview, messages.size, nowMs())
    }
}
