package com.zcw.chatai.data.image

import android.util.Log
import com.zcw.chatai.data.ConversationLamps
import com.zcw.chatai.data.TurnForeground
import com.zcw.chatai.data.TurnRegistry
import com.zcw.chatai.data.db.AppDatabase
import com.zcw.chatai.data.db.AttachmentCodec
import com.zcw.chatai.data.db.ConversationEntity
import com.zcw.chatai.data.db.MessageEntity
import com.zcw.chatai.data.db.searchKind
import com.zcw.chatai.data.db.toModel
import com.zcw.chatai.data.media.AttachmentStore
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.ConversationKind
import com.zcw.chatai.data.model.ConversationTitle
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.net.ChatApi
import com.zcw.chatai.data.net.ChatStreamEvent
import com.zcw.chatai.data.prefs.ChatSettings
import com.zcw.chatai.data.prefs.SettingsRepository
import com.zcw.chatai.data.prefs.requireTextRewriteConfig
import com.zcw.chatai.data.prefs.toChatConfig
import com.zcw.chatai.data.provider.ProviderCatalog
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ImageRepository"

/** 一次生图已经解析好的后端。发送和重新生成共用，避免各猜一次供应商。 */
private data class ResolvedImageTurn(
    val backend: ImageBackend,
    val providerId: String,
    val model: String,
    val acceptsImageInput: Boolean,
)

sealed interface ImageSendResult {
    data object Started : ImageSendResult

    /** 该会话已有回合在跑：静默忽略。 */
    data object Busy : ImageSendResult

    data class Rejected(val reason: String) : ImageSendResult
}

/** 后台失败：带会话 id，UI 只对当前会话显示。 */
data class ImageRepoError(
    val conversationId: String?,
    val message: String,
)

/**
 * 生图的唯一业务入口（与 `ChatRepository` 平级）。
 *
 * 与对话回路不同，这里是**一次性**调用：落用户消息 → 建助手占位 → 图像后端出图
 * → 结果字节落盘 → 定稿助手消息。没有流式、没有 Agent、没有工具。
 * 后端由会话的 `providerId` 选择（通义千问或 OpenAI）。
 * 生成跑在仓库自己的 scope 上（用户动作不依赖 UI 生命周期），并借 [TurnForeground] 在前台保活。
 */
