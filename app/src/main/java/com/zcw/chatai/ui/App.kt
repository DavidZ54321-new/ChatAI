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
import com.zcw.chatai.ui.chat.ChatScreen
import com.zcw.chatai.ui.chat.ChatViewModel
import com.zcw.chatai.ui.chat.ModelPickerSheet
import com.zcw.chatai.ui.drawer.ConversationListScreen
import com.zcw.chatai.ui.settings.SettingsScreen

@Composable
fun ChatAiRoot(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as ChatAiApp
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(
            repository = app.chatRepository,
            attachmentStore = app.attachmentStore,
            settingsRepository = app.settingsRepository,
            searchProviders = app.webSearchProviders,
            imageProviders = app.imageSearchProviders,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val conversations by viewModel.searchResults.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val autoFocusComposer by viewModel.autoFocusComposer.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showConversations by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    // 只有聊天主界面（无设置/会话列表/模型选择浮层）才允许自动唤键盘；任何其他界面都不唤醒。
    val chatSurfaceActive = !showSettings && !showConversations && !showModelPicker

    BackHandler(enabled = showSettings && !showConversations) { showSettings = false }
    BackHandler(enabled = showConversations) { showConversations = false }

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
                onNewConversation = viewModel::newConversation,
                onClearConversation = viewModel::clearConversation,
                onOpenSettings = { showSettings = true },
                onOpenConversations = { showConversations = true },
                onAddImage = viewModel::addAttachment,
                onAddVideo = viewModel::addVideo,
                onAddDocument = viewModel::addDocument,
                onRemoveAttachment = viewModel::removeAttachment,
                onModelClick = { showModelPicker = true },
                onToggleWebSearch = viewModel::toggleWebSearch,
                onNoticeShown = viewModel::consumeNotice,
                autoFocusComposer = autoFocusComposer,
                onAutoFocusComposerConsumed = viewModel::consumeAutoFocusComposer,
                composerFocusAllowed = chatSurfaceActive,
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
                onOpenSettings = {
                    closeConversations()
                    showSettings = true
                },
                onClose = closeConversations,
            )
        }
    }
}

private const val LIST_SLIDE_IN_MS = 260
private const val LIST_SLIDE_OUT_MS = 200
