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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zcw.chatai.R
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.ui.md.LocalPreviewOpener
import com.zcw.chatai.ui.md.PreviewTarget
import com.zcw.chatai.ui.theme.ChatTheme
import java.io.File

/** 附件预览目标：这条用户消息里可预览的附件 + 起始下标（左右滑的整组）。 */
private data class AttachmentPreviewTarget(val images: List<MessageImage>, val index: Int)

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
    /** 从某条 AI 回复签出分支（复制该条及之前的消息到新会话）。 */
    onBranch: (String) -> Unit,
    /** 切到指定会话（分支横幅点回来源会话用）。 */
    onSwitchConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onClearConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConversations: () -> Unit,
    onAddImage: (Uri) -> Unit,
    onAddVideo: (Uri) -> Unit,
    onAddDocument: (Uri) -> Unit,
    onAddAudio: (Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onModelClick: () -> Unit,
    onToggleWebSearch: () -> Unit,
    /** 编辑用户消息的草稿与回调（会话级语义：模型/供应商/🌐 写回会话，见 [MessageEditActions]）。 */
    editDraft: EditDraft?,
    editActions: MessageEditActions,
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
    var previewTarget by remember { mutableStateOf<AttachmentPreviewTarget?>(null) }
    var documentTarget by remember { mutableStateOf<MessageImage?>(null) }
    // SVG/HTML 全屏 viewer 目标：Dialog 随开随建、退出即销毁，列表里不驻留 WebView。
    var previewPage by remember { mutableStateOf<PreviewTarget?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    var confirmResend by remember { mutableStateOf(false) }
    // 分支确认：点的是哪条 AI 回复，确认后才真的复制（破坏性小但不可逆，值得问一次）。
    var branchTarget by remember { mutableStateOf<ChatMessageItem?>(null) }

    // 只有聊天主界面可见、且没有被任何浮层遮挡时才允许唤键盘：
    // 设置页 / 会话列表 / 模型选择（上层标志）与附件面板 / 溢出菜单 / 消息操作 / 图片预览（本地浮层）一律不唤醒。
    // 编辑弹层也算浮层：从系统相册选完附件回来时不该把键盘飘到 Composer 上。
    val focusAllowedNow = rememberUpdatedState(
        composerFocusAllowed && !attachOpen && !overflowOpen &&
        actionTarget == null && previewTarget == null && documentTarget == null && previewPage == null &&
            editDraft == null && !confirmResend && branchTarget == null,
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

    val follow = rememberChatListFollow(
        listState = listState,
        conversationId = state.conversationId,
        messages = messages,
        isStreaming = state.isStreaming,
    )

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(8),
    ) { uris -> uris.forEach(onAddImage) }

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onAddVideo) }

    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onAddDocument) }

    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onAddAudio) }

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
        // 显式跳到底：「发出 → 看到自己的消息和回答」，不依赖列表形状的巧合。
        follow.jumpToBottom()
        // 按发送键就收键盘：Started/Busy/Rejected 都收（用户意图是「发出去」）。
        focusManager.clearFocus()
        keyboardController?.hide()
        requestNotifyIfNeeded()
    }

    fun regenerateKeepingAlive(id: String) {
        onRegenerate(id)
        follow.jumpToBottom()
        requestNotifyIfNeeded()
    }

    fun retryKeepingAlive(id: String) {
        onRetry(id)
        follow.jumpToBottom()
        requestNotifyIfNeeded()
    }

    fun continueKeepingAlive() {
        onContinue()
        follow.jumpToBottom()
        requestNotifyIfNeeded()
    }

    fun resendKeepingAlive() {
        editActions.onResend()
        follow.jumpToBottom()
        requestNotifyIfNeeded()
    }

    fun openPendingAttachment(item: PendingAttachment, attachments: List<PendingAttachment>) {
        val images = attachments.map { it.toMessageImage(context.filesDir) }
        if (item.attachment.kind == AttachmentKind.DOCUMENT) {
            documentTarget = images.firstOrNull { it.id == item.id }
        } else {
            val previewable = AttachmentPreview.previewable(images)
            val index = AttachmentPreview.indexOf(previewable, item.id)
            if (index >= 0) previewTarget = AttachmentPreviewTarget(previewable, index)
        }
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
                EmptyChatState(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        // 手指一碰就停跟随：跟手指抢滚动会把跳转打断在半路（用户气泡上）。
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    var event = awaitPointerEvent()
                                    if (event.changes.none { it.pressed }) continue
                                    follow.unpin()
                                    while (event.changes.any { it.pressed }) {
                                        event = awaitPointerEvent()
                                    }
                                }
                            }
                        }
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
                    // 分支会话的说明条：列表最上面一行，点一下回到来源会话。
                    state.branchParentId?.let { parentId ->
                        item(key = "branch-header") {
                            BranchHeader(
                                parentTitle = state.branchParentTitle,
                                onClick = { onSwitchConversation(parentId) },
                            )
                        }
                    }
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
                                    // 点气泡进编辑弹层；能不能编辑（生成中/非用户消息）由 VM 判。
                                    onClick = { editActions.onBegin(group.items.single().id) },
                                    onOpenImage = { tapped ->
                                        if (tapped.kind == com.zcw.chatai.data.model.AttachmentKind.DOCUMENT) {
                                            documentTarget = tapped
                                        } else {
                                            val images = group.items.single().images
                                            val index = AttachmentPreview.indexOf(images, tapped.id)
                                            if (index >= 0) {
                                                previewTarget = AttachmentPreviewTarget(
                                                    images = AttachmentPreview.previewable(images),
                                                    index = index,
                                                )
                                            }
                                        }
                                    },
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
                                    onBranch = { branchTarget = it },
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
                    onOpenAttachment = { item -> openPendingAttachment(item, state.pending) },
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
            onEdit = if (target.role == Role.USER) {
                {
                    editActions.onBegin(target.id)
                    actionTarget = null
                }
            } else {
                null
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

    // 编辑弹层：破坏性截断先确认（只有后面还有消息时才问一次）。
    val draft = editDraft
    if (draft != null) {
        MessageEditSheet(
            draft = draft,
            webSearchAvailable = state.webSearchAvailable,
            // 弹层挡着主界面的提示条：把同一份文案搬进来，否则「点了没反应」。
            notice = state.notice,
            actions = editActions,
            onOpenAttachment = { item -> openPendingAttachment(item, draft.attachments) },
            onResend = { if (draft.laterCount > 0) confirmResend = true else resendKeepingAlive() },
        )
    }
    if (draft != null && confirmResend) {
        AlertDialog(
            onDismissRequest = { confirmResend = false },
            title = { Text("重新发送？") },
            text = { Text("这条消息之后的 ${draft.laterCount} 轮对话会被删除，且无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmResend = false
                        resendKeepingAlive()
                    },
                ) {
                    Text("重新发送")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmResend = false }) { Text("取消") }
            },
        )
    }

    // 分支确认：复制是不可逆的（新会话会多出一份消息与附件），先问一次。
    val branching = branchTarget
    if (branching != null) {
        AlertDialog(
            onDismissRequest = { branchTarget = null },
            title = { Text("从此处创建分支？") },
            text = {
                Text(
                    "会把这条 AI 回复以及它之前的全部聊天记录复制到一个新的分支会话，" +
                        "并切换过去继续对话。附件也会复制一份，原会话不受影响。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        branchTarget = null
                        onBranch(branching.id)
                    },
                ) { Text("创建分支") }
            },
            dismissButton = {
                TextButton(onClick = { branchTarget = null }) { Text("取消") }
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
            if (state.audioInputAvailable) {
                SheetAction(
                    label = "选择音频（MP3 / WAV / M4A / OGG / FLAC）",
                    onClick = {
                        attachOpen = false
                        suppressResumeFocus = true
                        pickAudio.launch(AUDIO_MIME_TYPES)
                    },
                )
            }
        }
    }

    val preview = previewTarget
    if (preview != null) {
        AttachmentPreviewDialog(
            images = preview.images,
            initialIndex = preview.index,
            onDismiss = { previewTarget = null },
        )
    }

    val document = documentTarget
    if (document != null) {
        DocumentPreviewDialog(document = document, onDismiss = { documentTarget = null })
    }

    val page = previewPage
    if (page != null) {
        PreviewViewerDialog(target = page, onDismiss = { previewPage = null })
    }
}