class ImageRepository(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val backends: List<ImageBackend>,
    private val attachmentStore: AttachmentStore,
    /** 提示词改写用它（走当前对话模型，与生图后端无关）。 */
    private val chatApi: ChatApi,
    private val turnForeground: TurnForeground = TurnForeground.NoOp,
    /** 与对话共用。一轮开始/结束时上报，不在助手占位定稿时写灯。 */
    private val lamps: ConversationLamps = ConversationLamps(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    private val turns = TurnRegistry()

    /** 发送前同步校验要用。DataStore 是 Flow，这里留最新一帧。 */
    private val settingsNow = MutableStateFlow<ChatSettings?>(null)

    init {
        scope.launch { settingsRepository.settings.collect { settingsNow.value = it } }
    }

    /** 正在生成的会话集合（UI 用它决定发送/停止键）。 */
    val busyConversations: StateFlow<Set<String>> = turns.busy

    private val _errors = MutableStateFlow<ImageRepoError?>(null)

    val errors: StateFlow<ImageRepoError?> = _errors.asStateFlow()

    /** 远程拉到的模型。空表表示该供应商仍用内置名单。 */
    private val _remoteModels = MutableStateFlow<Map<String, List<ImageModelOption>>>(emptyMap())

    val remoteModels: StateFlow<Map<String, List<ImageModelOption>>> = _remoteModels.asStateFlow()

    fun modelSections(remote: Map<String, List<ImageModelOption>> = _remoteModels.value): List<ImageModelSection> =
        ImageModelCatalog.sections(backends, remote)

    fun selection(
        model: String,
        storedProviderId: String?,
        remote: Map<String, List<ImageModelOption>> = _remoteModels.value,
    ): ImageSelection = ImageModelCatalog.selection(backends, remote, model, storedProviderId)

    /** 按供应商各拉一次模型列表。失败的那段保持空，选择器继续显示内置项。 */
    suspend fun refreshModels() {
        val settings = settingsRepository.settings.first()
        val fetched = backends.associate { backend ->
            val entry = settings.providers[backend.providerId]
            val models = if (entry == null || entry.apiKey.isBlank()) {
                emptyList()
            } else {
                try {
                    backend.listModels(settings.toChatConfig(backend.providerId))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    emptyList()
                }
            }
            backend.providerId to models
        }
        _remoteModels.value = fetched
    }

    fun consumeError() {
        _errors.value = null
    }

    // ---------- 观察 ----------

    fun observeConversations(): Flow<List<Conversation>> =
        db.conversationDao().observeByKind(ConversationKind.IMAGE.name)
            .map { list -> list.map { it.toModel() } }

    /** 标题或消息正文命中关键词的生图会话（关键词按字面量匹配，见 [LikePattern]）。 */
    fun searchConversations(query: String): Flow<List<Conversation>> =
        db.conversationDao().searchKind(ConversationKind.IMAGE, query)

    fun observeConversation(id: String): Flow<Conversation?> =
        db.conversationDao().observeById(id).map { it?.toModel() }

    fun observeMessages(conversationId: String): Flow<List<Message>> =
        db.messageDao().observeByConversation(conversationId)
            .conflate()
            .map { list -> list.map { it.toModel() } }
            .flowOn(Dispatchers.Default)

    // ---------- 会话 ----------

    suspend fun createConversation(model: String? = null, providerId: String? = null): String {
        val settings = settingsRepository.settings.first()
        val resolvedModel = model?.takeIf { it.isNotBlank() } ?: settings.resolvedImageWorkspaceModel
        val resolvedProvider = providerId?.takeIf { it.isNotBlank() }
            ?: settings.resolvedImageWorkspaceProvider
        val id = newId()
        val timestamp = nowMs()
        db.conversationDao().upsert(
            ConversationEntity(
                id = id,
                title = ConversationTitle.FALLBACK,
                model = resolvedModel,
                systemPrompt = null,
                createdAt = timestamp,
                updatedAt = timestamp,
                lastMessagePreview = "",
                messageCount = 0,
                isPinned = false,
                providerId = resolvedProvider,
                personaId = "",
                webSearchEnabled = false,
                kind = ConversationKind.IMAGE.name,
            ),
        )
        return id
    }

    suspend fun renameConversation(id: String, title: String) {
        val clean = title.trim().ifEmpty { ConversationTitle.FALLBACK }
        db.conversationDao().rename(id, clean, nowMs())
    }

    suspend fun setConversationModel(id: String, providerId: String, model: String) {
        if (model.isBlank() || providerId.isBlank()) return
        db.conversationDao().updateProviderAndModel(id, providerId, model, nowMs())
    }

    /** 删除会话：连同它的附件目录；跑在仓库 scope 上，界面退出也不半途而废。 */
    fun deleteConversation(id: String) {
        turns.cancel(id)
        scope.launch {
            runCatching {
                attachmentStore.deleteConversation(id)
                db.conversationDao().delete(id)
            }.onFailure { reportError(id, it, "删除会话失败") }
        }
    }

    /**
     * 删除消息：只删数据库行，**不删物理文件**——自动带入的「上一张」与助手图共享同一路径，
     * 删了用户轮会把助手图一并带走。真正的孤儿文件由 `ChatRepository.sweepOrphanAttachments`
     * 在「无任何消息引用」时回收。删用户轮时连带它紧邻的助手轮（一次问答成对删除）。
     */
    fun deleteMessage(messageId: String) {
        scope.launch {
            var conversationId: String? = null
            runCatching {
                val entity = db.messageDao().getById(messageId) ?: return@runCatching
                val message = entity.toModel()
                conversationId = message.conversationId
                val all = db.messageDao().getByConversation(message.conversationId)
                val victims = if (message.role == Role.USER) {
                    all.filter { it.seq == message.seq || it.seq == message.seq + 1 }
                } else {
                    all.filter { it.id == message.id }
                }
                db.messageDao().deleteByIds(victims.map { it.id })
                refreshSummary(message.conversationId)
            }.onFailure { reportError(conversationId, it, "删除消息失败") }
        }
    }

    // ---------- 生成 ----------

    /**
     * 发送一次生成/编辑。校验同步返回，落库与请求跑在仓库 scope 上。
     * [userImages] 只含**用户自己传的**图；上一轮生成结果由仓库静默强制带入为图 1。
     */
    fun send(
        conversationId: String,
        prompt: String,
        userImages: List<Attachment> = emptyList(),
        model: String,
        providerId: String,
    ): ImageSendResult {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return ImageSendResult.Rejected("请输入提示词")
        val settings = settingsNow.value ?: return ImageSendResult.Rejected("生图配置还没准备好，请重试")
        val turn = resolveTurn(
            model.ifBlank { settings.resolvedImageWorkspaceModel },
            providerId.ifBlank { settings.resolvedImageWorkspaceProvider },
        )
        rejectTurn(turn, userImages, settings)?.let { return ImageSendResult.Rejected(it) }
        val resolved = turn ?: return ImageSendResult.Rejected("当前供应商不支持生图")
        val job = turns.startIfIdle(conversationId) {
            launchTurn(conversationId, "生成失败") {
                startGeneration(conversationId, trimmed, userImages, resolved, insertUser = true)
            }
        }
        return if (job == null) ImageSendResult.Busy else ImageSendResult.Started
    }

    /** 重新生成某条助手结果：用它的用户提示词与输入图重跑（不新增用户消息）。 */
    fun regenerate(conversationId: String, assistantMessageId: String): ImageSendResult {
        turns.restart(conversationId) {
            launchTurn(conversationId, "重新生成失败") {
                val target = db.messageDao().getById(assistantMessageId)?.toModel()
                    ?: return@launchTurn null
                if (target.role != Role.ASSISTANT) {
                    _errors.value = ImageRepoError(conversationId, "只能重新生成生成结果")
                    return@launchTurn null
                }
                val conversation = db.conversationDao().getById(conversationId)
                val all = db.messageDao().getByConversation(conversationId)
                val user = all.lastOrNull { it.role == Role.USER.name && it.seq < target.seq }
                if (user == null) {
                    _errors.value = ImageRepoError(conversationId, "找不到对应的提示词，无法重新生成")
                    return@launchTurn null
                }
                val settings = settingsRepository.settings.first()
                val turn = resolveTurn(
                    conversation?.model?.takeIf { it.isNotBlank() } ?: settings.resolvedImageWorkspaceModel,
                    conversation?.providerId?.takeIf { it.isNotBlank() }
                        ?: settings.resolvedImageWorkspaceProvider,
                )
                val userImages = AttachmentCodec.decode(user.attachments)
                rejectTurn(turn, userImages, settings)?.let { reason ->
                    _errors.value = ImageRepoError(conversationId, reason)
                    return@launchTurn null
                }
                db.messageDao().deleteById(assistantMessageId)
                db.messageDao().deleteFrom(conversationId, target.seq + 1)
                startGeneration(
                    conversationId = conversationId,
                    prompt = user.content,
                    // 用户行只存了用户自己的图；「上一张」由 startGeneration 按规则实时推导。
                    userImages = userImages,
                    turn = turn ?: return@launchTurn null,
                    insertUser = false,
                )
            }
        }
        return ImageSendResult.Started
    }

    fun stop(conversationId: String) = turns.cancel(conversationId)

    /** 停止所有生图回合（前台服务超时被拆、进程即将失去保活时）。 */
    fun stopAll() = turns.cancelAll()

    /**
     * 提示词改写：把用户的抽象描述改写成画面式描述，流式吐给 UI 写回输入框。
     * 只用文本区的对话模型，不用图像供应商上的模型名。
     */
    fun rewritePrompt(text: String): Flow<String> = settingsRepository.settings.flatMapLatest { current ->
        val config = current.requireTextRewriteConfig()
        chatApi.stream(config, PromptRewriter.request(text))
            .mapNotNull { event -> (event as? ChatStreamEvent.Delta)?.content }
    }

    // ---------- 内部 ----------

    /**
     * 一次发送用的后端。供应商以调用方传入的 [providerId] 为准（已知的图像后端），
     * 不再在落库时用模型名另猜一次。
     */
    private fun resolveTurn(model: String, providerId: String): ResolvedImageTurn? {
        val selection = ImageModelCatalog.selection(
            backends,
            _remoteModels.value,
            model,
            providerId.takeIf { it.isNotBlank() },
        )
        val backend = backends.firstOrNull { it.providerId == selection.providerId } ?: return null
        return ResolvedImageTurn(
            backend = backend,
            providerId = selection.providerId,
            model = model.trim(),
            acceptsImageInput = selection.acceptsImageInput,
        )
    }

    /** 落库前的拒绝原因。通过时返回 null。 */
    private fun rejectTurn(
        turn: ResolvedImageTurn?,
        userImages: List<Attachment>,
        settings: ChatSettings,
    ): String? {
        if (turn == null) return "当前供应商不支持生图"
        if (!turn.acceptsImageInput && userImages.isNotEmpty()) return "当前模型只支持文生图"
        if (userImages.size > turn.backend.maxInputImages) {
            return "最多只能输入 ${turn.backend.maxInputImages} 张图片"
        }
        val key = settings.providers[turn.providerId]?.apiKey
        if (key.isNullOrBlank()) return ImageModelCatalog.missingKey(turn.providerId)
        return null
    }

    private suspend fun startGeneration(
        conversationId: String,
        prompt: String,
        userImages: List<Attachment>,
        turn: ResolvedImageTurn,
        insertUser: Boolean,
    ): MessageStatus? {
        val conversation = db.conversationDao().getById(conversationId)
        if (conversation == null) {
            _errors.value = ImageRepoError(conversationId, "会话不存在")
            return null
        }
        val settings = settingsRepository.settings.first()
        val imageModel = turn.model.ifBlank { settings.resolvedImageWorkspaceModel }
        if (conversation.providerId != turn.providerId || conversation.model != imageModel) {
            db.conversationDao().updateProviderAndModel(conversationId, turn.providerId, imageModel, nowMs())
        }
        // 静默强制带入上一轮生成结果作为图 1。不吃参考图的模型不带。
        val previous = if (turn.acceptsImageInput) {
            ImageInputs.previousImage(
                db.messageDao().getByConversation(conversationId).map { it.toModel() },
            )
        } else {
            null
        }
        val wireInputs = if (turn.acceptsImageInput) {
            ImageInputs.wireInputs(previous, userImages, turn.backend.maxInputImages)
        } else {
            emptyList()
        }
        val timestamp = nowMs()

        if (insertUser) {
            db.messageDao().upsert(
                MessageEntity(
                    id = newId(),
                    conversationId = conversationId,
                    role = Role.USER.name,
                    content = prompt,
                    status = MessageStatus.COMPLETE.name,
                    errorMessage = null,
                    reasoningContent = null,
                    seq = db.messageDao().nextSeq(conversationId),
                    model = imageModel,
                    promptTokens = null,
                    completionTokens = null,
                    // 只落用户自己传的图；自动带入的上一张不进这里（历史里看不到）。
                    attachments = AttachmentCodec.encode(userImages),
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            )
            if (conversation.messageCount == 0 && conversation.title == ConversationTitle.FALLBACK) {
                db.conversationDao().rename(
                    conversationId,
                    ConversationTitle.fromFirstMessage(prompt),
                    timestamp,
                )
            }
        }

        // 助手占位：生成中用 STREAMING，UI 显示转圈。
        val assistantId = newId()
        db.messageDao().upsert(
            MessageEntity(
                id = assistantId,
                conversationId = conversationId,
                role = Role.ASSISTANT.name,
                content = "",
                status = MessageStatus.STREAMING.name,
                errorMessage = null,
                reasoningContent = null,
                seq = db.messageDao().nextSeq(conversationId),
                model = imageModel,
                promptTokens = null,
                completionTokens = null,
                createdAt = timestamp,
                updatedAt = timestamp,
            ),
        )
        refreshSummary(conversationId)

        val config = settings.toChatConfig(turn.providerId)

        val dataUrls = withContext(Dispatchers.IO) {
            wireInputs.mapNotNull { attachment -> attachmentStore.toRequestImage(attachment, null)?.dataUrl }
        }
        val request = ImageGenerateRequest(
            model = imageModel,
            prompt = prompt,
            images = dataUrls,
            promptExtend = settings.imagePromptExtend,
            openAi = settings.openAiImage,
            qwen = settings.qwenImage,
        )

        try {
            val images = turn.backend.generate(config, request)
            val attachments = images.map { bytes ->
                attachmentStore.importGeneratedImage(conversationId, bytes)
            }
            withContext(NonCancellable) {
                finalizeAssistant(assistantId, MessageStatus.COMPLETE, null)
                db.messageDao().updateAttachments(assistantId, AttachmentCodec.encode(attachments), nowMs())
                refreshSummary(conversationId)
            }
            return MessageStatus.COMPLETE
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                finalizeAssistant(assistantId, MessageStatus.CANCELLED, "已停止生成")
                refreshSummary(conversationId)
            }
            throw cancelled
        } catch (t: Throwable) {
            Log.e(TAG, "生图失败", t)
            withContext(NonCancellable) {
                finalizeAssistant(
                    assistantId,
                    MessageStatus.ERROR,
                    t.message?.takeIf { it.isNotBlank() } ?: "生成失败",
                )
                refreshSummary(conversationId)
            }
            return MessageStatus.ERROR
        }
    }

    private suspend fun finalizeAssistant(
        id: String,
        status: MessageStatus,
        errorMessage: String?,
    ) {
        db.messageDao().finalize(
            id = id,
            content = "",
            reasoning = null,
            citations = null,
            status = status.name,
            errorMessage = errorMessage,
            promptTokens = null,
            completionTokens = null,
            reasoningTokens = null,
            cachedTokens = null,
            reasoningMs = null,
            updatedAt = nowMs(),
        )
    }

    private fun launchTurn(
        conversationId: String,
        errorFallback: String,
        block: suspend () -> MessageStatus?,
    ): Job {
        val token = lamps.begin(conversationId)
        turnForeground.acquire()
        return scope.launch {
            var status: MessageStatus? = null
            try {
                status = block()
            } catch (cancelled: CancellationException) {
                status = MessageStatus.CANCELLED
                throw cancelled
            } catch (t: Throwable) {
                status = MessageStatus.ERROR
                reportError(conversationId, t, errorFallback)
            } finally {
                lamps.finish(conversationId, token, status)
                turnForeground.release()
            }
        }
    }

    private fun reportError(conversationId: String?, t: Throwable, fallback: String) {
        Log.e(TAG, "生图后台操作失败", t)
        _errors.value = ImageRepoError(
            conversationId = conversationId,
            message = t.message?.takeIf { it.isNotBlank() } ?: fallback,
        )
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
