package com.zcw.chatai.ui.branch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.ConversationTitle
import com.zcw.chatai.ui.drawer.ConversationTree
import com.zcw.chatai.ui.drawer.RelativeTime
import com.zcw.chatai.ui.theme.ChatTheme

/**
 * 分支页：一次只看**当前这条会话的直接分支**，右侧箭头可以继续下钻，顶栏面包屑可以跳回上层。
 *
 * 逐层下钻（而不是把整棵子树铺一页）是因为分支可能又生分支：铺一页最终还是回到
 * 「缩进到看不出层级」的老问题。会话列表那边因此也回到平铺。
 *
 * 本页是纯浏览器：行主体 = 打开那条分支会话，箭头 = 下钻。重命名/删除仍在会话列表的长按菜单里。
 */
@Composable
fun BranchScreen(
    /** 未经过滤的全量会话（带搜索过滤的列表会让父子关系缺失）。 */
    conversations: List<Conversation>,
    /** 进入本页时的起点会话。 */
    rootId: String,
    /** 行主体点击：切到这条分支会话（调用方负责收起本页）。 */
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    // 页内下钻轨迹，末尾即当前所在的那条会话；换起点就重置。
    var trail by remember(rootId) { mutableStateOf(BranchTrail.start(rootId)) }
    // 返回：先逐层退回，退到起点才关页面（顶栏按钮与系统返回走同一条）。
    fun goBack() {
        val previous = BranchTrail.back(trail)
        if (previous == null) onClose() else trail = previous
    }
    BackHandler { goBack() }
    val byId = remember(conversations) { conversations.associateBy { it.id } }
    val counts = remember(conversations) { ConversationTree.childCounts(conversations) }
    val current = BranchTrail.current(trail)?.let(byId::get)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            // 本页是盖在会话列表之上的整屏浮层，但空白处默认不吃手势：列表仍在组合中，
            // 点在页内空白处会穿透到下面的列表行上。这里整屏吃掉点击（子节点优先，不影响行与箭头）。
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { goBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (trail.size > 1) "上一层" else "返回会话列表",
                    tint = scheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = "分支",
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            // 下钻到某条分支后，能直接在那儿继续聊（否则要退回列表里找它）。
            if (current != null) {
                Text(
                    text = "打开这条会话",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onOpen(current.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        BranchBreadcrumb(
            trail = trail,
            byId = byId,
            onPick = { index -> trail = BranchTrail.pick(trail, index) },
        )
        HorizontalDivider(color = colors.hairline)

        // 面包屑放在「会话不存在」之前：那层被删掉时，仍然能一键跳回更上层，而不是只能一层层退。
        if (current == null) {
            Text(
                text = "这条会话已不存在",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            )
            return@Column
        }

        val children = ConversationTree.childrenOf(conversations, current.id)
        if (children.isEmpty()) {
            EmptyBranches()
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(children, key = { it.id }) { child ->
                    BranchRow(
                        conversation = child,
                        branchCount = counts[child.id] ?: 0,
                        onOpen = { onOpen(child.id) },
                        onDrill = { trail = BranchTrail.drill(trail, child.id) },
                    )
                }
            }
        }
    }
}

/** 页内下钻轨迹：`父会话 › 分支A › 分支B`，点任一段回到那一层（当前层不可点）。 */
@Composable
private fun BranchBreadcrumb(
    trail: List<String>,
    byId: Map<String, Conversation>,
    onPick: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        trail.forEachIndexed { index, id ->
            if (index > 0) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            val isCurrent = index == trail.lastIndex
            Text(
                text = byId[id]?.title?.ifBlank { ConversationTitle.FALLBACK } ?: "已删除的会话",
                style = MaterialTheme.typography.labelLarge,
                color = if (isCurrent) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(enabled = !isCurrent) { onPick(index) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun BranchRow(
    conversation: Conversation,
    branchCount: Int,
    onOpen: () -> Unit,
    onDrill: () -> Unit,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text(
                    text = conversation.title.ifBlank { ConversationTitle.FALLBACK },
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            conversation.lastMessagePreview.takeIf { it.isNotBlank() }?.let { preview ->
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = RelativeTime.format(conversation.updatedAt, System.currentTimeMillis()),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
            // 只有真的有下辖分支时才给箭头，避免「点不动的死区」。
            if (branchCount > 0) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = onDrill)
                        .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$branchCount 个分支",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.primary,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "查看下辖分支",
                        tint = scheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyBranches() {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "这条会话还没有分支",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
        )
        Text(
            text = "在任意 AI 回复下方点分支图标，就能从那里签出一个分支；" +
                "分支会挂在这条会话下面，回到这里就能看到。",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}
