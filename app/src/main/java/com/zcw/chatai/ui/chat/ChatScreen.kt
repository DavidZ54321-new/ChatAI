package com.zcw.chatai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.ui.theme.ChatTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: (String) -> Unit,
    onRegenerate: (String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onNewConversation: () -> Unit,
    onClearConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var actionTarget by remember { mutableStateOf<ChatMessageItem?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    val messages = state.messages
    val lastMessage = messages.lastOrNull()
    val lastAssistantId = messages.lastOrNull { it.role == Role.ASSISTANT }?.id

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || (
                last.index >= info.totalItemsCount - 1 &&
                    last.offset + last.size <= info.viewportEndOffset + 48
                )
        }
    }

    LaunchedEffect(messages.size, lastMessage?.id) {
        if (messages.isNotEmpty()) {
            repeat(6) {
                listState.scrollToItem(messages.lastIndex)
                delay(100)
            }
        }
    }

    LaunchedEffect(lastMessage?.content?.length, state.isStreaming) {
        if (messages.isNotEmpty() && atBottom) {
            if (state.isStreaming) {
                listState.scrollToItem(messages.lastIndex)
            } else {
                listState.animateScrollToItem(messages.lastIndex)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.canvas).imePadding()) {
        if (messages.isEmpty()) {
            EmptyChatState(
                onSuggestionClick = onInputChange,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = topInset + 68.dp, bottom = 180.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    when (message.role) {
                        Role.USER -> UserMessageItem(
                            message = message,
                            onLongPress = { actionTarget = message },
                            onCopy = { clipboard.copy(message.content) },
                            onDelete = { onDeleteMessage(message.id) },
                        )

                        else -> AiMessageItem(
                            message = message,
                            isStreaming = state.isStreaming && message.id == state.streamingMessageId,
                            meta = if (message.id == lastAssistantId) metaOf(message) else null,
                            onLongPress = { actionTarget = message },
                            onRetry = { onRetry(message.id) },
                            onCopy = { clipboard.copy(message.content) },
                            onRegenerate = { onRegenerate(message.id) },
                            onDelete = { onDeleteMessage(message.id) },
                        )
                    }
                }
                item(key = "disclaimer") { Disclaimer() }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(150.dp)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, colors.canvas)),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(110.dp)
                .background(
                    Brush.verticalGradient(listOf(colors.canvas, Color.Transparent)),
                ),
        )
        FloatingTopControls(
            onOpenDrawer = onOpenDrawer,
            onNewConversation = onNewConversation,
            onOverflow = { overflowOpen = true },
            modifier = Modifier.align(Alignment.TopCenter),
        )
        if (messages.isNotEmpty() && !atBottom) {
            ScrollToBottomButton(
                onClick = {
                    scope.launch { listState.animateScrollToItem(messages.lastIndex) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 170.dp),
            )
        }
        Composer(
            value = state.input,
            onValueChange = onInputChange,
            model = state.model,
            isStreaming = state.isStreaming,
            canSend = state.canSend,
            onSend = onSend,
            onStop = onStop,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }

    val target = actionTarget
    if (target != null) {
        MessageActionsSheet(
            canRegenerate = target.role == Role.ASSISTANT,
            onDismiss = { actionTarget = null },
            onCopy = {
                clipboard.copy(target.content)
                actionTarget = null
            },
            onRegenerate = {
                onRegenerate(target.id)
                actionTarget = null
            },
            onDelete = {
                onDeleteMessage(target.id)
                actionTarget = null
            },
        )
    }

    if (overflowOpen) {
        ChatOverflowSheet(
            onDismiss = { overflowOpen = false },
            onClearConversation = {
                overflowOpen = false
                onClearConversation()
            },
            onOpenSettings = {
                overflowOpen = false
                onOpenSettings()
            },
        )
    }
}

@Composable
private fun Disclaimer() {
    Text(
        text = "ChatAI 是 AI，可能会出错。",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 8.dp),
    )
}

private fun metaOf(message: ChatMessageItem): String? {
    val parts = buildList {
        message.model?.takeIf { it.isNotBlank() }?.let { add(it) }
        message.completionTokens?.let { add("$it tokens") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun ClipboardManager.copy(text: String) {
    if (text.isNotEmpty()) setText(AnnotatedString(text))
}
