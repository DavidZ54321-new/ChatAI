package com.zcw.chatai.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.ui.theme.ChatTheme
import java.io.File

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
    onAddImage: (Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onModelClick: () -> Unit,
    onToggleWebSearch: () -> Unit,
    onNoticeShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val composerRect = remember { mutableStateOf(Rect.Zero) }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var actionTarget by remember { mutableStateOf<ChatMessageItem?>(null) }
    var previewTarget by remember { mutableStateOf<MessageImage?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }

    // 底消散贴 Composer 上沿；顶消散在按钮行内实心，只在按钮下沿淡出。
    // 底部留白跟 Composer 实测高度走：待发图把它撑高时，最后一条消息不会被盖住。
    var composerHeight by remember { mutableIntStateOf(0) }
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val composerDp = with(LocalDensity.current) { composerHeight.toDp() }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topBand = ChatMetrics.topDissolve(windowHeight, topInset)
    val bottomBand = ChatMetrics.bottomDissolve(windowHeight, composerDp + bottomInset + 10.dp)

    val messages = state.messages
    val lastAssistantId = messages.lastOrNull { it.role == Role.ASSISTANT }?.id

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(8),
    ) { uris -> uris.forEach(onAddImage) }

    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒了前台服务仍能保活，只是自定义通知不可见。 */ }

    fun requestNotifyIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun sendKeepingAlive() {
        onSend()
        requestNotifyIfNeeded()
    }

    fun regenerateKeepingAlive(id: String) {
        onRegenerate(id)
        requestNotifyIfNeeded()
    }

    fun retryKeepingAlive(id: String) {
        onRetry(id)
        requestNotifyIfNeeded()
    }

    // 拍照目标 URI 必须活过进程重建：相机在前台时我们的进程被杀是很常见的，
    // 只放在 remember 里会丢结果，表现为「确认后什么都没发生」。
    var captureUriText by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) {
            captureUriText?.let { text -> runCatching { Uri.parse(text) }.getOrNull()?.let(onAddImage) }
        }
        captureUriText = null
    }

    val follow = rememberChatListFollow(
        listState = listState,
        conversationId = state.conversationId,
        messages = messages,
        isStreaming = state.isStreaming,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .imePadding()
            .clearFocusOnTapOutside { composerRect.value },
    ) {
        if (messages.isEmpty()) {
            EmptyChatState(
                onSuggestionClick = onInputChange,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().nestedScroll(follow.nestedScrollConnection),
                contentPadding = PaddingValues(
                    // 含顶消散尾巴：停在顶部时第一条气泡在渐变之下，实色。
                    top = topBand.height,
                    // 静止时最后一行停在底引导带上方，正文本身保持实色。
                    bottom = bottomBand.height + 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    val previousRole = messages.getOrNull(index - 1)?.role
                    val turnGap = if (previousRole != null && previousRole != message.role) 18.dp else 0.dp
                    Box(Modifier.padding(top = turnGap)) {
                        when (message.role) {
                            Role.USER -> UserMessageItem(
                                message = message,
                                onLongPress = { actionTarget = message },
                                onCopy = { clipboard.copy(message.content) },
                                onRegenerate = { regenerateKeepingAlive(message.id) },
                                onDelete = { onDeleteMessage(message.id) },
                                onOpenImage = { previewTarget = it },
                            )

                            Role.TOOL -> Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                            ) {
                                message.toolResult?.let { ToolCallBlock(it) }
                            }

                            else -> AiMessageItem(
                                message = message,
                                isStreaming = state.isStreaming && message.id == state.streamingMessageId,
                                meta = if (message.id == lastAssistantId) metaOf(message) else null,
                                onLongPress = { actionTarget = message },
                                onRetry = { retryKeepingAlive(message.id) },
                                onCopy = { clipboard.copy(message.content) },
                                onRegenerate = { regenerateKeepingAlive(message.id) },
                                onDelete = { onDeleteMessage(message.id) },
                            )
                        }
                    }
                }
                item(key = "disclaimer") { Disclaimer() }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomBand.height)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            bottomBand.opaqueStop to colors.canvas,
                            1f to colors.canvas,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(topBand.height)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to colors.canvas,
                            topBand.opaqueStop to colors.canvas,
                            1f to Color.Transparent,
                        ),
                    ),
                ),
        )
        FloatingTopControls(
            onOpenDrawer = onOpenDrawer,
            onNewConversation = onNewConversation,
            onOverflow = { overflowOpen = true },
            modifier = Modifier.align(Alignment.TopCenter),
        )
        if (messages.isNotEmpty() && !follow.atBottom) {
            ScrollToBottomButton(
                onClick = follow.scrollToEnd,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 170.dp),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth()
                .onGloballyPositioned { composerRect.value = it.boundsInParent() }
                .onSizeChanged { composerHeight = it.height },
        ) {
            Composer(
                value = state.input,
                onValueChange = onInputChange,
                model = state.model,
                isTurnActive = state.isTurnActive,
                canSend = state.canSend,
                pending = state.pending,
                onSend = { sendKeepingAlive() },
                onStop = onStop,
                onAddImage = { attachOpen = true },
                onRemoveAttachment = onRemoveAttachment,
                onModelClick = onModelClick,
                webSearchEnabled = state.webSearchEnabled,
                webSearchAvailable = state.webSearchAvailable,
                onToggleWebSearch = onToggleWebSearch,
            )
        }
        val notice = state.notice
        if (notice != null) {
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 132.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.accentAmber.copy(alpha = 0.22f))
                    .clickable(onClick = onNoticeShown)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }

    val target = actionTarget
    if (target != null) {
        MessageActionsSheet(
            canRegenerate = target.role == Role.ASSISTANT || target.role == Role.USER,
            onDismiss = { actionTarget = null },
            onCopy = {
                clipboard.copy(target.content)
                actionTarget = null
            },
            onRegenerate = {
                regenerateKeepingAlive(target.id)
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

    if (attachOpen) {
        DarkSheet(onDismiss = { attachOpen = false }) {
            SheetAction(
                label = "从相册选择",
                onClick = {
                    attachOpen = false
                    pickImages.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
            SheetAction(
                label = "拍照",
                onClick = {
                    attachOpen = false
                    val uri = createCaptureUri(context)
                    captureUriText = uri?.toString()
                    if (uri != null) takePicture.launch(uri)
                },
            )
        }
    }

    val preview = previewTarget
    if (preview != null) {
        ImagePreviewDialog(image = preview, onDismiss = { previewTarget = null })
    }
}

private fun createCaptureUri(context: android.content.Context): Uri? = try {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
} catch (t: Throwable) {
    null
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
        message.reasoningTokens?.takeIf { it > 0 }?.let { add("思考 $it") }
        message.cachedTokens?.takeIf { it > 0 }?.let { add("缓存 $it") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun ClipboardManager.copy(text: String) {
    if (text.isNotEmpty()) setText(AnnotatedString(text))
}
