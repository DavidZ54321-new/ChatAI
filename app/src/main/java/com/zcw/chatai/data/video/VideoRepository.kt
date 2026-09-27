package com.zcw.chatai.data.video

import android.util.Log
import com.zcw.chatai.data.ConversationLamps
import com.zcw.chatai.data.db.AppDatabase
import com.zcw.chatai.data.db.AttachmentCodec
import com.zcw.chatai.data.db.ConversationEntity
import com.zcw.chatai.data.db.MessageEntity
import com.zcw.chatai.data.db.VideoTaskEntity
import com.zcw.chatai.data.db.toModel
import com.zcw.chatai.data.media.AttachmentStore
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.ConversationKind
import com.zcw.chatai.data.model.ConversationTitle
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.net.ApiErrorMapper
import com.zcw.chatai.data.net.ChatApi
import com.zcw.chatai.data.net.ChatApiException
import com.zcw.chatai.data.net.ChatStreamEvent
import com.zcw.chatai.data.net.DashScopeVideoClient
import com.zcw.chatai.data.net.VideoGenPayload
import com.zcw.chatai.data.net.VideoSynthesisOutcome
import com.zcw.chatai.data.net.VideoTaskOutcome
import com.zcw.chatai.data.prefs.SettingsRepository
import com.zcw.chatai.data.prefs.toChatConfig
import com.zcw.chatai.data.provider.ProviderCatalog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "VideoRepository"

/** DashScope 错误码文案里的主体名（避免视频失败时冒出「生图」字样）。 */
private const val SUBJECT = "视频生成"

sealed interface VideoSendResult {
    data object Started : VideoSendResult

    /** 该会话已有任务在跑：静默忽略。 */
    data object Busy : VideoSendResult

    data class Rejected(val reason: String) : VideoSendResult
}

/** 后台失败：带会话 id，UI 只对当前会话显示。 */
data class VideoRepoError(
    val conversationId: String?,
    val message: String,
)

/** 一次发送的输入（UI → 仓库），把散落的参数收成一个对象。 */
data class VideoDraft(
    val mode: VideoMode,
    val prompt: String,
    val model: String,
    val resolution: String = "720P",
    val ratio: String = "adaptive",
    val duration: Int = -1,
    val audio: Boolean = true,
    /** 是否让服务端做提示词智能改写（DashScope `prompt_extend`，来自设置）。 */
    val promptExtend: Boolean = true,
    val firstFrame: Attachment? = null,
    val lastFrame: Attachment? = null,
    val references: List<Attachment> = emptyList(),
)

/**
 * 视频生成的唯一业务入口（与 `ChatRepository` / `ImageRepository` 平级）。
 *
 * 与生图不同，这是**异步任务**：落库一条 `video_tasks` + 助手占位，然后交给 WorkManager
 * 提交/轮询/下载。进程被杀后任务仍在（远端在跑、本地有任务行），冷启动 [resumePending] 对账续跑。
 * 提交与轮询的推进逻辑都在 [advance] 里（suspend，Worker 调用），本仓库不自己持有长跑协程。
 */
