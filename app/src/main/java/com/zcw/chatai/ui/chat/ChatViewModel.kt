package com.zcw.chatai.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zcw.chatai.data.ChatRepository
import com.zcw.chatai.data.SendResult
import com.zcw.chatai.data.StreamingMessage
import com.zcw.chatai.data.media.AttachmentLimits
import com.zcw.chatai.data.media.AttachmentStore
import com.zcw.chatai.data.model.Attachment
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
    ) { text, pend, note, settings ->
        ComposerSnapshot(
            input = text,
            pending = pend,
            notice = note,
            defaultModel = settings.model,
            webSearchAvailable = searchProvider?.available(settings.baseUrl, settings.apiKey) == true,
        )
    }

    private val turnFlow = combine(
        repository.streaming,
        repository.busyConversations,
    ) { streaming, busy ->
        TurnSnapshot(streaming = streaming, busy = busy)
    }

    val state: StateFlow<ChatUiState> = combine(
        conversationId,
        conversationFlow,
        messagesFlow,
        turnFlow,
        composerFlow,
    ) { id, conversation, messages, turn, composer ->
        buildState(id, conversation, messages, turn, composer)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            repository.errors.collect { error ->
                if (error != null) {
                    val current = conversationId.value
                    // 只把当前会话（或全局）的失败显示出来，别的会话不打扰。
                    if (error.conversationId == null || error.conversationId == current) {
                        notice.value = error.message
                    }
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
        val existing = conversationId.value
        if (existing != null) {
            dispatchSend(existing, text, attachments)
            return
        }
        viewModelScope.launch {
            dispatchSend(ensureConversation(), text, attachments)
        }
    }

    private fun dispatchSend(id: String, text: String, attachments: List<Attachment>) {
        when (val result = repository.send(id, text, attachments)) {
            SendResult.Started -> {
                input.value = ""
                pending.value = emptyList()
            }

            // 本会话回合还在跑（连点/竞态）：静默忽略，发送键此时也已被禁用。
            SendResult.Busy -> Unit

            is SendResult.Rejected -> notice.value = result.reason
        }
    }

    /** 只停当前会话；其他会话的回合照常运行。 */
    fun stop() {
        val id = conversationId.value ?: return
        repository.stop(id)
    }

    fun retry(messageId: String) = regenerate(messageId)

    fun regenerate(messageId: String) {
        val id = conversationId.value ?: return
        when (val result = repository.regenerate(id, messageId)) {
            SendResult.Started -> Unit
            SendResult.Busy -> Unit
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
        turn: TurnSnapshot,
        composer: ComposerSnapshot,
    ): ChatUiState {
        val activeStream = id?.let { turn.streaming[it] }
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
            isTurnActive = id != null && turn.busy.contains(id),
            input = composer.input,
            pending = composer.pending,
            defaultModel = composer.defaultModel,
            notice = composer.notice,
            webSearchEnabled = conversation?.webSearchEnabled == true,
            webSearchAvailable = composer.webSearchAvailable,
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

    private data class TurnSnapshot(
        val streaming: Map<String, StreamingMessage>,
        val busy: Set<String>,
    )

    private data class ComposerSnapshot(
        val input: String,
        val pending: List<PendingAttachment>,
        val notice: String?,
        val defaultModel: String,
        val webSearchAvailable: Boolean,
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
