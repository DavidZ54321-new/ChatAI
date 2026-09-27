package com.zcw.chatai.ui.video

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.video.VideoInputs
import com.zcw.chatai.data.video.VideoMode
import com.zcw.chatai.data.video.VideoModels
import com.zcw.chatai.ui.chat.AttachmentPreview
import com.zcw.chatai.ui.chat.AttachmentPreviewDialog
import com.zcw.chatai.ui.chat.ChatMessageItem
import com.zcw.chatai.ui.chat.ChatMetrics
import com.zcw.chatai.ui.chat.DarkSheet
import com.zcw.chatai.ui.chat.IconBareButton
import com.zcw.chatai.ui.chat.MessageImage
import com.zcw.chatai.ui.chat.MessageImageRow
import com.zcw.chatai.ui.chat.SheetAction
import com.zcw.chatai.ui.chat.formatDuration
import com.zcw.chatai.ui.chat.rememberVideoFrame
import com.zcw.chatai.ui.chat.saveAttachmentToGallery
import com.zcw.chatai.ui.settings.SwitchRow
import com.zcw.chatai.ui.theme.ChatTheme
import kotlinx.coroutines.launch

private data class VideoPreviewTarget(val images: List<MessageImage>, val index: Int)

/**
 * 视频页（全屏，重新设计）：**顶部**提示词输入区 + **下方**任务与结果列表。
 * 每种状态都在同一块「视频区域」里给出：等待/生成中转圈、失败显示错误文本、成功出视频卡。
 */
