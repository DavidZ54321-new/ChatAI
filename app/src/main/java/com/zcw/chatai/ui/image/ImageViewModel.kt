package com.zcw.chatai.ui.image

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zcw.chatai.data.image.ImageModelCatalog
import com.zcw.chatai.data.prefs.WorkspaceSlot
import com.zcw.chatai.ui.common.launchPromptRewrite
import com.zcw.chatai.data.image.openai.OpenAiImageOptions
import com.zcw.chatai.data.image.qwen.QwenImageOptions
import com.zcw.chatai.data.image.ImageRepository
import com.zcw.chatai.data.image.ImageSendResult
import com.zcw.chatai.data.media.AttachmentStore
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.prefs.SettingsRepository
import com.zcw.chatai.ui.chat.PendingAttachment
import com.zcw.chatai.ui.common.ConversationSearch
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

class ImageViewModel(
    private val repository: ImageRepository,
    private val attachmentStore: AttachmentStore,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val conversationId = MutableStateFlow<String?>(null)
    private val input = MutableStateFlow("")
    private val pending = MutableStateFlow<List<PendingAttachment>>(emptyList())
    private val notice = MutableStateFlow<String?>(null)
    private val rewriting = MutableStateFlow(false)

    /** 建会话是读-改-写，串行化避免并发各建一条空会话。 */
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

    private val composerFlow = combine(input, pending, notice, rewriting) { text, pend, note, busy ->
        ComposerSnapshot(text, pend, note, busy)
    }

    private val coreFlow = combine(conversationId, conversationFlow, messagesFlow) { id, conversation, messages ->
        CoreSnapshot(id, conversation, messages)
    }

    val state: StateFlow<ImageUiState> = combine(
        coreFlow,
        repository.busyConversations,
        composerFlow,
        settingsRepository.settings,
        repository.remoteModels,
    ) { core, busy, composer, settings, remote ->
        val model = core.conversation?.model?.takeIf { it.isNotBlank() }
            ?: settings.resolvedImageWorkspaceModel
        val provider = core.conversation?.providerId?.takeIf { it.isNotBlank() }
            ?: settings.resolvedImageWorkspaceProvider
        val selection = repository.selection(model, provider, remote)
        ImageUiState(
            conversationId = core.id,
            title = core.conversation?.title ?: "新图像",
            model = model,
            providerId = selection.providerId,
            providerLabel = selection.providerLabel,
            modelSections = repository.modelSections(remote),
            maxInputImages = selection.maxInputImages,
            acceptsImageInput = selection.acceptsImageInput,
            messages = core.messages.map { it.toChatMessageItem(attachmentStore) },
            input = composer.input,
            pending = composer.pending,
            // 已有生成结果 → 下一轮会自动带上它（占一个图片名额）。判定与仓库同口径。
            hasPreviousImage = core.messages.any { message ->
                message.role == Role.ASSISTANT && message.attachments.any { it.kind == AttachmentKind.IMAGE }
            },
            isBusy = core.id != null && busy.contains(core.id),
            rewriting = composer.rewriting,
            notice = composer.notice,
            available = settings.providers[selection.providerId]?.apiKey?.isNotBlank() == true,
            openAiImage = settings.openAiImage,
            qwenImage = settings.qwenImage,
            imagePromptExtend = settings.imagePromptExtend,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImageUiState())

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

    fun addImage(uri: Uri) {
        viewModelScope.launch {
            if (!state.value.acceptsImageInput) {
                notice.value = "当前模型只支持文生图"
                return@launch
            }
            if (pending.value.size >= state.value.userImageLimit) {
                notice.value = "本轮最多还能再传 ${state.value.userImageLimit} 张图片"
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

    fun optimizePrompt() {
        viewModelScope.launchPromptRewrite(input, rewriting, notice) {
            repository.rewritePrompt(it)
        }?.let { rewriteJob = it }
    }

    fun send() {
        val text = input.value
        if (text.isBlank()) return
        if (!state.value.available) {
            notice.value = ImageModelCatalog.missingKey(state.value.providerId)
            return
        }
        // 只传用户自己传的图；上一轮生成结果由仓库静默带入为图 1。
        val images = pending.value.map { it.attachment }
        val existing = conversationId.value
        if (existing != null) {
            dispatchSend(existing, text, images)
        } else {
            viewModelScope.launch { dispatchSend(ensureConversation(), text, images) }
        }
    }

    private fun dispatchSend(id: String, text: String, images: List<Attachment>) {
        when (val result = repository.send(id, text, images, state.value.model, state.value.providerId)) {
            ImageSendResult.Started -> {
                input.value = ""
                pending.value = emptyList()
            }

            ImageSendResult.Busy -> Unit
            is ImageSendResult.Rejected -> notice.value = result.reason
        }
    }

    fun stop() {
        val id = conversationId.value ?: return
        repository.stop(id)
    }

    fun regenerate(messageId: String) {
        val id = conversationId.value ?: return
        when (val result = repository.regenerate(id, messageId)) {
            ImageSendResult.Started, ImageSendResult.Busy -> Unit
            is ImageSendResult.Rejected -> notice.value = result.reason
        }
    }

    fun deleteMessage(messageId: String) = repository.deleteMessage(messageId)

    fun newConversation() {
        cancelRewrite()
        discardPending()
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            conversationId.value = repository.createConversation(
                model = settings.resolvedImageWorkspaceModel,
                providerId = settings.resolvedImageWorkspaceProvider,
            )
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

    fun setModel(providerId: String, model: String) {
        if (model.isBlank() || providerId.isBlank()) return
        viewModelScope.launch {
            settingsRepository.updateWorkspace(WorkspaceSlot.Image, providerId, model)
            val id = conversationId.value ?: return@launch
            repository.setConversationModel(id, providerId, model)
        }
    }

    /** OpenAI 的质量、尺寸、格式等。全局生效，下次生图就带上。 */
    fun setOpenAiImage(options: OpenAiImageOptions) {
        viewModelScope.launch { settingsRepository.updateOpenAiImage(options) }
    }

    fun setQwenImage(options: QwenImageOptions) {
        viewModelScope.launch { settingsRepository.updateQwenImage(options) }
    }

    fun setImagePromptExtend(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateImagePromptExtend(enabled) }
    }

    /** 打开模型选择器时刷新远程名单。失败不影响已经显示的内置项。 */
    fun refreshModels() {
        viewModelScope.launch { repository.refreshModels() }
    }

    override fun onCleared() {
        cancelRewrite()
        discardPending()
        super.onCleared()
    }

    /** 优化跑在界面 scope 上，切会话要停掉，避免后到的字写进下一条的输入框。 */
    private fun cancelRewrite() {
        rewriteJob?.cancel()
        rewriteJob = null
        rewriting.value = false
    }

    private suspend fun ensureConversation(): String = conversationLock.withLock {
        conversationId.value?.let { return@withLock it }
        val created = repository.createConversation(
            model = state.value.model.ifBlank { null },
            providerId = state.value.providerId,
        )
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
    )

    private data class CoreSnapshot(
        val id: String?,
        val conversation: Conversation?,
        val messages: List<Message>,
    )

    companion object {
        fun factory(
            repository: ImageRepository,
            attachmentStore: AttachmentStore,
            settingsRepository: SettingsRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ImageViewModel(repository, attachmentStore, settingsRepository) }
        }
    }
}
