package com.zcw.chatai.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.ui.theme.ChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 编辑用户消息的弹层：改正文 / 增删附件 / 切 🌐 / 换会话的模型与供应商，然后就地重发。
 *
 * 附件入口是缩略图行末尾的「＋」方块：点它**直接唤起系统选择器**（不再内联展开选项列表）。
 * 选择器的 MIME 过滤按草稿供应商的能力相应扩大（支持音视频就带上），回来的文件按 MIME 归类导入。
 * 模型选择器仍在本弹层内联展开：两层 ModalBottomSheet 叠窗不可靠。
 * 草稿存在 ViewModel 里，所以选择器回来（甚至 Activity 重建）都不丢内容。
 * 不自动聚焦输入框——按 App 的约定，任何浮层都不唤醒键盘。
 */
@Composable
fun MessageEditSheet(
    draft: EditDraft,
    webSearchAvailable: Boolean,
    /** 导入失败 / 重发被拒的提示：弹层挡着主界面，得在这里也能看到。 */
    notice: String?,
    actions: MessageEditActions,
    onOpenAttachment: (PendingAttachment) -> Unit = {},
    /** 是否要先弹截断确认框由调用方决定（ChatScreen 知道这条消息后面还有多少内容）。 */
    onResend: () -> Unit,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    var modelOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // 供应商可能在草稿里被换掉，所以能力门禁按**草稿的**供应商判，而不是会话当前的。
    val videoAvailable = ProviderCatalog.supportsVideo(draft.providerId)
    val audioAvailable = ProviderCatalog.supportsAudio(draft.providerId)

    // 一个系统选择器搞定所有附件；MIME 范围随能力扩大（支持音视频就带上），回来的文件按 MIME 归类。
    val mimeTypes = remember(videoAvailable, audioAvailable) {
        buildList {
            add("image/*")
            // 视频：导入器只收 MP4（AttachmentStore.importVideo → isMp4：MIME 或扩展名），
            // 这里用大类与主输入框的 VideoOnly 口径一致，非 MP4 由导入器给可读报错。
            if (videoAvailable) add("video/*")
            // 音频：必须复用精确列表 —— OpenDocument 按精确 MIME 过滤，列全变体（audio/x-wav 等）
            // 才选得出来，也不会放出导入器不认的格式。
            if (audioAvailable) addAll(AUDIO_MIME_TYPES)
            addAll(DOCUMENT_MIME_TYPES)
        }.toTypedArray()
    }
    val scope = rememberCoroutineScope()
    val pickAttachments = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                // 归类要查 MIME/展示名，放 IO 上做；整批交给 VM 逐个导入（额度逐张判，多选不绕过上限）。
                val picked = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        PickedAttachment(
                            uri = uri,
                            kind = MessageEdit.kindOfMime(mimeOf(context, uri), displayNameOf(context, uri)),
                        )
                    }
                }
                actions.onAddAttachments(picked)
            }
        }
    }

    DarkSheet(onDismiss = actions.onDismiss, scroll = true, fullHeight = true, imeAware = true) {
        Text(
            text = "编辑消息",
            style = MaterialTheme.typography.titleMedium,
            color = colors.codeOnBackground,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        // 破坏性后果先写在明面上，确认框再兜一次。
        if (draft.laterCount > 0) {
            Text(
                text = "重新发送会删除这条消息之后的 ${draft.laterCount} 轮对话",
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentAmber,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.codeButtonBackground)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = draft.text,
                onValueChange = actions.onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp, max = 190.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                maxLines = 8,
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.TopStart) {
                        if (draft.text.isEmpty()) {
                            Text(
                                text = "输入内容…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
        // 附件行始终渲染：末尾的「＋」方块是唯一的附件入口，没有附件时也要看得到。
        PendingAttachmentStrip(
            pending = draft.attachments,
            onRemove = actions.onRemoveAttachment,
            onAdd = { pickAttachments.launch(mimeTypes) },
            onOpen = onOpenAttachment,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
        ) {
            Text(
                text = "联网搜索",
                style = MaterialTheme.typography.labelLarge,
                color = colors.codeOnBackground,
                modifier = Modifier.weight(1f),
            )
            WebSearchToggle(
                enabled = draft.webSearchEnabled,
                available = webSearchAvailable,
                onClick = actions.onToggleWebSearch,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { modelOpen = !modelOpen }
                .padding(horizontal = 24.dp, vertical = 10.dp),
        ) {
            Text(
                text = "模型",
                style = MaterialTheme.typography.labelLarge,
                color = colors.codeOnBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${ProviderCatalog.displayName(draft.providerId)} · " +
                    draft.model.ifBlank { "未选择" },
                style = MaterialTheme.typography.labelLarge,
                color = colors.codeHeaderText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (modelOpen) {
            // 角色不在编辑弹层里：这里只给会话级绑定中的模型/供应商换选择。
            ModelPickerContent(
                currentModel = draft.model,
                providerId = draft.providerId,
                personaId = null,
                onSelect = actions.onModelChange,
                onSelectProvider = actions.onProviderChange,
                onSelectPersona = {},
                onOpenSettings = null,
            )
        }
        if (notice != null) {
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = colors.accentAmber,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.accentAmber.copy(alpha = 0.22f))
                    .clickable(onClick = actions.onNoticeShown)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (draft.canResend) scheme.primary else colors.codeButtonBackground)
                .clickable(enabled = draft.canResend, onClick = onResend)
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "重新发送",
                style = MaterialTheme.typography.labelLarge,
                color = if (draft.canResend) scheme.onPrimary else colors.codeHeaderText,
            )
        }
    }
}

/** 选择器回来的 MIME；少数 provider 不实现 `getType`，返回 null 时由展示名扩展名兜底。 */
private fun mimeOf(context: Context, uri: Uri): String? =
    runCatching { context.contentResolver.getType(uri) }.getOrNull()

private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()