@Composable
fun VideoStudioScreen(
    state: VideoUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAddImage: (Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOptimize: () -> Unit,
    onInsertShot: () -> Unit,
    onSetMode: (VideoMode) -> Unit,
    onSetResolution: (String) -> Unit,
    onSetRatio: (String) -> Unit,
    onSetDuration: (Int) -> Unit,
    onSetAudio: (Boolean) -> Unit,
    onOpenConversations: () -> Unit,
    onNewConversation: () -> Unit,
    onSelectModel: (String) -> Unit,
    onRegenerate: (String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onNoticeShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val composerFocus = remember { FocusRequester() }
    var previewTarget by remember { mutableStateOf<VideoPreviewTarget?>(null) }
    var actionTarget by remember { mutableStateOf<ChatMessageItem?>(null) }
    var attachOpen by remember { mutableStateOf(false) }
    var modePickerOpen by remember { mutableStateOf(false) }
    var paramsOpen by remember { mutableStateOf(false) }
    var modelPickerOpen by remember { mutableStateOf(false) }

    fun openPreview(images: List<MessageImage>, tapped: MessageImage) {
        val previewable = AttachmentPreview.previewable(images)
        val index = AttachmentPreview.indexOf(images, tapped.id)
        if (index >= 0) previewTarget = VideoPreviewTarget(previewable, index)
    }

    fun saveToGallery(image: MessageImage) {
        scope.launch {
            val ok = saveAttachmentToGallery(context, image)
            Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(VideoInputs.MAX_REFERENCES),
    ) { uris -> uris.forEach(onAddImage) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBareButton(
                icon = Icons.Filled.Menu,
                contentDescription = "会话列表",
                onClick = onOpenConversations,
            )
            Text(
                text = state.title.ifBlank { "新视频" },
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            IconBareButton(
                icon = Icons.Filled.Add,
                contentDescription = "新视频",
                onClick = onNewConversation,
            )
        }

        VideoComposer(
            value = state.input,
            onValueChange = onInputChange,
            model = state.model,
            params = state.params,
            isBusy = state.isBusy,
            rewriting = state.rewriting,
            canSend = state.canSend,
            pending = state.pending,
            onSend = {
                onSend()
                focusManager.clearFocus()
                keyboardController?.hide()
            },
            onStop = onStop,
            onAttachClick = { attachOpen = true },
            onRemoveAttachment = onRemoveAttachment,
            onOptimize = onOptimize,
            onInsertShot = onInsertShot,
            onModeClick = { modePickerOpen = true },
            onParamsClick = { paramsOpen = true },
            onModelClick = { modelPickerOpen = true },
            focusRequester = composerFocus,
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.messages.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "在上方写提示词或分镜，选择「文生 / 图生 / 参考」，发送后在这里等待生成。" +
                            "生成会后台续跑，切走或杀掉 App 也不会中断。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 40.dp),
                    )
                }
            }
            items(items = state.messages, key = { it.id }) { message ->
                when (message.role) {
                    Role.USER -> UserVideoTurn(message, onOpenImage = { openPreview(message.images, it) })
                    else -> AssistantVideoTurn(
                        message = message,
                        taskLabel = state.taskStatuses[message.id]?.waitingLabel(),
                        onOpenImage = { openPreview(message.images, it) },
                        onLongPress = { actionTarget = message },
                        onRetry = { onRegenerate(message.id) },
                    )
                }
            }
        }

        state.notice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.accentAmber.copy(alpha = 0.22f))
                    .clickable(onClick = onNoticeShown)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        if (!state.available) {
            Text(
                text = "视频生成需要通义千问的 API Key：请到「设置 → 服务商」配置。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.accentAmber,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        Box(Modifier.navigationBarsPadding().height(4.dp))
    }

    if (attachOpen) {
        DarkSheet(onDismiss = { attachOpen = false }) {
            SheetAction(
                label = "从相册选择参考图",
                onClick = {
                    attachOpen = false
                    pickImages.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
        }
    }

    if (modePickerOpen) {
        DarkSheet(onDismiss = { modePickerOpen = false }) {
            SheetTitle("生成模式")
            VideoMode.entries.forEach { mode ->
                SheetAction(
                    label = (if (mode == state.params.mode) "✓ " else "") + mode.label() + " · " + mode.hint(),
                    onClick = {
                        onSetMode(mode)
                        modePickerOpen = false
                    },
                )
            }
        }
    }

    if (paramsOpen) {
        DarkSheet(onDismiss = { paramsOpen = false }, scroll = true) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text(
                    text = "生成参数",
                    style = MaterialTheme.typography.titleMedium,
                    color = ChatTheme.colors.codeOnBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                ParamGroup(
                    label = "分辨率",
                    options = listOf("480P" to "480P", "720P" to "720P", "1080P" to "1080P"),
                    selected = state.params.resolution,
                    onSelect = onSetResolution,
                )
                if (state.params.supportsRatio) {
                    ParamGroup(
                        label = "宽高比",
                        options = state.params.ratioOptions,
                        selected = state.params.ratio,
                        onSelect = onSetRatio,
                    )
                }
                ParamGroup(
                    label = "时长",
                    options = listOf(-1 to "智能时长", 5 to "5s", 10 to "10s", 15 to "15s", 30 to "30s"),
                    selected = state.params.duration,
                    onSelect = onSetDuration,
                )
                SwitchRow(
                    label = "生成有声视频",
                    checked = state.params.audio,
                    onCheckedChange = onSetAudio,
                )
                Box(Modifier.height(12.dp))
            }
        }
    }

    if (modelPickerOpen) {
        DarkSheet(onDismiss = { modelPickerOpen = false }) {
            SheetTitle("视频模型")
            VideoModels.presets.forEach { model ->
                SheetAction(
                    label = if (model == state.model) "✓ $model" else model,
                    onClick = {
                        onSelectModel(model)
                        modelPickerOpen = false
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
            onSave = { saveToGallery(it) },
        )
    }

    val target = actionTarget
    if (target != null) {
        val video = target.images.firstOrNull { it.kind == AttachmentKind.VIDEO }
        DarkSheet(onDismiss = { actionTarget = null }) {
            if (video != null) {
                SheetAction(
                    label = "保存到相册",
                    onClick = {
                        saveToGallery(video)
                        actionTarget = null
                    },
                )
            }
            SheetAction(
                label = "重新生成",
                onClick = {
                    onRegenerate(target.id)
                    actionTarget = null
                },
            )
            SheetAction(
                label = "删除",
                color = colors.accentAmber,
                onClick = {
                    onDeleteMessage(target.id)
                    actionTarget = null
                },
            )
        }
    }
}

/** 参数分组：居中标题 + 居中排列的可选胶囊，组与组之间留白。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ParamGroup(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            options.forEach { (value, text) ->
                val isSelected = value == selected
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) scheme.onPrimary else scheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isSelected) scheme.primary else colors.chipBackground)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = ChatTheme.colors.codeOnBackground,
        modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp),
    )
}

private fun VideoMode.hint(): String = when (this) {
    VideoMode.T2V -> "只写提示词"
    VideoMode.I2V -> "首帧 / 首尾帧"
    VideoMode.R2V -> "参考图"
}

@Composable
private fun UserVideoTurn(
    message: ChatMessageItem,
    onOpenImage: (MessageImage) -> Unit,
) {
    val colors = ChatTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (message.images.isNotEmpty()) {
            MessageImageRow(images = message.images, onOpen = onOpenImage)
        }
        if (message.content.isNotBlank()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.bubbleUser)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.bubbleUserText,
                )
            }
        }
    }
}

@Composable
private fun AssistantVideoTurn(
    message: ChatMessageItem,
    taskLabel: String?,
    onOpenImage: (MessageImage) -> Unit,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    when (message.status) {
        MessageStatus.STREAMING -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = scheme.primary,
            )
            Text(
                text = taskLabel ?: "等待生成视频…",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }

        MessageStatus.ERROR -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = message.errorMessage ?: "生成失败",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.error,
            )
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier.clickable(onClick = onRetry),
            )
        }

        MessageStatus.CANCELLED -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "已取消生成",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = "重新生成",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier.clickable(onClick = onRetry),
            )
        }

        MessageStatus.COMPLETE -> {
            val video = message.images.firstOrNull { it.kind == AttachmentKind.VIDEO }
            if (video != null) {
                GeneratedVideo(
                    video = video,
                    onOpen = { onOpenImage(video) },
                    onLongPress = onLongPress,
                )
            } else if (message.content.isNotBlank()) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/**
 * 生成结果：**居中**，装进「视窗宽 94% × 视窗高 50%」的框（竖屏视频不再顶满整屏），
 * 覆盖首帧 + 播放角标 + 时长。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GeneratedVideo(
    video: MessageImage,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = ChatTheme.colors
    val frame = rememberVideoFrame(video.fullPath, video.thumbnailPath, maxEdge = 1280)
    val window = LocalWindowInfo.current.containerDpSize
    val aspect = if (video.width > 0 && video.height > 0) {
        video.width.toFloat() / video.height.toFloat()
    } else {
        16f / 9f
    }
    val size = ChatMetrics.generatedVideoSize(aspect, window.width, window.height)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size.width, size.height)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceSoft)
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
            contentAlignment = Alignment.Center,
        ) {
            frame?.let {
                Image(
                    bitmap = it,
                    contentDescription = "生成结果",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            video.durationMs?.let { duration ->
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
