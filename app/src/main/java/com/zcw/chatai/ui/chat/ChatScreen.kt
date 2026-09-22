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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.ui.md.LocalPreviewOpener
import com.zcw.chatai.ui.md.PreviewTarget
import com.zcw.chatai.ui.theme.ChatTheme
import java.io.File

@Composable
fun ChatScreen(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onRetry: (String) -> Unit,
    onRegenerate: (String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onNewConversation: () -> Unit,
    onClearConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConversations: () -> Unit,
    onAddImage: (Uri) -> Unit,
    onAddVideo: (Uri) -> Unit,
    onAddDocument: (Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onModelClick: () -> Unit,
    onToggleWebSearch: () -> Unit,
    onNoticeShown: () -> Unit,
    autoFocusComposer: Boolean,
    onAutoFocusComposerConsumed: () -> Unit,
    composerFocusAllowed: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val composerFocus = remember { FocusRequester() }

    var actionTarget by remember { mutableStateOf<ChatMessageItem?>(null) }
    var previewTarget by remember { mutableStateOf<MessageImage?>(null) }
    // SVG/HTML 全屏 viewer 目标：Dialog 随开随建、退出即销毁，列表里不驻留 WebView。
    var previewPage by remember { mutableStateOf<PreviewTarget?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }

    // 只有聊天主界面可见、且没有被任何浮层遮挡时才允许唤键盘：
    // 设置页 / 会话列表 / 模型选择（上层标志）与附件面板 / 溢出菜单 / 消息操作 / 图片预览（本地浮层）一律不唤醒。
    val focusAllowedNow = rememberUpdatedState(
        composerFocusAllowed && !attachOpen && !overflowOpen &&
            actionTarget == null && previewTarget == null && previewPage == null,
    )
    // 聚焦成功才唤键盘：requestFocus 失败（节点已移除）时不弹，避免键盘飘到别的界面上。
    fun focusComposerIfAllowed() {
        if (!focusAllowedNow.value) return
        val focused = runCatching { composerFocus.requestFocus() }.isSuccess
        if (focused && focusAllowedNow.value) keyboardController?.show()
    }
    // 离开聊天主界面前收键盘：避免输入法跟到设置页/列表页上。
    fun hideKeyboardForNavigation() {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    // 冷启动/进程重建的一次性聚焦：等首帧布局（FocusRequester 挂上）再 requestFocus + 唤输入法。
    // 消费后不再触发；从设置页返回（ChatScreen 离开重组）也不会重触发。
    LaunchedEffect(autoFocusComposer) {
        if (autoFocusComposer) {
            withFrameNanos { }
            focusComposerIfAllowed()
            onAutoFocusComposerConsumed()
        }
    }

    // 切后台再回聊天页（含锁屏亮屏）：非流式且主界面可见时聚焦 + 弹键盘，方便粘贴剪贴板。
    // 首次 RESUME 时 everStopped 仍为 false → 跳过，冷启动仍由上面的 autoFocusComposer 负责。
    var everStopped by remember { mutableStateOf(false) }
    var resumeTick by remember { mutableIntStateOf(0) }
    // 系统相册/相机/文档选择器同样是「离开再回来」：从它们返回时不弹键盘（用户刚选完附件）。
    var suppressResumeFocus by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> everStopped = true
                Lifecycle.Event.ON_RESUME -> {
                    if (everStopped) {
                        if (suppressResumeFocus) suppressResumeFocus = false else resumeTick++
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumeTick) {
        if (resumeTick == 0 || state.isTurnActive) return@LaunchedEffect
        withFrameNanos { }
        focusComposerIfAllowed()
    }
    val composerRect = remember { mutableStateOf(Rect.Zero) }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // 底消散贴 Composer 上沿；顶消散在按钮行内实心，只在按钮下沿淡出。
    // 底部留白跟 Composer 实测高度走：待发图把它撑高时，最后一条消息不会被盖住。
    var composerHeight by remember { mutableIntStateOf(0) }
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val composerDp = with(LocalDensity.current) { composerHeight.toDp() }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topBand = ChatMetrics.topDissolve(windowHeight, topInset)
    val bottomBand = ChatMetrics.bottomDissolve(windowHeight, composerDp + bottomInset + 10.dp)

    val messages = state.messages

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(8),
    ) { uris -> uris.forEach(onAddImage) }

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onAddVideo) }

    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onAddDocument) }

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
        // 按发送键就收键盘：Started/Busy/Rejected 都收（用户意图是「发出去」）。
        focusManager.clearFocus()
        keyboardController?.hide()
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

    fun continueKeepingAlive() {
        onContinue()
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
        // 用 remember 固定这个 lambda 的身份：每次重组新建的话，所有读该 local 的
        // 卡片（预览卡）都会跟着流式增量一起重组。
        val previewOpener = remember { { target: PreviewTarget -> previewPage = target } }
        CompositionLocalProvider(LocalPreviewOpener provides previewOpener) {
            if (messages.isEmpty()) {
                EmptyChatState(
                    onSuggestionClick = onInputChange,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(follow.nestedScrollConnection)
                        // 定位门：切会话后先隐藏，等 jumpToEnd 定位完成再显示，避免看到顶部再瞬移。
                        .graphicsLayer { alpha = if (follow.located) 1f else 0f },
                    contentPadding = PaddingValues(
                        // 含顶消散尾巴：停在顶部时第一条气泡在渐变之下，实色。
                        top = topBand.height,
                        // 静止时最后一行停在底引导带上方，正文本身保持实色。
                        bottom = bottomBand.height + 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val groups = MessageGroups.of(messages)
                    itemsIndexed(groups, key = { _, group -> group.key }) { index, group ->
                        val previous = groups.getOrNull(index - 1)
                        val turnGap = if (previous != null && (previous is MessageGroup.User) != (group is MessageGroup.User)) {
                            18.dp
                        } else {
                            0.dp
                        }
                        Box(Modifier.padding(top = turnGap)) {
                            when (group) {
                                is MessageGroup.User -> UserMessageItem(
                                    message = group.items.single(),
                                    onLongPress = { actionTarget = group.items.single() },
                                    onOpenImage = { previewTarget = it },
                                )

                                is MessageGroup.Assistant -> AssistantTurnItem(
                                    group = group.items,
                                    streamingMessageId = state.streamingMessageId,
                                    isCurrentTurn = state.isTurnActive && index == groups.lastIndex,
                                    onUserExpand = follow.unpin,
                                    meta = state.messages.lastOrNull { it.role == Role.ASSISTANT }
                                        ?.takeIf { last -> group.items.any { it.id == last.id } }
                                        ?.let { metaOf(it) },
                                    onLongPress = { actionTarget = it },
                                    onRetry = { retryKeepingAlive(it.id) },
                                    onCopy = { clipboard.copy(it.content) },
                                    onRegenerate = { regenerateKeepingAlive(it.id) },
                                    onDelete = { onDeleteMessage(it.id) },
                                    onContinue = { continueKeepingAlive() },
                                )
                            }
                        }
                    }
                    item(key = "disclaimer") { Disclaimer() }
                }
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
            onOpenConversations = {
                hideKeyboardForNavigation()
                onOpenConversations()
            },
            onNewConversation = onNewConversation,
            onOverflow = { overflowOpen = true },
            modifier = Modifier.align(Alignment.TopCenter),
        )
        if (messages.isNotEmpty() && (!follow.following || !follow.atBottom)) {
            ScrollToBottomButton(
                onClick = follow.jumpToBottom,
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.videoUploadNotice?.let { uploading ->
                    Text(
                        text = uploading,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accentAmber,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Composer(
                    value = state.input,
                    onValueChange = onInputChange,
                    model = state.model,
                    isTurnActive = state.isTurnActive,
                    canSend = state.canSend,
                    pending = state.pending,
                    onSend = { sendKeepingAlive() },
                    onStop = onStop,
                    onAttachClick = { attachOpen = true },
                    onRemoveAttachment = onRemoveAttachment,
                    onModelClick = onModelClick,
                    webSearchEnabled = state.webSearchEnabled,
                    webSearchAvailable = state.webSearchAvailable,
                    onToggleWebSearch = onToggleWebSearch,
                    focusRequester = composerFocus,
                )
            }
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
                hideKeyboardForNavigation()
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
                    suppressResumeFocus = true
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
                    if (uri != null) {
                        suppressResumeFocus = true
                        takePicture.launch(uri)
                    }
                },
            )
            if (state.videoInputAvailable) {
                SheetAction(
                    label = "选择视频（MP4）",
                    onClick = {
                        attachOpen = false
                        suppressResumeFocus = true
                        pickVideo.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                        )
                    },
                )
            }
            SheetAction(
                label = "选择文档（PDF / Word / 表格 / 文本）",
                onClick = {
                    attachOpen = false
                    suppressResumeFocus = true
                    pickDocument.launch(DOCUMENT_MIME_TYPES)
                },
            )
        }
    }

    val preview = previewTarget
    if (preview != null) {
        if (preview.isVideo) {
            VideoPreviewDialog(image = preview, onDismiss = { previewTarget = null })
        } else {
            ImagePreviewDialog(image = preview, onDismiss = { previewTarget = null })
        }
    }

    val page = previewPage
    if (page != null) {
        PreviewViewerDialog(target = page, onDismiss = { previewPage = null })
    }
}

/** 文档选择器的 MIME 过滤（与 DocumentKind 首期范围对齐）。 */
private val DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "text/plain",
    "text/markdown",
    "text/csv",
    "application/json",
)

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
