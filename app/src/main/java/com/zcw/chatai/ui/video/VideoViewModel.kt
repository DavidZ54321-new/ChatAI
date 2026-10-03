package com.zcw.chatai.ui.video

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zcw.chatai.data.media.AttachmentStore
import com.zcw.chatai.data.prefs.WorkspaceSlot
import com.zcw.chatai.ui.common.launchPromptRewrite
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.prefs.SettingsRepository
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.video.VideoDraft
import com.zcw.chatai.ui.common.ConversationSearch
import com.zcw.chatai.data.video.VideoMode
import com.zcw.chatai.data.video.VideoRepository
import com.zcw.chatai.data.video.VideoSendResult
import com.zcw.chatai.data.video.VideoTask
import com.zcw.chatai.data.video.VideoTaskStatus
import com.zcw.chatai.ui.chat.PendingAttachment
import com.zcw.chatai.ui.chat.toChatMessageItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VideoViewModel(
    private val repository: VideoRepository,
    private val attachmentStore: AttachmentStore,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val conversationId = MutableStateFlow<String?>(null)
    private val input = MutableStateFlow("")
    private val pending = MutableStateFlow<List<PendingAttachment>>(emptyList())
    private val notice = MutableStateFlow<String?>(null)
    private val rewriting = MutableStateFlow(false)
    private val params = MutableStateFlow(VideoParams())

    private val conversationLock = Mutex()

    private var rewriteJob: Job? = null

    private val listSearch = ConversationSearch(
        all = repository.observeConversations(),
        search = repository::searchConversations,
        scope = viewModelScope,
    )

    val searchQuery: StateFlow<String> = listSearch.searchQuery

    val searchResults: StateFlow<List<Conversation>> = listSearch.results

    private val conversationFlow = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(null) else repository.observeConversation(id)
    }

    private val messagesFlow = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.observeMessages(id)
    }

    private val tasksFlow = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.observeTasks(id)
    }

    private val composerFlow = combine(input, pending, notice, rewriting, params) { text, pend, note, busy, params ->
        ComposerSnapshot(text, pend, note, busy, params)
    }

    private val coreFlow = combine(
        conversationId,
        conversationFlow,
        messagesFlow,
        tasksFlow,
    ) { id, conversation, messages, tasks ->
        CoreSnapshot(id, conversation, messages, tasks)
    }

    val state: StateFlow<VideoUiState> = combine(
        coreFlow,
        repository.busyConversations,
        composerFlow,
        settingsRepository.settings,
    ) { core, busy, composer, settings ->
        VideoUiState(
            conversationId = core.id,
            title = core.conversation?.title ?: "新视频",
            model = core.conversation?.model?.takeIf { it.isNotBlank() }
                ?: settings.resolvedVideoWorkspaceModel,
            params = composer.params,
            messages = core.messages.map { it.toChatMessageItem(attachmentStore) },
            taskStatuses = core.tasks.associate { it.messageId to it.status },
            input = composer.input,
            pending = composer.pending,
            isBusy = core.id != null && busy.contains(core.id),
            rewriting = composer.rewriting,
            promptExtend = settings.videoPromptExtend,
            notice = composer.notice,
            available = settings.providers[ProviderCatalog.QWEN]?.apiKey?.isNotBlank() == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoUiState())

    init {
        viewModelScope.launch {
            repository.errors.collect { error ->
                if (error == null) return@collect
                val current = conversationId.value
                if (error.conversationId == null || error.conversationId == current) {
                    notice.value = error.message
                }
                repository.consumeError()
            }
        }
    }

    fun setInput(text: String) {
        input.value = text
    }

    /** 会话列表页搜索：关键词变化时 [searchResults] 自动重算。 */
    fun setSearchQuery(query: String) = listSearch.setQuery(query)

    fun consumeNotice() {
        notice.value = null
    }

    fun setMode(mode: VideoMode) {
        if (params.value.mode == mode) return
        // 换模式即换输入语义（首帧/尾帧 vs 参考图），旧的选定图直接丢掉。
        discardPending()
        // 文生没有输入素材可「自适应」，改成一个服务端一定接受的比例。
        val ratio = if (mode == VideoMode.T2V && params.value.ratio == "adaptive") "16:9" else params.value.ratio
        params.value = params.value.copy(mode = mode, ratio = ratio)
    }

    fun setResolution(value: String) {
        params.value = params.value.copy(resolution = value)
    }

    fun setRatio(value: String) {
        params.value = params.value.copy(ratio = value)
    }

    fun setDuration(value: Int) {
        params.value = params.value.copy(duration = value)
    }

    fun setAudio(enabled: Boolean) {
        params.value = params.value.copy(audio = enabled)
    }

    fun addImage(uri: Uri) {
        val limit = state.value.params.imageLimit
        if (limit <= 0) {
            notice.value = "文生视频不需要参考图，先切到「图生」或「参考」模式"
            return
        }
        viewModelScope.launch {
            if (pending.value.size >= limit) {
                notice.value = "本轮最多还能再选 ${limit - pending.value.size} 张图片"
                return@launch
            }
            val id = ensureConversation()
            runCatching { attachmentStore.importImage(id, uri) }
                .onSuccess { attachment -> pending.value = pending.value + attachment.toPending() }
                .onFailure { t -> notice.value = t.message ?: "图片处理失败" }
        }
    }

    fun removeAttachment(attachmentId: String) {
        val target = pending.value.firstOrNull { it.id == attachmentId } ?: return
        attachmentStore.delete(listOf(target.attachment))
        pending.value = pending.value.filterNot { it.id == attachmentId }
    }

    /** 在输入框里插入一个分镜骨架，方便按「第N个镜头[起-止秒]」填内容。 */
    fun insertShotTemplate() {
        val template = buildString {
            if (input.value.isNotBlank()) append("\n\n")
            append("全局风格：写实电影感，青橙色调，浅景深。\n")
            append("第1个镜头[0-3秒] 中景：\n")
            append("第2个镜头[3-6秒] 特写：\n")
        }
        input.value = input.value + template
    }

    fun optimizePrompt() {
        viewModelScope.launchPromptRewrite(input, rewriting, notice) {
            repository.rewritePrompt(it)
        }?.let { rewriteJob = it }
    }

    fun send() {
        val snapshot = state.value
        if (!snapshot.canSend) return
        if (!snapshot.available) {
            notice.value = "视频生成需要通义千问的 API Key，请到设置里配置"
            return
        }
        val draft = VideoDraft(
            mode = snapshot.params.mode,
            prompt = input.value,
            model = snapshot.model,
            resolution = snapshot.params.resolution,
            ratio = if (snapshot.params.supportsRatio) snapshot.params.ratio else "adaptive",
            duration = snapshot.params.duration,
            audio = snapshot.params.audio,
            promptExtend = snapshot.promptExtend,
            firstFrame = snapshot.firstFrame?.attachment,
            lastFrame = snapshot.lastFrame?.attachment,
            references = snapshot.references.map { it.attachment },
        )
        val existing = conversationId.value
        if (existing != null) {
            dispatchSend(existing, draft)
        } else {
            viewModelScope.launch { dispatchSend(ensureConversation(), draft) }
        }
    }

    private fun dispatchSend(id: String, draft: VideoDraft) {
        when (val result = repository.send(id, draft)) {
            VideoSendResult.Started -> {
                input.value = ""
                pending.value = emptyList()
            }

            VideoSendResult.Busy -> Unit
            is VideoSendResult.Rejected -> notice.value = result.reason
        }
    }

    fun stop() {
        val id = conversationId.value ?: return
        repository.stop(id)
    }

    fun regenerate(messageId: String) {
        val id = conversationId.value ?: return
        when (val result = repository.regenerate(id, messageId)) {
            VideoSendResult.Started, VideoSendResult.Busy -> Unit
            is VideoSendResult.Rejected -> notice.value = result.reason
        }
    }

    fun deleteMessage(messageId: String) = repository.deleteMessage(messageId)

    fun newConversation() {
        cancelRewrite()
        discardPending()
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            conversationId.value = repository.createConversation(settings.resolvedVideoWorkspaceModel)
        }
    }

    fun selectConversation(id: String) {
        if (conversationId.value == id) return
        cancelRewrite()
        discardPending()
        conversationId.value = id
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { repository.renameConversation(id, title) }
    }

    fun deleteConversation(id: String) {
        repository.deleteConversation(id)
        if (conversationId.value == id) conversationId.value = null
    }

    fun setModel(model: String) {
        if (model.isBlank()) return
        viewModelScope.launch {
            settingsRepository.updateWorkspace(WorkspaceSlot.Video, ProviderCatalog.QWEN, model)
            val id = conversationId.value ?: return@launch
            repository.setConversationModel(id, model)
        }
    }

    override fun onCleared() {
        cancelRewrite()
        discardPending()
        super.onCleared()
    }

    private fun cancelRewrite() {
        rewriteJob?.cancel()
        rewriteJob = null
        rewriting.value = false
    }

    private suspend fun ensureConversation(): String = conversationLock.withLock {
        conversationId.value?.let { return@withLock it }
        val created = repository.createConversation(state.value.model.ifBlank { null })
        conversationId.value = created
        created
    }

    private fun discardPending() {
        val attachments = pending.value.map { it.attachment }
        if (attachments.isNotEmpty()) attachmentStore.delete(attachments)
        pending.value = emptyList()
    }

    private fun Attachment.toPending(): PendingAttachment = PendingAttachment(
        id = id,
        thumbnailPath = attachmentStore.thumbnailOf(this).absolutePath,
        attachment = this,
    )

    private data class ComposerSnapshot(
        val input: String,
        val pending: List<PendingAttachment>,
        val notice: String?,
        val rewriting: Boolean,
        val params: VideoParams,
    )

    private data class CoreSnapshot(
        val id: String?,
        val conversation: Conversation?,
        val messages: List<Message>,
        val tasks: List<VideoTask>,
    )

    companion object {
        fun factory(
            repository: VideoRepository,
            attachmentStore: AttachmentStore,
            settingsRepository: SettingsRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { VideoViewModel(repository, attachmentStore, settingsRepository) }
        }
    }
}

/** 任务状态 → 「等待/生成中」文案（UI 用）。 */
internal fun VideoTaskStatus.waitingLabel(): String = when (this) {
    VideoTaskStatus.PENDING -> "等待生成视频…"
    VideoTaskStatus.SUBMITTED, VideoTaskStatus.RUNNING -> "生成中…"
    VideoTaskStatus.SUCCEEDED -> "已生成"
    VideoTaskStatus.FAILED -> "生成失败"
    VideoTaskStatus.CANCELLED -> "已取消"
}
