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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.zcw.chatai.R
import com.zcw.chatai.data.ConversationLamp
import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.ConversationTitle
import com.zcw.chatai.data.model.SearchSnippet
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

/** 三个工作区：对话 / 生图 / 视频。列表页与底部按钮按它切换入口与文案。 */
enum class WorkspaceMode { CHAT, IMAGE, VIDEO }

/**
 * 列表这一页要展示的会话，以及只作用于这些会话的操作。
 * 对话页和生图页各备一份，壳层选中后再交给列表，避免每个字段各写一次模式分支。
 */
data class ConversationListPage(
    val conversations: List<Conversation>,
    /**
     * 每条会话的下辖分支数。调用方按**未过滤**的全量会话算：
     * 用已经过滤过的列表算，搜索时会让长按菜单里的「下辖分支」凭空消失。
     */
    val branchCounts: Map<String, Int>,
    val selectedId: String?,
    val searchQuery: String,
    val onSearchQueryChange: (String) -> Unit,
    val onSelect: (String) -> Unit,
    val onNew: () -> Unit,
    val onRename: (String, String) -> Unit,
    val onDelete: (String) -> Unit,
)

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
    page: ConversationListPage,
    /** 正在处理 = 黄，完成未见 = 绿，失败未见 = 红。没有条目时沿用选中/淡灰。 */
    lamps: Map<String, ConversationLamp>,
    /** 打开某条会话的分支页（长按菜单里的「下辖分支」）。 */
    onOpenBranches: (String) -> Unit,
    onOpenSettings: () -> Unit,
    /** 当前工作区：决定菜单块里显示「另两个」入口，以及底部按钮文案。 */
    mode: WorkspaceMode,
    onOpenChat: () -> Unit,
    onOpenImageStudio: () -> Unit,
    onOpenVideoStudio: () -> Unit,
    onClose: () -> Unit,
    /** 本工作区的滚动与搜索展开。列表页拆掉后还在，三个工作区各一份。 */
    scroll: ConversationListSlot,
    modifier: Modifier = Modifier,
) {
    val conversations = page.conversations
    val branchCounts = page.branchCounts
    val selectedId = page.selectedId
    val searchQuery = page.searchQuery
    val onSearchQueryChange = page.onSearchQueryChange
    val onSelect = page.onSelect
    val onNew = page.onNew
    val onRename = page.onRename
    val onDelete = page.onDelete
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var actionTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    val searchVisible = searchFieldVisible(searchQuery, scroll.expanded)
    val searchFocus = remember { FocusRequester() }
    // 只在用户刚刚点开搜索时聚焦。从别的页面回来时槽里的展开状态还在，但不能再弹键盘。
    var pendingFocus by remember { mutableStateOf(false) }
    // 左滑收起：只跟随手指向左偏移（不右移），松手按位移/速度决定收起还是弹回。
    var offsetX by remember { mutableFloatStateOf(0f) }
    val windowWidth = LocalWindowInfo.current.containerSize.width.toFloat()

    LaunchedEffect(pendingFocus, searchVisible) {
        if (pendingFocus && searchVisible) {
            searchFocus.requestFocus()
            pendingFocus = false
        }
    }

    // 换了一个非空白搜索词才把筛选列表拉回顶部。同一个词再进来保持原位。
    // scroll 也是 key：退出动画里 mode 会先换，搜索词碰巧相同时不能还抓着上一份槽。
    LaunchedEffect(scroll, searchQuery) {
        if (!shouldResetFilteredScroll(scroll.appliedQuery, searchQuery)) return@LaunchedEffect
        scroll.filtered.scrollToItem(0)
        scroll.appliedQuery = searchQuery
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
                // 显示「另两个」工作区入口：对话页给出生图/视频，生图页给出对话/视频，以此类推。
                if (mode != WorkspaceMode.CHAT) {
                    MenuRow(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        label = "对话",
                        onClick = onOpenChat,
                    )
                }
                if (mode != WorkspaceMode.IMAGE) {
                    MenuRow(
                        painter = painterResource(R.drawable.ic_image),
                        label = "生图",
                        onClick = onOpenImageStudio,
                    )
                }
                if (mode != WorkspaceMode.VIDEO) {
                    MenuRow(
                        painter = painterResource(R.drawable.ic_video),
                        label = "视频",
                        onClick = onOpenVideoStudio,
                    )
                }
                MenuRow(
                    icon = Icons.Filled.Search,
                    label = "搜索",
                    onClick = {
                        if (searchVisible) {
                            // 这是主动收起，不是离开页面：清掉词，回到未筛选列表自己的滚动位置。
                            scroll.expanded = false
                            onSearchQueryChange("")
                        } else {
                            scroll.expanded = true
                            pendingFocus = true
                        }
                    },
                )
            }

            if (searchVisible) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    placeholder = { Text("搜索记录") },
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
                    // 列表随输入即时筛选，键盘上的「搜索」只负责收起输入法。
                    // 只 hide 的话焦点还在框里，三星会把键盘立刻弹回来。
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        },
                    ),
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

            // 平铺：不再按父子缩进（层级交给分支页逐层查看），但保留「分支」标签，
            // 并且父会话被删的孤儿分支也不会因此失去挂载点。
            // key 按工作区 + 未筛选/筛选拆开组合节点，退出动画里换 mode 也不会把两份滚动接到同一个列表上。
            val pane = listPane(searchQuery)
            key(mode, pane) {
                RecentConversationList(
                    conversations = conversations,
                    selectedId = selectedId,
                    searchQuery = searchQuery,
                    lamps = lamps,
                    listState = scroll.stateFor(searchQuery),
                    onSelect = onSelect,
                    onLongClick = { actionTarget = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 底部反色胶囊：浅色主题近黑底、深色主题奶油白底（inverseSurface 随主题取反）。
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 24.dp),
        ) {
            NewChatButton(
                label = when (mode) {
                    WorkspaceMode.IMAGE -> "新图像"
                    WorkspaceMode.VIDEO -> "新视频"
                    WorkspaceMode.CHAT -> "新对话"
                },
                onClick = onNew,
            )
        }
    }

    val target = actionTarget
    if (target != null) {
        DarkSheet(onDismiss = { actionTarget = null }) {
            // 只有真的有分支时才出现：没分支的会话点进去也是空页，不如不给入口。
            branchCounts[target.id]?.takeIf { it > 0 }?.let { count ->
                SheetAction(
                    label = "下辖分支（$count）",
                    onClick = {
                        actionTarget = null
                        onOpenBranches(target.id)
                    },
                )
            }
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
) = MenuRowShell(label = label, onClick = onClick) { tint ->
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(26.dp),
    )
}

