package com.zcw.chatai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zcw.chatai.ChatAiApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.zcw.chatai.ui.branch.BranchScreen
import com.zcw.chatai.ui.chat.ChatScreen
import com.zcw.chatai.ui.chat.ChatViewModel
import com.zcw.chatai.ui.chat.MessageEditActions
import com.zcw.chatai.ui.chat.ModelPickerSheet
import com.zcw.chatai.ui.drawer.ConversationListScreen
import com.zcw.chatai.ui.drawer.ConversationTree
import com.zcw.chatai.ui.settings.SettingsScreen

@Composable
fun ChatAiRoot(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as ChatAiApp
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(
            repository = app.chatRepository,
            attachmentStore = app.attachmentStore,
            settingsRepository = app.settingsRepository,
            dataBackup = app.dataBackup,
            searchProviders = app.webSearchProviders,
            imageProviders = app.imageSearchProviders,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val branchParent by viewModel.branchParent.collectAsStateWithLifecycle()
    val conversations by viewModel.searchResults.collectAsStateWithLifecycle()
    // 分支页与「下辖分支（N）」都要用**未过滤**的全量会话：带搜索过滤的列表会让父子关系缺失
    //（树不完整，长按菜单里的入口也会凭空消失）。
    val allConversations by viewModel.conversations.collectAsStateWithLifecycle()
    val branchCounts = remember(allConversations) { ConversationTree.childCounts(allConversations) }
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val editDraft by viewModel.editDraft.collectAsStateWithLifecycle()
    val autoFocusComposer by viewModel.autoFocusComposer.collectAsStateWithLifecycle()
    val streamHaptic by app.settingsRepository.settings
        .map { it.streamHaptic }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = true)
    var showSettings by remember { mutableStateOf(false) }
    var showConversations by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    /** 非空时叠在最上面显示分支页（起点为这条会话）。 */
    var branchRootId by remember { mutableStateOf<String?>(null) }

    // 编辑弹层的回调打包一次构建：ChatScreen 的参数已经很多，不再逐个往下传。
    val editActions = remember(viewModel) {
        MessageEditActions(
            onBegin = viewModel::beginEdit,
            onTextChange = viewModel::setEditText,
            onRemoveAttachment = viewModel::removeEditAttachment,
            onAddAttachments = viewModel::addEditAttachments,
            onModelChange = viewModel::setEditModel,
            onProviderChange = viewModel::setEditProvider,
            onToggleWebSearch = viewModel::toggleEditWebSearch,
            onResend = viewModel::resendEdit,
            onDismiss = viewModel::dismissEdit,
            onNoticeShown = viewModel::consumeNotice,
        )
    }

    // 只有聊天主界面（无设置/会话列表/模型选择/编辑弹层/分支页）才允许自动唤键盘；任何其他界面都不唤醒。
    val chatSurfaceActive = !showSettings && !showConversations && !showModelPicker &&
        editDraft == null && branchRootId == null

    BackHandler(enabled = showSettings && !showConversations) { showSettings = false }
    BackHandler(enabled = showConversations) { showConversations = false }
    // 分支页自己注册返回（先逐层退回、再关页面），见 BranchScreen；这里不重复注册。

    // 关闭列表一律连搜索词一起清掉：否则下次打开会看到「没有搜索框却已被过滤」的列表。
    val closeConversations = {
        showConversations = false
        viewModel.setSearchQuery("")
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (showSettings) {
            SettingsScreen(onBack = { showSettings = false })
        } else {
            ChatScreen(
                state = state,
                onInputChange = viewModel::setInput,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onContinue = viewModel::continueTurn,
                onRetry = viewModel::retry,
                onRegenerate = viewModel::regenerate,
                onDeleteMessage = viewModel::deleteMessage,
                onBranch = viewModel::branchConversation,
                onSwitchConversation = viewModel::selectConversation,
                onNewConversation = viewModel::newConversation,
                onClearConversation = viewModel::clearConversation,
                onOpenSettings = { showSettings = true },
                onOpenConversations = { showConversations = true },
                onAddImage = viewModel::addAttachment,
                onAddVideo = viewModel::addVideo,
                onAddDocument = viewModel::addDocument,
                onAddAudio = viewModel::addAudio,
                onRemoveAttachment = viewModel::removeAttachment,
                onModelClick = { showModelPicker = true },
                onToggleWebSearch = viewModel::toggleWebSearch,
                editDraft = editDraft,
                editActions = editActions,
                onNoticeShown = viewModel::consumeNotice,
                autoFocusComposer = autoFocusComposer,
                onAutoFocusComposerConsumed = viewModel::consumeAutoFocusComposer,
                composerFocusAllowed = chatSurfaceActive,
                branchParent = branchParent,
                streamHapticsEnabled = streamHaptic && chatSurfaceActive,
            )
            if (showModelPicker) {
                ModelPickerSheet(
                    currentModel = state.model,
                    providerId = state.providerId,
                    personaId = state.personaId,
                    onDismiss = { showModelPicker = false },
                    onSelect = { model ->
                        viewModel.setModel(model)
                        showModelPicker = false
                    },
                    onSelectProvider = viewModel::setProvider,
                    onSelectPersona = viewModel::setPersona,
                    onOpenSettings = {
                        showModelPicker = false
                        showSettings = true
                    },
                )
            }
        }

        // 全屏会话列表：从左侧滑入/滑回左侧（与页内「左滑收起」同一方向）。
        // 偏移量必须显式给满宽：`slideIn/OutHorizontally` 的默认值只有 `-it / 2`，会滑一半就停。
        AnimatedVisibility(
            visible = showConversations,
            enter = slideInHorizontally(
                animationSpec = tween(LIST_SLIDE_IN_MS),
                initialOffsetX = { -it },
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(LIST_SLIDE_OUT_MS),
                targetOffsetX = { -it },
            ),
        ) {
            ConversationListScreen(
                visible = showConversations,
                conversations = conversations,
                branchCounts = branchCounts,
                selectedId = state.conversationId,
                searchQuery = searchQuery,
                onSearchQueryChange = viewModel::setSearchQuery,
                onSelect = { id ->
                    viewModel.selectConversation(id)
                    closeConversations()
                },
                onNew = {
                    viewModel.newConversation()
                    closeConversations()
                },
                onRename = viewModel::renameConversation,
                onDelete = viewModel::deleteConversation,
                onOpenBranches = { id -> branchRootId = id },
                onOpenSettings = {
                    closeConversations()
                    showSettings = true
                },
                onClose = closeConversations,
            )
        }

        // 分支页叠在会话列表之上：关掉它回到列表（入口就在列表里，方便接着看别的会话）；
        // 只有「打开某条分支」才把列表一起收掉、回到聊天页。
        val branchRoot = branchRootId
        if (branchRoot != null) {
            BranchScreen(
                conversations = allConversations,
                rootId = branchRoot,
                onOpen = { id ->
                    viewModel.selectConversation(id)
                    branchRootId = null
                    closeConversations()
                },
                onClose = { branchRootId = null },
            )
        }
    }
}

private const val LIST_SLIDE_IN_MS = 260
private const val LIST_SLIDE_OUT_MS = 200
