package com.zcw.chatai.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcw.chatai.R
import com.zcw.chatai.data.ai.formatReasoningDuration
import com.zcw.chatai.data.ai.reasoningTickerText
import com.zcw.chatai.data.model.ToolKind
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolStatus
import com.zcw.chatai.ui.theme.ChatTheme
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 收起时面板露出的高度，占视窗的比例。 */
private const val SHEET_VISIBLE_FRACTION = 0.4f

/**
 * 助手回合上方的一行：时钟 + 最近一步 + ›。点开从底部升起摘要，不撑高消息列表。
 */
@Composable
fun ActivityRow(
    label: ActivityRowLabel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_schedule),
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        if (label.followEnd) {
            ActivityTicker(
                text = reasoningTickerText(label.text, followEnd = true),
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                text = label.text,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "查看摘要",
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 从底部升起的摘要。默认高度是视窗的 40%，上滑拉满，下滑关掉。
 * 点时间线里的一条，换成那一条自己的页面（思考全文或工具结果），返回回到摘要。
 */
@Composable
fun ActivitySummaryCard(
    steps: List<ActivityStep>,
    followLatest: Boolean,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dismiss = rememberUpdatedState(onDismiss)
    var screenPx by remember { mutableFloatStateOf(0f) }
    var sheetTop by remember { mutableFloatStateOf(Float.NaN) }
    var placed by remember { mutableStateOf(false) }
    var pageId by remember { mutableStateOf<String?>(null) }
    val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
    LaunchedEffect(pageId, steps) {
        if (pageId != null && steps.none { it.messageId == pageId }) pageId = null
    }

    var motion by remember { mutableStateOf<Job?>(null) }

    fun partialTop(): Float = screenPx * (1f - SHEET_VISIBLE_FRACTION)

    fun dragBy(delta: Float): Float {
        if (screenPx <= 0f || sheetTop.isNaN()) return 0f
        motion?.cancel()
        val next = (sheetTop + delta).coerceIn(0f, screenPx)
        val consumed = next - sheetTop
        if (consumed != 0f) sheetTop = next
        return consumed
    }

    fun moveTo(target: Float, durationMillis: Int = 260) {
        if (screenPx <= 0f || sheetTop.isNaN()) {
            dismiss.value()
            return
        }
        motion?.cancel()
        val from = sheetTop
        motion = scope.launch {
            animate(
                initialValue = from,
                targetValue = target,
                animationSpec = tween(durationMillis = durationMillis),
            ) { value, _ -> sheetTop = value }
            if (target >= screenPx - 1f) dismiss.value()
        }
    }

    fun settle(velocity: Float) {
        if (screenPx <= 0f || sheetTop.isNaN()) return
        val anchors = floatArrayOf(0f, partialTop(), screenPx)
        val current = sheetTop
        val target = when {
            velocity > 1400f -> anchors.firstOrNull { it > current + 8f } ?: screenPx
            velocity < -1400f -> anchors.lastOrNull { it < current - 8f } ?: 0f
            else -> anchors.minBy { abs(it - current) }
        }
        moveTo(target)
    }

    val drag = rememberDraggableState { delta -> dragBy(delta) }

    fun dismissSheet() {
        if (screenPx <= 0f || sheetTop.isNaN()) dismiss.value() else moveTo(screenPx, durationMillis = 220)
    }

    BackHandler {
        if (pageId != null) pageId = null else dismissSheet()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .onSizeChanged { size ->
                val height = size.height.toFloat()
                if (height <= 0f) return@onSizeChanged
                val previous = screenPx
                screenPx = height
                if (!placed) {
                    placed = true
                    sheetTop = height
                    moveTo(height * (1f - SHEET_VISIBLE_FRACTION))
                } else if (previous > 0f && previous != height && !sheetTop.isNaN()) {
                    sheetTop = (sheetTop / previous * height).coerceIn(0f, height)
                }
            },
    ) {
        if (!sheetTop.isNaN() && screenPx > 0f) {
            val progress = (1f - sheetTop / screenPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f * progress))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { dismissSheet() },
                    ),
            )
            val visiblePx = (screenPx - sheetTop).coerceAtLeast(0f)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(density) { visiblePx.toDp() })
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(ChatTheme.colors.surfaceCard),
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                )
                Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                    val overlap = (statusTop - sheetTop).coerceAtLeast(0f)
                    if (overlap > 0f) {
                        Spacer(Modifier.height(with(density) { overlap.toDp() }))
                    }
                    val dragHandle = Modifier.draggable(
                        state = drag,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity -> settle(velocity) },
                    )
                    SheetHandle(modifier = dragHandle)
                    AnimatedContent(
                        targetState = pageId,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clipToBounds(),
                        transitionSpec = {
                            if (targetState != null) {
                                slideInHorizontally(tween(280)) { width -> width } togetherWith
                                    slideOutHorizontally(tween(280)) { width -> -width }
                            } else {
                                slideInHorizontally(tween(280)) { width -> -width } togetherWith
                                    slideOutHorizontally(tween(280)) { width -> width }
                            } using SizeTransform(clip = true)
                        },
                        label = "summary-page",
                    ) { openId ->
                        val open = steps.firstOrNull { it.messageId == openId }
                        if (open == null) {
                            Column(Modifier.fillMaxSize()) {
                                SummaryHeader(onClose = { dismissSheet() }, modifier = dragHandle)
                                if (steps.isEmpty()) {
                                    Text(
                                        text = "思考中…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                                    )
                                } else {
                                    ActivityTimeline(
                                        steps = steps,
                                        followLatest = followLatest,
                                        onOpen = { step -> pageId = step.messageId },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                DetailHeader(step = open, onBack = { pageId = null }, modifier = dragHandle)
                                DetailBody(step = open, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

@Composable
private fun SummaryHeader(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "关闭",
            tint = scheme.onSurface,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .padding(10.dp)
                .size(18.dp),
        )
        Text(
            text = "摘要",
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun DetailHeader(step: ActivityStep, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = scheme.onSurface,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .padding(10.dp)
                .size(22.dp),
        )
        Text(
            text = detailTitle(step),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun detailTitle(step: ActivityStep): String = when (step) {
    is ActivityStep.Thinking -> {
        val duration = formatReasoningDuration(step.reasoningMs)
        when {
            step.streaming && !step.answerStarted -> "思考中…"
            duration != null -> "已深度思考 $duration"
            else -> "已深度思考"
        }
    }
    is ActivityStep.Tool -> ActivityLog.line(step)
}

@Composable
private fun DetailBody(step: ActivityStep, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        when (step) {
            is ActivityStep.Thinking -> Text(
                text = step.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            is ActivityStep.Tool -> ToolResultDetail(step.result)
        }
    }
}

@Composable
private fun ActivityTimeline(
    steps: List<ActivityStep>,
    followLatest: Boolean,
    onOpen: (ActivityStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val stick = remember { mutableStateOf(followLatest) }
    val followLatestNow = rememberUpdatedState(followLatest)
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (followLatestNow.value && source == NestedScrollSource.UserInput) {
                    stick.value = scroll.maxValue - scroll.value <= 2
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(steps.size, followLatest) {
        if (!followLatest) return@LaunchedEffect
        snapshotFlow { scroll.maxValue }.collect { max ->
            if (stick.value && scroll.value != max) scroll.scrollTo(max)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .nestedScroll(userScroll)
            .verticalScroll(scroll)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
    ) {
        steps.forEachIndexed { index, step ->
            ActivityTimelineRow(
                step = step,
                connectAbove = index > 0,
                connectBelow = index != steps.lastIndex,
                onOpen = { onOpen(step) },
            )
        }
    }
}

private val MarkSlot = 22.dp

@Composable
private fun ActivityTimelineRow(
    step: ActivityStep,
    connectAbove: Boolean,
    connectBelow: Boolean,
    onOpen: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.onSurfaceVariant.copy(alpha = 0.45f)
    val mask = ChatTheme.colors.surfaceCard
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = MarkSlot.toPx() / 2f
                val mid = MarkSlot.toPx() / 2f
                val stroke = 1.5.dp.toPx()
                if (connectAbove) {
                    drawLine(line, Offset(x, 0f), Offset(x, mid), stroke)
                }
                if (connectBelow) {
                    drawLine(line, Offset(x, mid), Offset(x, size.height), stroke)
                }
            }
            .clickable(onClick = onOpen),
    ) {
        Box(
            modifier = Modifier.size(MarkSlot),
            contentAlignment = Alignment.Center,
        ) {
            StepMark(step = step, mask = mask)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, bottom = 18.dp)
                .heightIn(min = MarkSlot),
        ) {
            Text(
                text = ActivityLog.line(step),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (ActivityLog.isFailed(step)) {
                Text(
                    text = "失败",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.error,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StepMark(step: ActivityStep, mask: Color) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    when (step) {
        is ActivityStep.Thinking -> Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(tint),
        )
        is ActivityStep.Tool -> Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(mask),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(markIcon(step.result.kind)),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun markIcon(kind: ToolKind): Int = when (kind) {
    ToolKind.IMAGE_SEARCH, ToolKind.IMAGE_SIMILAR -> R.drawable.ic_image
    ToolKind.SEARCH, ToolKind.FETCH -> R.drawable.ic_public
}

/** 工具结果页：来源按条排开，图搜把图放在上面。点来源打开链接。 */
@Composable
private fun ToolResultDetail(result: ToolResult) {
    val scheme = MaterialTheme.colorScheme
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    if (result.status == ToolStatus.FAILED) {
        Text(
            text = "失败",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.error,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
    result.images.forEach { image ->
        RemoteImage(
            url = image.url,
            contentDescription = image.title.ifBlank { "搜索结果图片" },
            maxEdge = 512,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ChatTheme.colors.surfaceSoft)
                .clickable { previewIndex = result.images.indexOf(image) },
        )
        if (image.title.isNotBlank()) {
            Text(
                text = image.title,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
    val uriHandler = LocalUriHandler.current
    result.sources.forEach { source ->
        val title = source.title?.takeIf { it.isNotBlank() } ?: hostOf(source.url).ifBlank { source.url }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { runCatching { uriHandler.openUri(source.url) } }
                .padding(vertical = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_public),
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = hostOf(source.url).ifBlank { source.url },
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val snippet = source.snippet?.takeIf { it.isNotBlank() }
            if (snippet != null) {
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
    if (result.text.isNotBlank()) {
        Text(
            text = result.text.take(8000),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (result.images.isEmpty() && result.sources.isEmpty() && result.text.isBlank()) {
        Text(
            text = if (result.status == ToolStatus.RUNNING) "进行中" else "没有更多内容",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
    }
    previewIndex?.takeIf { result.images.isNotEmpty() }?.let { index ->
        RemoteImagePreviewDialog(
            images = result.images.map { image ->
                PreviewImage(url = image.url, title = image.title.takeIf { it.isNotBlank() })
            },
            initialIndex = index.coerceIn(0, result.images.lastIndex),
            onDismiss = { previewIndex = null },
        )
    }
}

/** 从 URL 里取出主机名。不用正则，避免 ICU 引擎的差异。 */
private fun hostOf(url: String): String {
    val rest = url.substringAfter("://", url)
    val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
    return host.removePrefix("www.")
}

@Composable
private fun ActivityTicker(
    text: String,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(text, scroll.maxValue) {
        scroll.scrollTo(scroll.maxValue)
    }
    Box(
        modifier = modifier
            .clipToBounds()
            .horizontalScroll(scroll, enabled = false),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}
