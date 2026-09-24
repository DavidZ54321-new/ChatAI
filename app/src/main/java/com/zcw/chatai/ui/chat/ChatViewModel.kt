package com.zcw.chatai.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zcw.chatai.data.ChatRepository
import com.zcw.chatai.data.ConversationBinding
import com.zcw.chatai.data.SendResult
import com.zcw.chatai.data.StreamingMessage
import com.zcw.chatai.data.doc.DocumentLabel
import com.zcw.chatai.data.media.AttachmentLimits
import com.zcw.chatai.data.media.AttachmentStore
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.persona.PersonaEntry
import com.zcw.chatai.data.prefs.SettingsRepository
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.provider.ToolBackendResolver
import com.zcw.chatai.data.web.ImageSearchProvider
import com.zcw.chatai.data.web.WebSearchProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 「继续」发送的文本；和用户手打一致，走同一条落库/请求路径。 */
private const val CONTINUE_TEXT = "继续"

class ChatViewModel(
    private val repository: ChatRepository,
    private val attachmentStore: AttachmentStore,
    private val settingsRepository: SettingsRepository,
    /** 按供应商 id 选搜索后端（可用性提示用）。 */
    private val searchProviders: Map<String, WebSearchProvider> = emptyMap(),
    /** 按供应商 id 选图搜后端（可用性提示用）。 */
    private val imageProviders: Map<String, ImageSearchProvider> = emptyMap(),
) : ViewModel() {

    private val input = MutableStateFlow("")
    private val pending = MutableStateFlow<List<PendingAttachment>>(emptyList())
    private val notice = MutableStateFlow<String?>(null)
    private val conversationId = MutableStateFlow<String?>(null)

    /**
     * 编辑用户消息的草稿。独立于 [state] 的 combine 链（那条链已有 5 个源，再加要改结构），
     * 由 App 单独 collect 后传给 ChatScreen；弹层是否显示只看它是否为 null。
     */
    private val _editDraft = MutableStateFlow<EditDraft?>(null)

    val editDraft: StateFlow<EditDraft?> = _editDraft.asStateFlow()

    /**
     * 冷启动/进程重建后的一次性聚焦请求：进「新对话」空态时聚焦输入框并唤醒输入法。
     * 消费后置 false——回后台再回来（VM 还活着）不再抢焦点。
     */
    private val _autoFocusComposer = MutableStateFlow(true)
    val autoFocusComposer: StateFlow<Boolean> = _autoFocusComposer.asStateFlow()

    /**
     * 还没有会话时，用户先选好的模型/供应商只是**界面状态**：不急着建一条空会话，
     * 等真正建会话（首次发送或点「+」）时再一次性落库。
     */
    private val pendingBinding = MutableStateFlow<PendingBinding?>(null)

    /** 建会话是「读-改-写」，两条协程并发 can 都看到 null 而各建一条空会话，串行化掉。 */
    private val conversationLock = Mutex()

    val conversations: StateFlow<List<Conversation>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 会话列表页的搜索词；空串 = 不过滤（直接看全部会话）。 */
    private val _searchQuery = MutableStateFlow("")

    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** 会话列表页展示的数据：有关键词就走标题+消息正文检索，否则全部会话。 */
    val searchResults: StateFlow<List<Conversation>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) conversations else repository.searchConversations(query)
        }
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
        pendingBinding,
    ) { text, pend, note, settings, binding ->
        val entry = settings.activeProvider
        // 工具后端与主对话供应商解耦（借道）：可用性只看后端配置，不看会话模型。
        val backendIds = ToolBackendResolver.resolve(
            providers = settings.providers,
            activeProviderId = settings.activeProviderId,
            conversationProviderId = null,
            preferredSearchProviderId = settings.searchProviderId,
        )
        val textAvailable = backendIds.textProviderIds.any { providerId ->
            settings.providers[providerId]?.let { providerEntry ->
                searchProviders[providerId]?.available(providerEntry.baseUrl, providerEntry.apiKey)
            } == true
        }
        val imageAvailable = backendIds.imageProviderId?.let { providerId ->
            settings.providers[providerId]?.let { providerEntry ->
                imageProviders[providerId]?.available(providerEntry.baseUrl, providerEntry.apiKey)
            }
        } == true

        ComposerSnapshot(
            input = text,
            pending = pend,
            notice = note,
            defaultModel = entry.model,
            webSearchAvailable = textAvailable || imageAvailable,
            pendingWebSearch = binding?.webSearchEnabled == true,
            activeProviderId = settings.activeProviderId,
            pendingModel = binding?.model,
            pendingProviderId = binding?.providerId,
            personas = settings.personas,
            activePersonaId = settings.resolvedActivePersonaId,
            pendingPersonaId = binding?.personaId,
        )
    }

    private val turnFlow = combine(
        repository.streaming,
        repository.busyConversations,
        repository.videoUploads,
    ) { streaming, busy, uploads ->
        TurnSnapshot(streaming = streaming, busy = busy, videoUploads = uploads)
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
    }

    fun consumeAutoFocusComposer() {
        _autoFocusComposer.value = false
    }

    // ---------- Composer ----------

    fun setInput(text: String) {
        input.value = text
    }

    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            val imageCount = pending.value.count { it.attachment.kind == AttachmentKind.IMAGE }
            if (imageCount >= AttachmentLimits.MAX_IMAGES) {
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

    /** 导入文档：原文件落盘 + 解析文本写 sidecar；失败原因直接展示（解析器 message 已面向用户）。 */
    fun addDocument(uri: Uri) {
        viewModelScope.launch {
            val docCount = pending.value.count { it.attachment.kind == AttachmentKind.DOCUMENT }
            if (docCount >= AttachmentLimits.MAX_DOCUMENTS) {
                notice.value = "最多只能发送 ${AttachmentLimits.MAX_DOCUMENTS} 个文档"
                return@launch
            }
            val id = ensureConversation()
            runCatching { attachmentStore.importDocument(id, uri) }
                .onSuccess { attachment ->
                    pending.value = pending.value + PendingAttachment(
                        id = attachment.id,
                        // 文档没有位图缩略图：传空路径，缩略图组件显示类型戳（见前端）。
                        thumbnailPath = "",
                        attachment = attachment,
                    )
                }
                .onFailure { t -> notice.value = t.message ?: "文档处理失败" }
        }
    }

    fun removeAttachment(attachmentId: String) {
        val target = pending.value.firstOrNull { it.id == attachmentId } ?: return
        attachmentStore.delete(listOf(target.attachment))
        pending.value = pending.value.filterNot { it.id == attachmentId }
    }

    /** 导入视频：原样复制 mp4 + 抽首帧缩略图；超过数量/大小上限给可读提示。 */
    fun addVideo(uri: Uri) {
        viewModelScope.launch {
            val videoCount = pending.value.count { it.attachment.kind == AttachmentKind.VIDEO }
            if (videoCount >= AttachmentLimits.MAX_VIDEOS) {
                notice.value = "最多只能发送 ${AttachmentLimits.MAX_VIDEOS} 个视频"
                return@launch
            }
            val id = ensureConversation()
            runCatching { attachmentStore.importVideo(id, uri) }
                .onSuccess { attachment ->
                    pending.value = pending.value + PendingAttachment(
                        id = attachment.id,
                        thumbnailPath = attachmentStore.thumbnailOf(attachment).absolutePath,
                        attachment = attachment,
                    )
                }
                .onFailure { t -> notice.value = t.message ?: "视频处理失败" }
        }
    }

    /** 导入音频：原样复制 + 读时长；无位图缩略图（字母牌呈现）。 */
    fun addAudio(uri: Uri) {
        viewModelScope.launch {
            val audioCount = pending.value.count { it.attachment.kind == AttachmentKind.AUDIO }
            if (audioCount >= AttachmentLimits.MAX_AUDIOS) {
                notice.value = "最多只能发送 ${AttachmentLimits.MAX_AUDIOS} 个音频"
                return@launch
            }
            val id = ensureConversation()
            runCatching { attachmentStore.importAudio(id, uri) }
                .onSuccess { attachment ->
                    pending.value = pending.value + PendingAttachment(
                        id = attachment.id,
                        // 音频没有位图缩略图：传空路径，缩略图组件显示字母牌（见前端）。
                        thumbnailPath = "",
                        attachment = attachment,
                    )
                }
                .onFailure { t -> notice.value = t.message ?: "音频处理失败" }
        }
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

    /**
     * Claude Code 式「继续」：在停止/中断的回答后接着生成。
     * 用一条普通用户消息「继续」触发（落库与线上请求一致），历史损坏由仓库层修复。
     */
    fun continueTurn() {
        val id = conversationId.value ?: return
        when (val result = repository.send(id, CONTINUE_TEXT)) {
            SendResult.Started, SendResult.Busy -> Unit
            is SendResult.Rejected -> notice.value = result.reason
        }
    }

    fun regenerate(messageId: String) {
        val id = conversationId.value ?: return
        when (val result = repository.regenerate(id, messageId)) {
            SendResult.Started -> Unit
            SendResult.Busy -> Unit
            is SendResult.Rejected -> notice.value = result.reason
        }
    }

    fun deleteMessage(messageId: String) = repository.deleteMessage(messageId)

    // ---------- 编辑用户消息 ----------

    /**
     * 进编辑：从当前消息列表取原始附件与正文，模型/供应商/🌐 取**会话级**当前值（会话级语义）。
     * 生成中先拦下——截断会打断正在流式的回合。
     */
    fun beginEdit(messageId: String) {
        if (state.value.isTurnActive) {
            notice.value = "正在生成回答，请先停止或稍后编辑"
            return
        }
        val current = state.value
        val conversationId = current.conversationId ?: return
        val message = current.messages.firstOrNull { it.id == messageId }
        if (message == null || message.role != Role.USER) return
        // 上一个草稿（例如没关就点了另一条）按取消处理：新导入的文件得删掉。
        discardEditDraft()
        _editDraft.value = EditDraft(
            messageId = message.id,
            conversationId = conversationId,
            text = message.content,
            attachments = message.attachments.map { it.toPending() },
            model = current.model,
            providerId = current.providerId,
            webSearchEnabled = current.webSearchEnabled,
            laterCount = MessageEdit.laterCount(current.messages, message.id),
        )
    }

    fun setEditText(text: String) = updateEditDraft { it.copy(text = text) }

    fun setEditModel(model: String) {
        if (model.isBlank()) return
        updateEditDraft { it.copy(model = model, bindingDirty = true) }
    }

    /** 弹层里换供应商：与 Composer 的 setProvider 同一条规则，但只改草稿（确认重发才落库）。 */
    fun setEditProvider(providerId: String) {
        if (providerId.isBlank()) return
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val model = ProviderCatalog.defaultModelFor(settings.providers, providerId)
            if (model.isBlank()) {
                notice.value = "「${ProviderCatalog.displayName(providerId)}」还没配置模型，请先到设置里填写"
                return@launch
            }
            updateEditDraft { it.copy(providerId = providerId, model = model, bindingDirty = true) }
        }
    }

    fun toggleEditWebSearch() {
        val draft = _editDraft.value ?: return
        if (!draft.webSearchEnabled && !state.value.webSearchAvailable) {
            notice.value = "联网搜索不可用：请先在设置里填写 API Key"
            return
        }
        updateEditDraft { it.copy(webSearchEnabled = !it.webSearchEnabled, bindingDirty = true) }
    }

    fun removeEditAttachment(attachmentId: String) {
        val draft = _editDraft.value ?: return
        val removal = MessageEdit.remove(draft, attachmentId)
        // 只有本次新导入的附件可以立刻删文件（它还不属于任何消息）。
        if (removal.deleteNow.isNotEmpty()) attachmentStore.delete(removal.deleteNow)
        _editDraft.value = removal.draft
    }

    /**
     * 系统选择器回来的一批附件：**逐个**导入，每张都在前一张落进草稿之后再判额度，
     * 多选才不会整批读到同一份旧草稿而绕过上限。
     */
    fun addEditAttachments(picked: List<PickedAttachment>) {
        if (picked.isEmpty()) return
        viewModelScope.launch {
            for (item in picked) importIntoDraft(item.uri, item.kind)
        }
    }

    private suspend fun importIntoDraft(uri: Uri, kind: AttachmentKind) {
        val draft = _editDraft.value ?: return
        val limits = when (kind) {
            AttachmentKind.IMAGE -> AttachmentLimits.MAX_IMAGES
            AttachmentKind.DOCUMENT -> AttachmentLimits.MAX_DOCUMENTS
            AttachmentKind.VIDEO -> AttachmentLimits.MAX_VIDEOS
            AttachmentKind.AUDIO -> AttachmentLimits.MAX_AUDIOS
        }
        // 计数口径含草稿里已有的附件：原有附件在出站时同样占额度。
        if (draft.attachments.count { it.attachment.kind == kind } >= limits) {
            notice.value = "最多只能发送 ${kind.countNoun(limits)}"
            return
        }
        val conversationId = draft.conversationId
        val messageId = draft.messageId
        runCatching {
            when (kind) {
                AttachmentKind.IMAGE -> attachmentStore.importImage(conversationId, uri)
                AttachmentKind.DOCUMENT -> attachmentStore.importDocument(conversationId, uri)
                AttachmentKind.VIDEO -> attachmentStore.importVideo(conversationId, uri)
                AttachmentKind.AUDIO -> attachmentStore.importAudio(conversationId, uri)
            }
        }
            .onSuccess { attachment -> addImportedToDraft(messageId, attachment) }
            .onFailure { t -> notice.value = t.message ?: kind.failureMessage }
    }

    /** 附件数量上限的说明词：与 Composer 的提示口径一致（图片论「张」，其余论「个」）。 */
    private fun AttachmentKind.countNoun(count: Int): String = when (this) {
        AttachmentKind.IMAGE -> "$count 张图片"
        AttachmentKind.DOCUMENT -> "$count 个文档"
        AttachmentKind.VIDEO -> "$count 个视频"
        AttachmentKind.AUDIO -> "$count 个音频"
    }

    /** 导入失败的兜底文案（导入器自己抛的可读原因优先）。 */
    private val AttachmentKind.failureMessage: String
        get() = when (this) {
            AttachmentKind.IMAGE -> "图片处理失败"
            AttachmentKind.DOCUMENT -> "文档处理失败"
            AttachmentKind.VIDEO -> "视频处理失败"
            AttachmentKind.AUDIO -> "音频处理失败"
        }

    /**
     * 导入回来的新附件进草稿：记进 importedIds，取消编辑时才知道该删哪些文件。
     * 草稿已经关掉或换成了另一条消息 → 这份文件没人要，立刻删。
     */
    private fun addImportedToDraft(messageId: String, attachment: Attachment) {
        val pending = attachment.toPending()
        var kept = false
        _editDraft.update { current ->
            if (current == null || current.messageId != messageId) {
                current
            } else {
                MessageEdit.add(current, pending).also { kept = true }
            }
        }
        if (!kept) attachmentStore.delete(listOf(attachment))
    }

    fun dismissEdit() = discardEditDraft()

    /** 放弃草稿：只删本次新导入的文件；原有附件仍被消息引用，一律不动。 */
    private fun discardEditDraft() {
        val draft = _editDraft.value ?: return
        val files = MessageEdit.discardFiles(draft)
        if (files.isNotEmpty()) attachmentStore.delete(files)
        _editDraft.value = null
    }

    /**
     * 确认重发：绑定（模型/供应商/🌐）先暂存在草稿里，到这里才随回合一起写回会话。
     * 失败只提示、不关弹层，用户改完可以再点一次。
     */
    fun resendEdit() {
        val draft = _editDraft.value ?: return
        if (!draft.canResend) {
            notice.value = "请输入内容"
            return
        }
        val result = repository.editAndResend(
            conversationId = draft.conversationId,
            messageId = draft.messageId,
            text = draft.text,
            attachments = draft.attachments.map { it.attachment },
            // 没动过模型/供应商/🌐 就不写回会话：老会话的空 provider_id（跟随激活供应商）不该被钉死。
            binding = if (draft.bindingDirty) {
                ConversationBinding(draft.model, draft.providerId, draft.webSearchEnabled)
            } else {
                null
            },
        )
        when (result) {
            // 新导入的文件所有权已转移给消息，不能再按「取消要删」处理。
            SendResult.Started -> _editDraft.value = null
            SendResult.Busy -> Unit
            is SendResult.Rejected -> notice.value = result.reason
        }
    }

    private inline fun updateEditDraft(block: (EditDraft) -> EditDraft) {
        _editDraft.update { current -> current?.let(block) }
    }

    /** 草稿里的附件与 Composer 的待发附件同形：图片/视频有缩略图，文档/音频用字母牌。 */
    private fun Attachment.toPending(): PendingAttachment = PendingAttachment(
        id = id,
        thumbnailPath = when (kind) {
            AttachmentKind.IMAGE, AttachmentKind.VIDEO -> attachmentStore.thumbnailOf(this).absolutePath
            else -> ""
        },
        attachment = this,
    )

    fun selectConversation(id: String) {
        if (conversationId.value == id) return
        discardPending()
        // 草稿跟着会话走：切走就把新导入的文件删掉，别把上一个会话的编辑带到下一个。
        discardEditDraft()
        // 选的是已存在的会话：丢弃「新对话里先选好、还没落库」的绑定，避免它残留到下一个新对话。
        pendingBinding.value = null
        conversationId.value = id
    }

    /** 会话列表页搜索：关键词变化时 [searchResults] 自动重算。 */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun newConversation() {
        discardPending()
        discardEditDraft()
        viewModelScope.launch {
            val binding = pendingBinding.value
            conversationId.value = repository.createConversation(
                binding?.model,
                binding?.providerId,
                binding?.personaId,
                binding?.webSearchEnabled == true,
            )
            pendingBinding.value = null
        }
    }

    fun clearConversation() {
        val id = conversationId.value ?: return
        discardPending()
        discardEditDraft()
        repository.clearConversation(id)
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { repository.renameConversation(id, title) }
    }

    fun deleteConversation(id: String) {
        repository.deleteConversation(id)
        if (conversationId.value == id) conversationId.value = null
    }

    /**
     * 设置会话模型。还没有会话时只记成待落库的绑定（不建空会话），
     * 等真正发消息或点「+」时一次性写进新会话。
     */
    fun setModel(model: String) {
        if (model.isBlank()) return
        val id = conversationId.value
        if (id == null) {
            pendingBinding.update { current ->
                (current ?: PendingBinding(model = null, providerId = null)).copy(model = model)
            }
            return
        }
        viewModelScope.launch { repository.setConversationModel(id, model) }
    }

    /**
     * 切换当前会话的供应商，并把它重置为该供应商配置的模型（两者必须一起换）。
     * 没配模型的供应商给可读提示，不改绑定；还没有会话时同样先记成待落库绑定。
     */
    fun setProvider(providerId: String) {
        if (providerId.isBlank()) return
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val model = ProviderCatalog.defaultModelFor(settings.providers, providerId)
            if (model.isBlank()) {
                notice.value = "「${ProviderCatalog.displayName(providerId)}」还没配置模型，请先到设置里填写"
                return@launch
            }
            val id = conversationId.value
            if (id == null) {
                // 保留用户先选好的角色绑定：整体替换会把 pendingPersonaId 丢掉。
                pendingBinding.update { current ->
                    (current ?: PendingBinding(model = null, providerId = null, personaId = null))
                        .copy(model = model, providerId = providerId)
                }
                return@launch
            }
            repository.setConversationProvider(id, providerId, model)
        }
    }

    /**
     * 切换当前会话的角色，并同步激活角色（新会话记住上次选择）。
     * 还没有会话时记成待落库绑定 + 同步激活，等建会话时一次性落库。
     */
    fun setPersona(personaId: String) {
        if (personaId.isBlank()) return
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            if (personaId !in settings.personas) {
                notice.value = "该角色已被删除"
                return@launch
            }
            val id = conversationId.value
            if (id == null) {
                pendingBinding.update { current ->
                    (current ?: PendingBinding(model = null, providerId = null, personaId = null))
                        .copy(personaId = personaId)
                }
                settingsRepository.updateActivePersona(personaId)
                return@launch
            }
            repository.setConversationPersona(id, personaId)
        }
    }

    /** 切换本会话的 🌐 联网开关；不可用时给可读提示而不静默失败。 */
    fun toggleWebSearch() {
        val current = state.value.webSearchEnabled
        // 可用性先判：新对话还没有会话行，不能因为 id 为空就把提示也吞掉。
        if (!current && !state.value.webSearchAvailable) {
            notice.value = "联网搜索不可用：请先在设置里填写 API Key"
            return
        }
        val id = conversationId.value
        if (id == null) {
            // 新对话（会话行还没建）：记成待落库绑定，建会话时随其它绑定一起落库。
            pendingBinding.update { binding ->
                (binding ?: PendingBinding(model = null, providerId = null))
                    .copy(webSearchEnabled = !current)
            }
            return
        }
        viewModelScope.launch { repository.setConversationWebSearch(id, !current) }
    }

    override fun onCleared() {
        discardPending()
        discardEditDraft()
        super.onCleared()
    }

    /** 拿当前会话；没有就建一个（带上待落库的模型/供应商/角色绑定）。加锁避免并发各建一条空会话。 */
    private suspend fun ensureConversation(): String = conversationLock.withLock {
        conversationId.value?.let { return@withLock it }
        val binding = pendingBinding.value
        val created = repository.createConversation(
            binding?.model,
            binding?.providerId,
            binding?.personaId,
            binding?.webSearchEnabled == true,
        )
        pendingBinding.value = null
        conversationId.value = created
        created
    }

    private fun discardPending() {
        val attachments = pending.value.map { it.attachment }
        if (attachments.isNotEmpty()) attachmentStore.delete(attachments)
        pending.value = emptyList()
    }

    /**
     * 流式幽灵：`clearStreaming` 落在 Room 发射定稿内容之前时，用最后一帧流式内容顶住那一帧，
     * 防止 assistant 渲染塌成 ~0 高、整列钳回用户气泡。纯逻辑见 [StreamGhost]；这里只持有状态。
     */
    private var streamGhost = StreamGhost()

    private fun buildState(
        id: String?,
        conversation: Conversation?,
        messages: List<Message>,
        turn: TurnSnapshot,
        composer: ComposerSnapshot,
    ): ChatUiState {
        val activeStream = id?.let { turn.streaming[it] }
        streamGhost = streamGhost.step(
            conversationId = id,
            activeStream = activeStream,
            // 每行「正文 + 思考」的字符数：幽灵靠它判断 DB 的定稿内容有没有发射到位。
            dbTextLengths = messages.associate { it.id to it.content.length + (it.reasoningContent?.length ?: 0) },
        )
        val overlay = streamGhost.overlay(activeStream)
        val items = messages.map { message ->
            val item = message.toItem()
            if (overlay != null && overlay.messageId == message.id) {
                item.copy(
                    content = overlay.content,
                    reasoning = overlay.reasoning.ifEmpty { null },
                    reasoningMs = overlay.reasoningMs,
                    status = MessageStatus.STREAMING,
                )
            } else {
                item
            }
        }.let { list ->
            if (overlay != null && list.none { it.id == overlay.messageId }) {
                list + ChatMessageItem(
                    id = overlay.messageId,
                    role = Role.ASSISTANT,
                    content = overlay.content,
                    reasoning = overlay.reasoning.ifEmpty { null },
                    reasoningMs = overlay.reasoningMs,
                    status = MessageStatus.STREAMING,
                    model = conversation?.model,
                )
            } else {
                list
            }
        }
        // 视频能不能发由**会话绑定的供应商**决定；迁移后的旧会话为空串 → 跟随激活供应商
        // （与 ChatRepository.resolveConfig 同一条规则）。还没有会话时用「待落库绑定」，
        // 让用户刚选的供应商/模型立刻在界面上生效。
        val providerId = conversation?.providerId?.takeIf { it.isNotBlank() }
            ?: composer.pendingProviderId?.takeIf { it.isNotBlank() }
            ?: composer.activeProviderId
        val model = conversation?.model?.takeIf { it.isNotBlank() }
            ?: composer.pendingModel?.takeIf { it.isNotBlank() }
            ?: composer.defaultModel
        // 角色与供应商同一回退语义：会话绑定 → 待落库 → 激活；已删除的绑定静默跟随激活。
        val rawPersonaId = conversation?.personaId?.takeIf { it.isNotBlank() }
            ?: composer.pendingPersonaId?.takeIf { it.isNotBlank() }
        val personaId = rawPersonaId?.takeIf { it in composer.personas }
            ?: composer.activePersonaId
        return ChatUiState(
            conversationId = id,
            title = conversation?.title ?: "新对话",
            model = model,
            providerId = providerId,
            personaId = personaId,
            personaName = composer.personas[personaId]?.name.orEmpty(),
            messages = items,
            isStreaming = overlay != null,
            streamingMessageId = overlay?.messageId,
            isTurnActive = id != null && turn.busy.contains(id),
            input = composer.input,
            pending = composer.pending,
            defaultModel = composer.defaultModel,
            notice = composer.notice,
            // 还没有会话时用「待落库」的 🌐：新对话里点开也能立刻在界面上生效，首轮发送时落库。
            webSearchEnabled = conversation?.webSearchEnabled == true ||
                (id == null && composer.pendingWebSearch),
            webSearchAvailable = composer.webSearchAvailable,
            videoInputAvailable = ProviderCatalog.supportsVideo(providerId),
            audioInputAvailable = ProviderCatalog.supportsAudio(providerId),
            videoUploadNotice = id?.let { turn.videoUploads[it] },
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
                kind = attachment.kind,
                durationMs = attachment.durationMs,
                displayName = attachment.displayName,
                label = when (attachment.kind) {
                    AttachmentKind.DOCUMENT -> DocumentLabel.of(
                        attachment.mimeType,
                        attachment.displayName ?: attachment.relativePath,
                    )
                    // 音频用字母牌渲染；点开走预览弹层的音频播放页。
                    AttachmentKind.AUDIO -> "AUDIO"
                    else -> null
                },
            )
        },
        attachments = attachments,
        toolResult = toolResult,
    )

    private data class TurnSnapshot(
        val streaming: Map<String, StreamingMessage>,
        val busy: Set<String>,
        val videoUploads: Map<String, String>,
    )

    private data class ComposerSnapshot(
        val input: String,
        val pending: List<PendingAttachment>,
        val notice: String?,
        val defaultModel: String,
        val webSearchAvailable: Boolean,
        /** 还没有会话时用户先开的 🌐；建会话时落库。 */
        val pendingWebSearch: Boolean = false,
        val activeProviderId: String,
        /** 还没有会话时用户先选好的绑定；建会话时落库。 */
        val pendingModel: String? = null,
        val pendingProviderId: String? = null,
        val personas: Map<String, PersonaEntry> = emptyMap(),
        val activePersonaId: String = "",
        val pendingPersonaId: String? = null,
    )

    private data class PendingBinding(
        val model: String?,
        val providerId: String?,
        val personaId: String? = null,
        /** 新会话上先开好的 🌐：建会话时随绑定一起落库。 */
        val webSearchEnabled: Boolean = false,
    )

    companion object {
        fun factory(
            repository: ChatRepository,
            attachmentStore: AttachmentStore,
            settingsRepository: SettingsRepository,
            searchProviders: Map<String, WebSearchProvider> = emptyMap(),
            imageProviders: Map<String, ImageSearchProvider> = emptyMap(),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(repository, attachmentStore, settingsRepository, searchProviders, imageProviders)
            }
        }
    }
}