@Composable
private fun MenuRow(
    painter: Painter,
    label: String,
    onClick: () -> Unit,
) = MenuRowShell(label = label, onClick = onClick) { tint ->
    Icon(
        painter = painter,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(26.dp),
    )
}

@Composable
private fun MenuRowShell(
    label: String,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
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
        icon(scheme.onSurface)
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onSurface,
        )
    }
}

@Composable
private fun NewChatButton(label: String, onClick: () -> Unit) {
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
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.inverseOnSurface,
        )
    }
}

@Composable
private fun RecentConversationList(
    conversations: List<Conversation>,
    selectedId: String?,
    searchQuery: String,
    lamps: Map<String, ConversationLamp>,
    listState: LazyListState,
    onSelect: (String) -> Unit,
    onLongClick: (Conversation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    LazyColumn(
        modifier = modifier,
        state = listState,
        // 底部留出「新对话」胶囊的高度，最后一条不会被盖住。
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp),
    ) {
        items(conversations, key = { it.id }) { conversation ->
            ConversationRow(
                conversation = conversation,
                selected = conversation.id == selectedId,
                searchQuery = searchQuery,
                lamp = lamps[conversation.id],
                onClick = { onSelect(conversation.id) },
                onLongClick = { onLongClick(conversation) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    searchQuery: String,
    lamp: ConversationLamp?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    val isBranch = conversation.parentConversationId.isNotBlank()
    val dotColor = when (lamp) {
        ConversationLamp.RUNNING -> colors.warning
        ConversationLamp.UNSEEN_DONE -> colors.success
        ConversationLamp.UNSEEN_FAILED -> scheme.error
        null -> if (selected) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    val dotLabel = when (lamp) {
        ConversationLamp.RUNNING -> "正在生成"
        ConversationLamp.UNSEEN_DONE -> "已完成"
        ConversationLamp.UNSEEN_FAILED -> "生成失败"
        null -> null
    }
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
                .background(dotColor)
                .then(
                    if (dotLabel == null) {
                        Modifier
                    } else {
                        Modifier.semantics { contentDescription = dotLabel }
                    },
                ),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val highlight = ChatTheme.colors.accentAmber.copy(alpha = 0.45f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isBranch) {
                    Text(
                        text = ConversationTitle.BRANCH_SUFFIX,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.chipBackground)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                val title = conversation.title.ifBlank { ConversationTitle.FALLBACK }
                Text(
                    text = highlighted(title, searchQuery, highlight),
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
            val snippet = conversation.searchSnippet
            val matchStart = remember(snippet, searchQuery) {
                if (searchQuery.isNotBlank() && snippet != null) {
                    SearchSnippet.findFirst(snippet, searchQuery)
                } else {
                    -1
                }
            }
            if (snippet != null && matchStart >= 0) {
                CenteredMatchLine(
                    text = snippet,
                    matchStart = matchStart,
                    matchEnd = matchStart + searchQuery.length,
                    highlight = highlight,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            } else {
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
}

/** 标题里的第一次命中铺一层底色。没命中或没在搜索时就是原标题。 */
private fun highlighted(text: String, query: String, background: Color): AnnotatedString {
    val start = if (query.isBlank()) -1 else SearchSnippet.findFirst(text, query)
    if (start < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(background = background), start, (start + query.length).coerceAtMost(text.length))
    }
}

/**
 * 单行预览：先量出命中词的位置，再用真正的 [Text] 按这个位移摆放。
 * 短于一行、或词就在开头时不留空。外层裁掉两侧溢出。
 */
@Composable
private fun CenteredMatchLine(
    text: String,
    matchStart: Int,
    matchEnd: Int,
    highlight: Color,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(text, matchStart, matchEnd, highlight) {
        buildAnnotatedString {
            append(text)
            if (matchStart in 0 until matchEnd && matchEnd <= text.length) {
                addStyle(SpanStyle(background = highlight), matchStart, matchEnd)
            }
        }
    }
    val measurer = rememberTextMeasurer()
    val measured = remember(annotated, style, measurer) {
        measurer.measure(
            text = annotated,
            style = style,
            overflow = TextOverflow.Visible,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = Constraints.Infinity),
        )
    }
    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds()) {
        val edges = matchEdges(measured, matchStart, matchEnd)
        val shift = SearchSnippet.matchShiftPx(
            constraints.maxWidth,
            measured.size.width,
            edges.first,
            edges.second,
        )
        Layout(
            content = {
                Text(
                    text = annotated,
                    style = style,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            },
        ) { measurables, constraints ->
            val placeable = measurables.first().measure(
                Constraints(maxWidth = Constraints.Infinity),
            )
            val width = constraints.maxWidth.coerceAtLeast(0)
            layout(width, placeable.height) {
                placeable.place(shift, 0)
            }
        }
    }
}

private fun matchEdges(layout: TextLayoutResult, start: Int, end: Int): Pair<Float, Float> {
    val length = layout.layoutInput.text.length
    if (length == 0 || start !in 0 until length) return 0f to 0f
    val last = (end - 1).coerceIn(start, length - 1)
    return layout.getBoundingBox(start).left to layout.getBoundingBox(last).right
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
