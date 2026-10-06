package com.zcw.chatai.ui.chat

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlin.math.min

/** 普通命中是半透明琥珀，当前这一处是实心琥珀。文字颜色不动。 */
internal object InChatHighlightColors {
    val idle = Color(0x59F5C542)
    val current = Color(0xFFF5C542)
}

/**
 * 把 [text] 追加进当前构造器，并给落在 [hits] 里的区间铺底。
 * [sourceStart] 是 [text] 第一个字符在这一段正文里的偏移。
 * [probe] 为真时，在当前命中的起点插一个定位点。id 跟着这一处走，不和上一处共用。
 */
internal fun AnnotatedString.Builder.appendHighlighted(
    text: String,
    sourceStart: Int,
    hits: List<InChatHit>,
    active: InChatHit?,
    probe: Boolean,
) {
    if (text.isEmpty()) return
    val end = sourceStart + text.length
    val relevant = hits.filter { it.start < end && it.end > sourceStart }
    val probeHit = if (probe) active else null
    val probeAt = if (probeHit != null && probeHit.start in sourceStart until end) probeHit.start else -1
    if (relevant.isEmpty() && probeAt < 0) {
        append(text)
        return
    }
    var cursor = 0
    while (cursor < text.length) {
        val source = sourceStart + cursor
        if (probeAt == source && probeHit != null) {
            appendInlineContent(InChatSearch.probeId(probeHit), "\u200B")
        }
        var next = text.length
        for (hit in relevant) {
            val localStart = (hit.start - sourceStart).coerceAtLeast(0)
            val localEnd = (hit.end - sourceStart).coerceAtMost(text.length)
            if (localStart > cursor) next = min(next, localStart)
            if (localEnd > cursor) next = min(next, localEnd)
        }
        if (probeAt - sourceStart > cursor) next = min(next, probeAt - sourceStart)
        if (next <= cursor) next = cursor + 1
        val style = backgroundAt(source, relevant, active)
        if (style == null) append(text, cursor, next) else withStyle(style) { append(text, cursor, next) }
        cursor = next
    }
}

private fun backgroundAt(source: Int, hits: List<InChatHit>, active: InChatHit?): SpanStyle? {
    var idle = false
    for (hit in hits) {
        if (source < hit.start || source >= hit.end) continue
        if (active != null && hit == active) return SpanStyle(background = InChatHighlightColors.current)
        idle = true
    }
    return if (idle) SpanStyle(background = InChatHighlightColors.idle) else null
}

/** 用户气泡：原文和展示串一致，用文字布局报当前命中那一行的垂直中心。 */
@Composable
internal fun HighlightedPlainText(
    text: String,
    hits: List<InChatHit>,
    active: InChatHit?,
    style: TextStyle,
    color: Color,
    onActiveCenter: InChatCenterReport?,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(text, hits, active) {
        buildAnnotatedString {
            appendHighlighted(text, sourceStart = 0, hits = hits, active = active, probe = false)
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var originY by remember { mutableFloatStateOf(Float.NaN) }
    Text(
        text = annotated,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        modifier = modifier.onGloballyPositioned { originY = it.positionInWindow().y },
    )
    val activeStart = active?.start
    LaunchedEffect(layout, originY, active, onActiveCenter) {
        val report = onActiveCenter ?: return@LaunchedEffect
        val hit = active ?: return@LaunchedEffect
        val result = layout ?: return@LaunchedEffect
        val start = activeStart ?: return@LaunchedEffect
        if (originY.isNaN() || start !in 0 until result.layoutInput.text.length) return@LaunchedEffect
        val box = result.getBoundingBox(start)
        report(hit.messageId, hit.segment, hit.start, originY + (box.top + box.bottom) / 2f)
    }
}