class VideoRepository(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val client: DashScopeVideoClient,
    private val attachmentStore: AttachmentStore,
    /** 提示词改写用它（走当前对话模型，与视频后端无关）。 */
    private val chatApi: ChatApi,
    private val scheduler: VideoTaskScheduler = VideoTaskScheduler.NoOp,
    /** 与对话/生图共用。任务开始/结束时上报，列表圆点自动可用。 */
    private val lamps: ConversationLamps = ConversationLamps(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /** taskId → 状态灯 token（进程内；被新任务替换的旧 token 由 lamps 忽略）。 */
    private val lampTokens = ConcurrentHashMap<String, Long>()

    /**
     * 正在提交的任务所对应的会话：`send` 是同步返回、落库在 scope 上异步完成，
     * 而 [busyConversations] 要等数据库 Flow 发出才有值——这中间的空窗用它挡住连点。
     */
    private val starting = ConcurrentHashMap.newKeySet<String>()

    private val _errors = MutableStateFlow<VideoRepoError?>(null)

    val errors: StateFlow<VideoRepoError?> = _errors.asStateFlow()

    fun consumeError() {
        _errors.value = null
    }

    /** 有未到终态任务的会话集合（UI 用它决定发送/停止键）。 */
    val busyConversations: StateFlow<Set<String>> = db.videoTaskDao().observeActive(VideoTaskStatus.ACTIVE)
        .map { tasks -> tasks.mapTo(HashSet()) { it.conversationId } }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    // ---------- 观察 ----------

    fun observeConversations(): Flow<List<Conversation>> =
        db.conversationDao().observeByKind(ConversationKind.VIDEO.name)
            .map { list -> list.map { it.toModel() } }

    fun observeConversation(id: String): Flow<Conversation?> =
        db.conversationDao().observeById(id).map { it?.toModel() }

    fun observeMessages(conversationId: String): Flow<List<Message>> =
        db.messageDao().observeByConversation(conversationId)
            .conflate()
            .map { list -> list.map { it.toModel() } }
            .flowOn(Dispatchers.Default)

    fun observeTasks(conversationId: String): Flow<List<VideoTask>> =
        db.videoTaskDao().observeByConversation(conversationId)
            .map { list -> list.map { it.toModel() } }

    // ---------- 会话 ----------

    suspend fun createConversation(model: String? = null): String {
        val settings = settingsRepository.settings.first()
        val id = newId()
        val timestamp = nowMs()
        db.conversationDao().upsert(
            ConversationEntity(
                id = id,
                title = ConversationTitle.FALLBACK,
                model = model?.takeIf { it.isNotBlank() } ?: settings.videoGenModel,
                systemPrompt = null,
                createdAt = timestamp,
                updatedAt = timestamp,
                lastMessagePreview = "",
                messageCount = 0,
                isPinned = false,
                // 视频生成后端固定是通义千问（DashScope 异步接口）。
                providerId = ProviderCatalog.QWEN,
                personaId = "",
                webSearchEnabled = false,
                kind = ConversationKind.VIDEO.name,
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

    /** 删除会话：先停掉在跑的任务，再删库（任务行靠外键级联清掉）+ 附件目录。 */
    fun deleteConversation(id: String) {
        scope.launch {
            runCatching {
                cancelTasksFor(id)
                attachmentStore.deleteConversation(id)
                db.conversationDao().delete(id)
            }.onFailure { reportError(id, it, "删除会话失败") }
        }
    }

    /**
     * 删除消息：删用户轮时连带它紧邻的助手轮。跑路任务一并取消并删行（不删物理文件，
     * 孤儿文件由 `ChatRepository.sweepOrphanAttachments` 回收）。
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
                victims.forEach { victim ->
                    db.videoTaskDao().getByMessage(victim.id)?.let { task ->
                        scheduler.cancel(task.id)
                        lampTokens.remove(task.id)?.let { token ->
                            lamps.finish(message.conversationId, token, MessageStatus.CANCELLED)
                        }
                    }
                }
                db.messageDao().deleteByIds(victims.map { it.id })
                db.videoTaskDao().deleteByMessageIds(victims.map { it.id })
                refreshSummary(message.conversationId)
            }.onFailure { reportError(conversationId, it, "删除消息失败") }
        }
    }

    // ---------- 生成 ----------

    /** 提交一次生成。校验同步返回，落库 + 入队跑在仓库 scope 上。 */
    fun send(conversationId: String, draft: VideoDraft): VideoSendResult {
        val prompt = draft.prompt.trim()
        val error = VideoInputs.validate(
            mode = draft.mode,
            prompt = prompt,
            hasFirstFrame = draft.firstFrame != null,
            hasLastFrame = draft.lastFrame != null,
            referenceCount = draft.references.size,
        )
        if (error != null) return VideoSendResult.Rejected(error)
        if (busyConversations.value.contains(conversationId) || !starting.add(conversationId)) {
            return VideoSendResult.Busy
        }
        val request = draft.toRequest(prompt)
        scope.launch {
            try {
                createTask(
                    conversationId = conversationId,
                    request = request,
                    userPrompt = prompt,
                    userInputs = draft.inputsInOrder(),
                    insertUser = true,
                )
            } catch (t: Throwable) {
                reportError(conversationId, t, "提交失败")
            } finally {
                starting.remove(conversationId)
            }
        }
        return VideoSendResult.Started
    }

    /** 重新生成某条助手结果：用它的原始任务参数重跑（不新增用户消息）。 */
    fun regenerate(conversationId: String, assistantMessageId: String): VideoSendResult {
        if (busyConversations.value.contains(conversationId) || !starting.add(conversationId)) {
            return VideoSendResult.Busy
        }
        scope.launch {
            try {
                val assistant = db.messageDao().getById(assistantMessageId)?.toModel()
                if (assistant == null || assistant.role != Role.ASSISTANT) {
                    _errors.value = VideoRepoError(conversationId, "只能重新生成生成结果")
                    return@launch
                }
                val task = db.videoTaskDao().getByMessage(assistantMessageId)
                val request = task?.let { VideoGenRequestCodec.decode(it.requestJson) }
                if (task == null || request == null) {
                    _errors.value = VideoRepoError(conversationId, "找不到原始任务参数，无法重新生成")
                    return@launch
                }
                scheduler.cancel(task.id)
                lampTokens.remove(task.id)?.let { token ->
                    lamps.finish(conversationId, token, MessageStatus.CANCELLED)
                }
                db.messageDao().deleteById(assistantMessageId)
                db.videoTaskDao().deleteById(task.id)
                db.messageDao().deleteFrom(conversationId, assistant.seq + 1)
                createTask(
                    conversationId = conversationId,
                    request = request,
                    userPrompt = request.prompt,
                    userInputs = emptyList(),
                    insertUser = false,
                )
            } catch (t: Throwable) {
                reportError(conversationId, t, "重新生成失败")
            } finally {
                starting.remove(conversationId)
            }
        }
        return VideoSendResult.Started
    }

    /** 停止该会话所有在跑的任务。 */
    fun stop(conversationId: String) {
        scope.launch {
            runCatching { cancelTasksFor(conversationId) }
                .onFailure { reportError(conversationId, it, "停止失败") }
        }
    }

    // ---------- Worker 推进 ----------

    /**
     * 推进一条任务一步：未提交就提交，已提交就查询；命中终态则落库定稿。
     * Worker 反复调用直到 [AdvanceOutcome] 不是 [AdvanceOutcome.CONTINUE]。
     * 网络异常向上抛，由 Worker 决定退避重试。
     */
    suspend fun advance(taskId: String): AdvanceOutcome {
        val entity = db.videoTaskDao().getById(taskId) ?: return AdvanceOutcome.DONE
        val status = VideoTaskStatus.fromString(entity.status)
        if (!status.isActive) {
            return if (status == VideoTaskStatus.SUCCEEDED) AdvanceOutcome.DONE else AdvanceOutcome.FAILED
        }
        val request = VideoGenRequestCodec.decode(entity.requestJson)
        if (request == null) {
            failTask(entity, "任务参数已损坏，请重新生成")
            return AdvanceOutcome.FAILED
        }
        val settings = settingsRepository.settings.first()
        val entry = settings.providers[ProviderCatalog.QWEN]
        if (entry == null || entry.apiKey.isBlank()) {
            failTask(entity, "视频生成需要通义千问的 API Key，请到设置里配置")
            return AdvanceOutcome.FAILED
        }
        val config = settings.toChatConfig(ProviderCatalog.QWEN)

        var remoteId = entity.remoteTaskId
        if (remoteId.isNullOrBlank()) {
            // 读本地图 + base64 是阻塞 IO，切到 IO 线程（advance 跑在 Worker 的 Default 线程）。
            val payload = withContext(Dispatchers.IO) {
                VideoGenPayload.build(request) { ref ->
                    attachmentStore.dataUrlFor(ref.relativePath, ref.mimeType)
                }
            }
            when (val outcome = client.submit(config, payload)) {
                is VideoSynthesisOutcome.Submitted -> {
                    remoteId = outcome.taskId
                    // 远端任务已创建（可能已计费）：任务 id 必须落库，取消也不能丢，
                    // 否则重排后会拿不到 id 而再提交一次 → 重复生成。
                    withContext(NonCancellable) {
                        db.videoTaskDao().updateRemote(
                            taskId,
                            outcome.taskId,
                            VideoTaskStatus.SUBMITTED.name,
                            nowMs(),
                        )
                    }
                }

                is VideoSynthesisOutcome.Failure -> {
                    failTask(entity, ApiErrorMapper.dashScope(outcome.code, outcome.message, SUBJECT))
                    return AdvanceOutcome.FAILED
                }

                // 2xx 但结构无法解析：继续重试只会在预算内反复提交（可能重复计费），直接判失败。
                VideoSynthesisOutcome.Malformed -> {
                    failTask(entity, "服务端返回了无法解析的响应，请重试")
                    return AdvanceOutcome.FAILED
                }
            }
        }
        // 走到这里 remoteId 必定非空（未提交就提交、提交失败已返回），直接查。
        return when (val outcome = client.query(config, remoteId)) {
            VideoTaskOutcome.Pending -> {
                markStatus(entity, VideoTaskStatus.SUBMITTED)
                AdvanceOutcome.CONTINUE
            }

            VideoTaskOutcome.Running -> {
                markStatus(entity, VideoTaskStatus.RUNNING)
                AdvanceOutcome.CONTINUE
            }

            is VideoTaskOutcome.Succeeded -> {
                try {
                    succeedTask(entity, outcome.url)
                    AdvanceOutcome.DONE
                } catch (t: Throwable) {
                    // 视频 URL 只保留 24 小时；403/404 基本就是过期/被清除，重试也没用。
                    if (isExpiredDownload(t)) {
                        failTask(entity, "视频链接已过期（仅保留 24 小时），请重新生成")
                        AdvanceOutcome.FAILED
                    } else {
                        throw t
                    }
                }
            }

            is VideoTaskOutcome.Failed -> {
                failTask(entity, ApiErrorMapper.dashScope(outcome.code, outcome.message, SUBJECT))
                AdvanceOutcome.FAILED
            }

            VideoTaskOutcome.Canceled -> {
                cancelTask(entity)
                AdvanceOutcome.DONE
            }

            // task_id 超过 24 小时有效期：官方语义就是「查不到了」，如实报错并让用户重生成。
            VideoTaskOutcome.Expired -> {
                failTask(entity, "任务已过期（任务 ID 与视频链接仅保留 24 小时），请重新生成")
                AdvanceOutcome.FAILED
            }

            VideoTaskOutcome.Malformed -> AdvanceOutcome.CONTINUE
        }
    }

    /** 下载阶段的 403/404：几乎只可能是 OSS 链接过期（24 小时）或被清除。 */
    private fun isExpiredDownload(t: Throwable): Boolean {
        if (t !is ChatApiException) return false
        val message = t.message ?: return false
        return "HTTP 403" in message || "HTTP 404" in message
    }

    /**
     * 冷启动对账：
     * 1. 未到终态的任务重新入队（进程被杀后 WorkManager 可能已经丢了它们）；
     * 2. 修复「停在生成中、却没有任务行」的助手消息（如导入备份后遗留），标为中断可重试。
     *
     * 删会话时任务行随外键级联消失，指向它们的残留 Worker 会因 `advance` 取不到行而直接收工，
     * 所以这里不需要（也没有）显式取消。入队用 `KEEP`，不会打断正在跑的 Worker。
     */
    suspend fun resumePending() {
        val active = db.videoTaskDao().getActive(VideoTaskStatus.ACTIVE)
        for (task in active) {
            lampTokens.putIfAbsent(task.id, lamps.begin(task.conversationId))
            scheduler.enqueue(task.id)
        }
        val videoConversations = db.conversationDao().getAll()
            .filter { it.kind == ConversationKind.VIDEO.name }
        for (conversation in videoConversations) {
            val messages = db.messageDao().getByConversation(conversation.id)
            var repaired = false
            for (message in messages) {
                if (message.role != Role.ASSISTANT.name) continue
                if (message.status != MessageStatus.STREAMING.name) continue
                if (db.videoTaskDao().getByMessage(message.id) != null) continue
                db.messageDao().finalize(
                    id = message.id,
                    content = "",
                    reasoning = null,
                    status = MessageStatus.ERROR.name,
                    errorMessage = "生成被中断，请重新生成",
                    promptTokens = null,
                    completionTokens = null,
                    reasoningTokens = null,
                    cachedTokens = null,
                    reasoningMs = null,
                    updatedAt = nowMs(),
                )
                repaired = true
            }
            if (repaired) refreshSummary(conversation.id)
        }
    }

    /** Worker 重试用尽后的兜底：把任务标成失败，避免无限重排。 */
    suspend fun giveUp(taskId: String, reason: String) {
        val entity = db.videoTaskDao().getById(taskId) ?: return
        if (!VideoTaskStatus.fromString(entity.status).isActive) return
        failTask(entity, reason)
    }

    // ---------- 提示词改写 ----------

    fun rewritePrompt(text: String): Flow<String> = settingsRepository.settings.flatMapLatest { current ->
        val providerId = when {
            current.providers[current.activeProviderId]?.apiKey?.isNotBlank() == true ->
                current.activeProviderId
            current.providers[ProviderCatalog.QWEN]?.apiKey?.isNotBlank() == true -> ProviderCatalog.QWEN
            else -> current.activeProviderId
        }
        val config = current.toChatConfig(providerId).copy(
            enabledTools = emptyList(),
            webSearchEnabled = false,
            includeEnvTime = false,
        )
        chatApi.stream(config, VideoPromptRewriter.request(text))
            .mapNotNull { event -> (event as? ChatStreamEvent.Delta)?.content }
    }

    // ---------- 内部 ----------

    private suspend fun createTask(
        conversationId: String,
        request: VideoGenRequest,
        userPrompt: String,
        userInputs: List<Attachment>,
        insertUser: Boolean,
    ) {
        val conversation = db.conversationDao().getById(conversationId)
        if (conversation == null) {
            _errors.value = VideoRepoError(conversationId, "会话不存在")
            return
        }
        val timestamp = nowMs()
        if (insertUser) {
            db.messageDao().upsert(
                MessageEntity(
                    id = newId(),
                    conversationId = conversationId,
                    role = Role.USER.name,
                    content = userPrompt,
                    status = MessageStatus.COMPLETE.name,
                    errorMessage = null,
                    reasoningContent = null,
                    seq = db.messageDao().nextSeq(conversationId),
                    model = request.model,
                    promptTokens = null,
                    completionTokens = null,
                    attachments = AttachmentCodec.encode(userInputs),
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            )
            if (conversation.messageCount == 0 && conversation.title == ConversationTitle.FALLBACK) {
                db.conversationDao().rename(
                    conversationId,
                    ConversationTitle.fromFirstMessage(userPrompt.ifBlank { "新视频" }),
                    timestamp,
                )
            }
        }

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
                model = request.model,
                promptTokens = null,
                completionTokens = null,
                createdAt = timestamp,
                updatedAt = timestamp,
            ),
        )
        val taskId = newId()
        db.videoTaskDao().upsert(
            VideoTaskEntity(
                id = taskId,
                conversationId = conversationId,
                messageId = assistantId,
                remoteTaskId = null,
                model = request.model,
                mode = request.mode.name,
                requestJson = VideoGenRequestCodec.encode(request),
                status = VideoTaskStatus.PENDING.name,
                errorMessage = null,
                createdAt = timestamp,
                updatedAt = timestamp,
            ),
        )
        refreshSummary(conversationId)
        lampTokens[taskId] = lamps.begin(conversationId)
        scheduler.enqueue(taskId)
    }

    private suspend fun succeedTask(task: VideoTaskEntity, url: String) {
        // 下载走文件（流式落盘，不进堆），再移进附件目录。
        val temp = attachmentStore.tempVideoFile(task.id)
        client.downloadToFile(url, temp)
        val attachment = attachmentStore.importGeneratedVideoFile(task.conversationId, temp)
        withContext(NonCancellable) {
            db.messageDao().finalize(
                id = task.messageId,
                content = "",
                reasoning = null,
                status = MessageStatus.COMPLETE.name,
                errorMessage = null,
                promptTokens = null,
                completionTokens = null,
                reasoningTokens = null,
                cachedTokens = null,
                reasoningMs = null,
                updatedAt = nowMs(),
            )
            db.messageDao().updateAttachments(
                task.messageId,
                AttachmentCodec.encode(listOf(attachment)),
                nowMs(),
            )
            db.videoTaskDao().updateStatus(task.id, VideoTaskStatus.SUCCEEDED.name, null, nowMs())
            finishLamp(task, MessageStatus.COMPLETE)
            refreshSummary(task.conversationId)
        }
    }

    private suspend fun failTask(task: VideoTaskEntity, message: String) {
        withContext(NonCancellable) {
            db.messageDao().finalize(
                id = task.messageId,
                content = "",
                reasoning = null,
                status = MessageStatus.ERROR.name,
                errorMessage = message,
                promptTokens = null,
                completionTokens = null,
                reasoningTokens = null,
                cachedTokens = null,
                reasoningMs = null,
                updatedAt = nowMs(),
            )
            db.videoTaskDao().updateStatus(task.id, VideoTaskStatus.FAILED.name, message, nowMs())
            finishLamp(task, MessageStatus.ERROR)
            refreshSummary(task.conversationId)
        }
    }

    private suspend fun cancelTask(task: VideoTaskEntity) {
        withContext(NonCancellable) {
            db.messageDao().finalize(
                id = task.messageId,
                content = "",
                reasoning = null,
                status = MessageStatus.CANCELLED.name,
                errorMessage = "已取消",
                promptTokens = null,
                completionTokens = null,
                reasoningTokens = null,
                cachedTokens = null,
                reasoningMs = null,
                updatedAt = nowMs(),
            )
            db.videoTaskDao().updateStatus(task.id, VideoTaskStatus.CANCELLED.name, null, nowMs())
            finishLamp(task, MessageStatus.CANCELLED)
            refreshSummary(task.conversationId)
        }
    }

    private suspend fun markStatus(task: VideoTaskEntity, target: VideoTaskStatus) {
        if (VideoTaskStatus.fromString(task.status) == target) return
        db.videoTaskDao().updateStatus(task.id, target.name, null, nowMs())
    }

    private fun finishLamp(task: VideoTaskEntity, status: MessageStatus) {
        lampTokens.remove(task.id)?.let { token -> lamps.finish(task.conversationId, token, status) }
    }

    private suspend fun cancelTasksFor(conversationId: String) {
        val active = db.videoTaskDao().getActiveForConversation(conversationId, VideoTaskStatus.ACTIVE)
        for (task in active) {
            scheduler.cancel(task.id)
            cancelTask(task)
        }
    }

    private fun reportError(conversationId: String?, t: Throwable, fallback: String) {
        Log.e(TAG, "视频后台操作失败", t)
        _errors.value = VideoRepoError(
            conversationId = conversationId,
            message = t.message?.takeIf { it.isNotBlank() } ?: fallback,
        )
    }

    private suspend fun refreshSummary(conversationId: String) {
        val messages = db.messageDao().getByConversation(conversationId)
        val preview = messages.lastOrNull()?.toModel()?.let { message ->
            when {
                message.content.isNotBlank() -> ConversationTitle.preview(message.content)
                message.attachments.isNotEmpty() -> "［视频］"
                message.status == MessageStatus.STREAMING -> "［生成中］"
                message.status == MessageStatus.ERROR -> message.errorMessage?.let { "［失败］$it" } ?: "［失败］"
                else -> ""
            }
        }.orEmpty()
        db.conversationDao().updateSummary(conversationId, preview, messages.size, nowMs())
    }

    private fun VideoDraft.toRequest(prompt: String): VideoGenRequest = VideoGenRequest(
        mode = mode,
        prompt = prompt,
        model = model.trim().ifEmpty { VideoModels.DEFAULT },
        resolution = resolution,
        ratio = ratio,
        duration = duration,
        audio = audio,
        watermark = false,
        promptExtend = promptExtend,
        firstFrame = firstFrame?.toRef(),
        lastFrame = lastFrame?.toRef(),
        references = references.map { it.toRef() },
    )

    private fun VideoDraft.inputsInOrder(): List<Attachment> = when (mode) {
        VideoMode.T2V -> emptyList()
        VideoMode.I2V -> listOfNotNull(firstFrame, lastFrame)
        VideoMode.R2V -> references
    }

    private fun Attachment.toRef(): VideoInputRef = VideoInputRef(
        id = id,
        relativePath = relativePath,
        mimeType = mimeType,
    )
}
