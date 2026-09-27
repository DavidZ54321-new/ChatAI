package com.zcw.chatai.ui.image

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zcw.chatai.data.image.ImageRepository
import com.zcw.chatai.data.image.ImageSendResult
import com.zcw.chatai.data.media.AttachmentStore
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.prefs.SettingsRepository
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.ui.chat.PendingAttachment
import com.zcw.chatai.ui.chat.toChatMessageItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private var rewriteOriginal: String = ""

    val conversations: StateFlow<List<Conversation>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    ) { core, busy, composer, settings ->
        ImageUiState(
            conversationId = core.id,
            title = core.conversation?.title ?: "新图像",
            model = core.conversation?.model?.takeIf { it.isNotBlank() } ?: settings.imageGenModel,
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
            available = settings.providers[ProviderCatalog.QWEN]?.apiKey?.isNotBlank() == true,
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

    fun consumeNotice() {
        notice.value = null
    }

    fun addImage(uri: Uri) {
        viewModelScope.launch {
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

    /** 优化提示词：流式写回输入框；再点一次取消并恢复原文。 */
    fun optimizePrompt() {
        if (rewriting.value) {
            val restored = rewriteOriginal
            rewriteJob?.cancel()
            rewriteJob = null
            input.value = restored
            rewriting.value = false
            return
        }
        val text = input.value.trim()
        if (text.isEmpty()) {
            notice.value = "先输入提示词再优化"
            return
        }
        rewriteOriginal = text
        rewriting.value = true
        input.value = ""
        rewriteJob = viewModelScope.launch {
            try {
                repository.rewritePrompt(text).collect { delta -> input.value = input.value + delta }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (input.value.isBlank()) input.value = text
                notice.value = t.message?.takeIf { it.isNotBlank() } ?: "提示词优化失败"
            } finally {
                rewriting.value = false
            }
        }
    }

    fun send() {
        val text = input.value
        if (text.isBlank()) return
        if (!state.value.available) {
            notice.value = "生图需要通义千问的 API Key，请到设置里配置"
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
        when (val result = repository.send(id, text, images, state.value.model)) {
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
            conversationId.value = repository.createConversation(state.value.model.ifBlank { null })
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
        val id = conversationId.value
        if (id == null) {
            viewModelScope.launch { conversationId.value = repository.createConversation(model) }
            return
        }
        viewModelScope.launch { repository.setConversationModel(id, model) }
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
