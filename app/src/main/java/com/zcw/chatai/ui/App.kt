package com.zcw.chatai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zcw.chatai.ChatAiApp
import com.zcw.chatai.ui.chat.ChatScreen
import com.zcw.chatai.ui.chat.ChatViewModel
import com.zcw.chatai.ui.chat.ModelPickerSheet
import com.zcw.chatai.ui.drawer.ConversationDrawer
import com.zcw.chatai.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun ChatAiRoot(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as ChatAiApp
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(
            repository = app.chatRepository,
            attachmentStore = app.attachmentStore,
            settingsRepository = app.settingsRepository,
            searchProvider = app.webSearchProvider,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    BackHandler(enabled = showSettings) { showSettings = false }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false }, modifier = modifier)
    } else {
        ConversationDrawer(
            conversations = conversations,
            selectedId = state.conversationId,
            currentModel = state.model,
            drawerState = drawerState,
            scope = scope,
            onSelect = viewModel::selectConversation,
            onNew = viewModel::newConversation,
            onRename = viewModel::renameConversation,
            onDelete = viewModel::deleteConversation,
            onOpenSettings = { showSettings = true },
            modifier = modifier,
        ) {
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
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onAddImage = viewModel::addAttachment,
                onRemoveAttachment = viewModel::removeAttachment,
                onModelClick = { showModelPicker = true },
                onToggleWebSearch = viewModel::toggleWebSearch,
                onNoticeShown = viewModel::consumeNotice,
            )
        }
        if (showModelPicker) {
            ModelPickerSheet(
                currentModel = state.model,
                onDismiss = { showModelPicker = false },
                onSelect = { model ->
                    viewModel.setModel(model)
                    showModelPicker = false
                },
                onOpenSettings = {
                    showModelPicker = false
                    showSettings = true
                },
            )
        }
    }
}
