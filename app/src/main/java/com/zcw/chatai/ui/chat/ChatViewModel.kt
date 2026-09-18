package com.zcw.chatai.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zcw.chatai.data.AgentActivity
import com.zcw.chatai.data.ChatRepository
import com.zcw.chatai.data.SendResult
import com.zcw.chatai.data.StreamingMessage
import com.zcw.chatai.data.media.AttachmentLimits
import com.zcw.chatai.data.media.AttachmentStore
import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.prefs.SettingsRepository
import com.zcw.chatai.data.web.WebSearchProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: ChatRepository,
    private val attachmentStore: AttachmentStore,
    settingsRepository: SettingsRepository,
    private val searchProvider: WebSearchProvider? = null,
) : ViewModel() {

    private val input = MutableStateFlow("")
    private val pending = MutableStateFlow<List<PendingAttachment>>(emptyList())
    private val notice = MutableStateFlow<String?>(null)
    private val conversationId = MutableStateFlow<String?>(null)

    val conversations: StateFlow<List<Conversation>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val messagesFlow = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.observeMessages(id)
    }

    private val conversationFlow = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(null) else repository.observeConversation(id)
    }

    private val composerFlow = combine(
        input,
        pending,
        notice,
        settingsRepository.settings,
        repository.activity,
    ) { text, pend, note, settings, activity ->
        ComposerSnapshot(
            input = text,
            pending = pend,
            notice = note,
            defaultModel = settings.model,
            webSearchAvailable = searchProvider?.available(settings.baseUrl, settings.apiKey) == true,
            activity = activity,
        )
    }

    val state: StateFlow<ChatUiState> = combine(
        conversationId,
        conversationFlow,
        messagesFlow,
        repository.streaming,
        composerFlow,
    ) { id, conversation, messages, streaming, composer ->
        buildState(id, conversation, messages, streaming, composer)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            repository.errors.collect { message ->
                if (message != null) {
                    notice.value = message
                    repository.consumeError()
                }
            }
        }
        viewModelScope.launch {
            val existing = repository.observeConversations().first()
            if (conversationId.value == null && existing.isNotEmpty()) {
                conversationId.value = existing.first().id
            }
        }
    }

    // ---------- Composer ----------

    fun setInput(text: String) {
        input.value = text
    }

    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            if (pending.value.size >= AttachmentLimits.MAX_IMAGES) {
                notice.value = "最多只能发送 ${AttachmentLimits.MAX_IMAGES} 张图片"
                return@launch
            }
            val id = ensureConversation()
            runCatching { attachmentStore.importImage(id, uri) }
                .onSuccess { attachment ->
                    pending.value = pending.value + PendingAttachment(
                        id = attachment.id,
                        thumbnailPath = attachmentStore.thumbnailOf(attachment).absolutePath,
                        attachment = attachment,
                    )
                }
                .onFailure { t -> notice.value = t.message ?: "图片处理失败" }
        }
    }

    fun removeAttachment(attachmentId: String) {
        val target = pending.value.firstOrNull { it.id == attachmentId } ?: return
        attachmentStore.delete(listOf(target.attachment))
        pending.value = pending.value.filterNot { it.id == attachmentId }
    }

    fun consumeNotice() {
        notice.value = null
    }

    // ---------- 会话动作 ----------

    fun send() {
        val text = input.value
        val attachments = pending.value.map { it.attachment }
        if (text.isBlank() && attachments.isEmpty()) return
        viewModelScope.launch {
            val id = ensureConversation()
            when (val result = repository.send(id, text, attachments)) {
                SendResult.Started -> {
                    input.value = ""
                    pending.value = emptyList()
                }

                is SendResult.Rejected -> notice.value = result.reason
            }
        }
    }

    fun stop() = repository.stop()

    fun retry(messageId: String) = regenerate(messageId)

    fun regenerate(messageId: String) {
        when (val result = repository.regenerate(messageId)) {
            SendResult.Started -> Unit
            is SendResult.Rejected -> notice.value = result.reason
        }
    }

    fun deleteMessage(messageId: String) = repository.deleteMessage(messageId)

    fun selectConversation(id: String) {
        if (conversationId.value == id) return
        discardPending()
        conversationId.value = id
    }

    fun newConversation() {
        discardPending()
        viewModelScope.launch { conversationId.value = repository.createConversation() }
    }

    fun clearConversation() {
        val id = conversationId.value ?: return
        discardPending()
        repository.clearConversation(id)
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { repository.renameConversation(id, title) }
    }

    fun deleteConversation(id: String) {
        repository.deleteConversation(id)
        if (conversationId.value == id) conversationId.value = null
    }

    fun setModel(model: String) {
        val id = conversationId.value ?: return
        viewModelScope.launch { repository.setConversationModel(id, model) }
    }

    /** 切换本会话的 🌐 联网开关；不可用时给可读提示而不静默失败。 */
    fun toggleWebSearch() {
        val id = conversationId.value ?: return
        val current = state.value.webSearchEnabled
        viewModelScope.launch {
            if (!current && !state.value.webSearchAvailable) {
                notice.value = "联网搜索不可用：请先在设置里填写 API Key"
                return@launch
            }
            repository.setConversationWebSearch(id, !current)
        }
    }

    override fun onCleared() {
        discardPending()
        super.onCleared()
    }

    private suspend fun ensureConversation(): String {
        conversationId.value?.let { return it }
        val created = repository.createConversation()
        conversationId.value = created
        return created
    }

    private fun discardPending() {
        val attachments = pending.value.map { it.attachment }
        if (attachments.isNotEmpty()) attachmentStore.delete(attachments)
        pending.value = emptyList()
    }

    private fun buildState(
        id: String?,
        conversation: Conversation?,
        messages: List<Message>,
        streaming: StreamingMessage?,
        composer: ComposerSnapshot,
    ): ChatUiState {
        val activeStream = streaming?.takeIf { it.conversationId == id }
        val items = messages.map { message ->
            val item = message.toItem()
            if (activeStream != null && activeStream.messageId == message.id) {
                item.copy(
                    content = activeStream.content,
                    reasoning = activeStream.reasoning.ifEmpty { null },
                    reasoningMs = activeStream.reasoningMs,
                    status = MessageStatus.STREAMING,
                )
            } else {
                item
            }
        }.let { list ->
            if (activeStream != null && list.none { it.id == activeStream.messageId }) {
                list + ChatMessageItem(
                    id = activeStream.messageId,
                    role = Role.ASSISTANT,
                    content = activeStream.content,
                    reasoning = activeStream.reasoning.ifEmpty { null },
                    reasoningMs = activeStream.reasoningMs,
                    status = MessageStatus.STREAMING,
                    model = conversation?.model,
                )
            } else {
                list
            }
        }
        return ChatUiState(
            conversationId = id,
            title = conversation?.title ?: "新对话",
            model = conversation?.model?.takeIf { it.isNotBlank() } ?: composer.defaultModel,
            messages = items,
            isStreaming = activeStream != null,
            streamingMessageId = activeStream?.messageId,
            input = composer.input,
            pending = composer.pending,
            defaultModel = composer.defaultModel,
            notice = composer.notice,
            webSearchEnabled = conversation?.webSearchEnabled == true,
            webSearchAvailable = composer.webSearchAvailable,
            activity = composer.activity?.takeIf { it.conversationId == id }?.text,
        )
    }

    private fun Message.toItem(): ChatMessageItem = ChatMessageItem(
        id = id,
        role = role,
        content = content,
        reasoning = reasoningContent,
        status = status,
        errorMessage = errorMessage,
        model = model,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
    reasoningTokens = reasoningTokens,
    cachedTokens = cachedTokens,
    reasoningMs = reasoningMs,
        images = attachments.map { attachment ->
            MessageImage(
                id = attachment.id,
                thumbnailPath = attachmentStore.thumbnailOf(attachment).absolutePath,
                fullPath = attachmentStore.fileOf(attachment).absolutePath,
                width = attachment.width,
                height = attachment.height,
            )
        },
        toolResult = toolResult,
    )

    private data class ComposerSnapshot(
        val input: String,
        val pending: List<PendingAttachment>,
        val notice: String?,
        val defaultModel: String,
        val webSearchAvailable: Boolean,
        val activity: AgentActivity?,
    )

    companion object {
        fun factory(
            repository: ChatRepository,
            attachmentStore: AttachmentStore,
            settingsRepository: SettingsRepository,
            searchProvider: WebSearchProvider? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(repository, attachmentStore, settingsRepository, searchProvider) }
        }
    }
}
