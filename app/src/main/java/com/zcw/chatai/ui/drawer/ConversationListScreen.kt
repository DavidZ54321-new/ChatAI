package com.zcw.chatai.ui.drawer

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.ConversationTitle
import com.zcw.chatai.ui.chat.DarkSheet
import com.zcw.chatai.ui.chat.SheetAction
import com.zcw.chatai.ui.theme.ChatTheme

/** 左滑收起：位移超过视窗宽的这个比例即视为「滑走」。 */
private const val DRAG_DISMISS_FRACTION = 0.35f

/** 左滑收起：松手瞬间向左速度超过这个值（px/s）也视为「滑走」。 */
private const val DRAG_DISMISS_VELOCITY = 1_200f

/** 滑走/弹回的动画时长。 */
private const val DRAG_ANIM_MS = 220

/** 视窗宽度拿不到时（理论上不会）的兜底像素宽度。 */
private const val DEFAULT_WINDOW_WIDTH_PX = 1080f

/**
 * 会话列表：占满全屏的独立页（不再是可右滑拉出的 ModalNavigationDrawer）。
 * 版式参考 Claude 侧栏——衬线大字品牌名 + 菜单块 + 平铺的「最近」列表 + 底部反色「新对话」胶囊。
 *
 * 打开/关闭由 [com.zcw.chatai.ui.ChatAiRoot] 的路由状态控制。**手势方向相反**：
 * 不再右滑打开，而是**向左滑**把这一页收回去（[onClose]）。
 */
@Composable
fun ConversationListScreen(
    visible: Boolean,
    conversations: List<Conversation>,
    selectedId: String?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    var actionTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var searching by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    // 左滑收起：只跟随手指向左偏移（不右移），松手按位移/速度决定收起还是弹回。
    var offsetX by remember { mutableFloatStateOf(0f) }
    val windowWidth = LocalWindowInfo.current.containerSize.width.toFloat()

    // 点「搜索」后自动聚焦并弹键盘。
    LaunchedEffect(searching) {
        if (searching) searchFocus.requestFocus()
    }

    // 重新打开时归零左滑位移：退场动画期间若被再次打开，组合不会重建，remember 的偏移会残留。
    LaunchedEffect(visible) {
        if (visible) offsetX = 0f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    offsetX = (offsetX + delta).coerceAtMost(0f)
                },
                onDragStopped = { velocity ->
                    val width = windowWidth.takeIf { it > 0f } ?: DEFAULT_WINDOW_WIDTH_PX
                    val dismiss = offsetX < -width * DRAG_DISMISS_FRACTION ||
                        velocity < -DRAG_DISMISS_VELOCITY
                    if (dismiss) {
                        // 交回壳层的滑出动画（从当前位移继续往左），别在页内再跑一遍。
                        onClose()
                    } else {
                        animate(
                            initialValue = offsetX,
                            targetValue = 0f,
                            animationSpec = tween(DRAG_ANIM_MS),
                        ) { value, _ -> offsetX = value }
                    }
                },
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "ChatAI",
                style = MaterialTheme.typography.displaySmall,
                color = scheme.onSurface,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
            )

            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                MenuRow(
                    icon = Icons.Filled.Settings,
                    label = "设置",
                    onClick = onOpenSettings,
                )
                MenuRow(
                    icon = Icons.Filled.Search,
                    label = "搜索",
                    onClick = {
                        // 收起搜索时一并清空关键词，避免「搜索框没了但列表还在过滤」。
                        if (searching) onSearchQueryChange("")
                        searching = !searching
                    },
                )
            }

            if (searching) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    placeholder = { Text("搜索聊天记录") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "清除",
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { onSearchQueryChange("") },
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .focusRequester(searchFocus),
                )
            }

            HorizontalDivider(
                color = colors.hairline,
                modifier = Modifier.padding(top = 10.dp),
            )

            Text(
                text = "最近",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                // 底部留出「新对话」胶囊的高度，最后一条不会被盖住。
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp),
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == selectedId,
                        onClick = { onSelect(conversation.id) },
                        onLongClick = { actionTarget = conversation },
                    )
                }
                if (conversations.isEmpty()) {
                    item {
                        Text(
                            text = if (searchQuery.isBlank()) "还没有对话" else "没有找到相关会话",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp),
                        )
                    }
                }
            }
        }

        // 底部反色胶囊：浅色主题近黑底、深色主题奶油白底（inverseSurface 随主题取反）。
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 24.dp),
        ) {
            NewChatButton(onClick = onNew)
        }
    }

    val target = actionTarget
    if (target != null) {
        DarkSheet(onDismiss = { actionTarget = null }) {
            SheetAction(
                label = "重命名",
                onClick = {
                    renameTarget = target
                    actionTarget = null
                },
            )
            SheetAction(
                label = "删除会话",
                color = ChatTheme.colors.accentAmber,
                onClick = {
                    onDelete(target.id)
                    actionTarget = null
                },
            )
        }
    }

    val renaming = renameTarget
    if (renaming != null) {
        RenameDialog(
            initial = renaming.title,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                onRename(renaming.id, title)
                renameTarget = null
            },
        )
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.onSurface,
            modifier = Modifier.size(26.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onSurface,
        )
    }
}

@Composable
private fun NewChatButton(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(scheme.inverseSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = scheme.inverseOnSurface,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "新对话",
            style = MaterialTheme.typography.titleMedium,
            color = scheme.inverseOnSurface,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (selected) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.35f)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.title.ifBlank { ConversationTitle.FALLBACK },
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = RelativeTime.format(conversation.updatedAt, System.currentTimeMillis()),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            val preview = conversation.lastMessagePreview
            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
