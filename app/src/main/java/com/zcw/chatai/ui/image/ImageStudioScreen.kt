package com.zcw.chatai.ui.image

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.zcw.chatai.data.image.openai.OpenAiImageOptions
import com.zcw.chatai.data.image.qwen.QwenImageOptions
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.ui.chat.AttachmentPreview
import com.zcw.chatai.ui.chat.AttachmentPreviewDialog
import com.zcw.chatai.ui.chat.ChatMessageItem
import com.zcw.chatai.ui.chat.ChatMetrics
import com.zcw.chatai.ui.chat.DarkSheet
import com.zcw.chatai.ui.chat.IconBareButton
import com.zcw.chatai.ui.chat.MessageImage
import com.zcw.chatai.ui.chat.MessageImageRow
import com.zcw.chatai.ui.chat.SheetAction
import com.zcw.chatai.ui.chat.clearFocusOnTapOutside
import com.zcw.chatai.ui.chat.rememberBitmap
import com.zcw.chatai.ui.chat.saveAttachmentToGallery
import com.zcw.chatai.ui.theme.ChatTheme
import java.io.File
import kotlinx.coroutines.launch

private data class ImagePreviewTarget(val images: List<MessageImage>, val index: Int)

/**
 * 生图页（全屏）：聊天启发式的图像生成/编辑。用户轮是「提示词 + 自己传的图」，
 * 助手轮是生成出来的大图；上一轮生成结果由仓库静默带入为图 1，界面上看不到。
 */
@Composable
fun ImageStudioScreen(
    state: ImageUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAddImage: (Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOptimize: () -> Unit,
    onOpenConversations: () -> Unit,
    onNewConversation: () -> Unit,
    onSelectModel: (String, String) -> Unit,
    onRefreshModels: () -> Unit,
    onOpenAiImage: (OpenAiImageOptions) -> Unit,
    onQwenImage: (QwenImageOptions) -> Unit,
    onPromptExtend: (Boolean) -> Unit,
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
    val composerRect = remember { mutableStateOf(Rect.Zero) }
    var previewTarget by remember { mutableStateOf<ImagePreviewTarget?>(null) }
    var actionTarget by remember { mutableStateOf<ChatMessageItem?>(null) }
    var attachOpen by remember { mutableStateOf(false) }
    var modelPickerOpen by remember { mutableStateOf(false) }

    fun openPreview(images: List<MessageImage>, tapped: MessageImage) {
        val previewable = AttachmentPreview.previewable(images)
        val index = AttachmentPreview.indexOf(images, tapped.id)
        if (index >= 0) previewTarget = ImagePreviewTarget(previewable, index)
    }

    fun saveToGallery(image: MessageImage) {
        scope.launch {
            val ok = saveAttachmentToGallery(context, image)
            Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(3),
    ) { uris -> uris.forEach(onAddImage) }

    var captureUriText by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) {
            captureUriText?.let { text -> runCatching { Uri.parse(text) }.getOrNull()?.let(onAddImage) }
        }
        captureUriText = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .imePadding()
            // 点输入框以外的地方即失焦（收键盘、外环熄灭）；输入区矩形由下面量出来。
            .clearFocusOnTapOutside { composerRect.value },
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
                text = state.title.ifBlank { "新图像" },
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
                contentDescription = "新图像",
                onClick = onNewConversation,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            // 横向不留白：生成结果要按「视窗宽 94%」铺开；需要内边距的条目自己加。
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.messages.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "输入提示词开始生成；也可以带上一张图做编辑（改文字、加删元素、换风格…）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 48.dp),
                    )
                }
            }
            items(items = state.messages, key = { it.id }) { message ->
                when (message.role) {
                    Role.USER -> UserImageTurn(message, onOpenImage = { openPreview(message.images, it) })
                    else -> AssistantImageTurn(
                        message = message,
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
                text = "生图需要${state.providerLabel}的 API Key：请到「设置 → 服务商」配置。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.accentAmber,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        ImageComposer(
            value = state.input,
            onValueChange = onInputChange,
            model = state.model,
            isBusy = state.isBusy,
            rewriting = state.rewriting,
            canSend = state.canSend,
            pending = state.pending,
            // 发送后立刻收回输入法（与对话页一致），别让键盘挡住刚生成的结果。
            onSend = {
                onSend()
                focusManager.clearFocus()
                keyboardController?.hide()
            },
            onStop = onStop,
            onAttachClick = { attachOpen = true },
            onRemoveAttachment = onRemoveAttachment,
            onOptimize = onOptimize,
            onModelClick = {
                modelPickerOpen = true
                onRefreshModels()
            },
            focusRequester = composerFocus,
            modifier = Modifier
                .onGloballyPositioned { composerRect.value = it.boundsInParent() }
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
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

    if (modelPickerOpen) {
        ImageModelSheet(
            state = state,
            onDismiss = { modelPickerOpen = false },
            onSelectModel = onSelectModel,
            onRefreshModels = onRefreshModels,
            onOpenAiImage = onOpenAiImage,
            onQwenImage = onQwenImage,
            onPromptExtend = onPromptExtend,
        )
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
        val image = target.images.lastOrNull { it.kind == AttachmentKind.IMAGE }
        DarkSheet(onDismiss = { actionTarget = null }) {
            if (image != null) {
                SheetAction(
                    label = "保存到相册",
                    onClick = {
                        saveToGallery(image)
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

@Composable
private fun UserImageTurn(
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
private fun AssistantImageTurn(
    message: ChatMessageItem,
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
                text = "生成中…",
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
                text = "已停止生成",
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
            if (message.images.isNotEmpty()) {
                GeneratedImages(
                    images = message.images,
                    onOpen = onOpenImage,
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

/** 生成结果：视窗宽 94%、居中、按原图比例撑高。多张时纵向排列，每张同样尺寸。 */
@Composable
private fun GeneratedImages(
    images: List<MessageImage>,
    onOpen: (MessageImage) -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        images.forEach { image ->
            GeneratedImage(
                image = image,
                onOpen = { onOpen(image) },
                onLongPress = onLongPress,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GeneratedImage(
    image: MessageImage,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = ChatTheme.colors
    val bitmap = rememberBitmap(image.fullPath, maxEdge = 2048)
    // 宽高比：附件里存了原始像素尺寸；万一缺失就退回 1:1。
    val aspect = if (image.width > 0 && image.height > 0) {
        image.width.toFloat() / image.height.toFloat()
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .fillMaxWidth(ChatMetrics.GENERATED_IMAGE_WIDTH_FRACTION)
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceSoft)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "生成结果",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 拍照落点 URI（与对话页同一套 FileProvider 规则）。 */
private fun createCaptureUri(context: android.content.Context): Uri? = try {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
} catch (t: Throwable) {
    null
}