/** 文档选择器的 MIME 过滤（与 DocumentKind 首期范围对齐）。 */
internal val DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "text/plain",
    "text/markdown",
    "text/csv",
    "application/json",
)

/**
 * 音频选择器的 MIME 过滤（与 MiMo 支持的格式对齐：MP3/WAV/FLAC/M4A/OGG）。
 * 必须与 `AttachmentStore.audioExtension()` 认的 MIME 集**完全一致**——
 * OpenDocument 按精确 MIME 过滤，漏一个变体（如 `audio/x-wav`）该文件就选不出来。
 */
internal val AUDIO_MIME_TYPES = arrayOf(
    "audio/mpeg",
    "audio/mp3",
    "audio/wav",
    "audio/x-wav",
    "audio/wave",
    "audio/flac",
    "audio/x-flac",
    "audio/mp4",
    "audio/x-m4a",
    "audio/aac",
    "audio/ogg",
    "application/ogg",
)

private fun createCaptureUri(context: android.content.Context): Uri? = try {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
} catch (t: Throwable) {
    null
}

/**
 * 分支会话的说明条：告诉用户这条会话是从哪儿签出来的，点一下切回来源会话。
 * 放在消息列表最上面（而不是浮在顶栏下方），避免和滚动内容抢位置。
 */
@Composable
private fun BranchHeader(parentTitle: String?, onClick: () -> Unit) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(colors.chipBackground)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_branch),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "分支自「${parentTitle ?: "原会话"}」",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
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
        message.reasoningTokens?.takeIf { it > 0 }?.let { add("思考 $it") }
        message.cachedTokens?.takeIf { it > 0 }?.let { add("缓存 $it") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun ClipboardManager.copy(text: String) {
    if (text.isNotEmpty()) setText(AnnotatedString(text))
}
