package com.zcw.chatai.ui.md

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.zcw.chatai.ui.chat.CodePaint
import com.zcw.chatai.ui.chat.InChatCenterReport
import com.zcw.chatai.ui.chat.InChatHighlightColors
import com.zcw.chatai.ui.chat.InChatHit
import com.zcw.chatai.ui.chat.InChatSearch
import org.intellij.markdown.ast.ASTNode
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 代码高亮出站文本：**所有高亮区间进入 `addStyle` 之前必须校验**。
 *
 * 背景：`AnnotatedString.Builder.addStyle(style, start, end)` 不校验 `end`
 * （`MutableRange.toRange` 只对 `pushStyle` 的开放式区间用 `text.length` 兜底），
 * 而 `Range` 只要求 `start <= end`。所以高亮库给什么区间，就原样做进 `AnnotatedString`。
 *
 * `dev.snipme:highlights` 1.1.0 的 `MultilineCommentLocator` 按**下标位置**硬配对 `/*` 与 `*/`
 * （SnipMeDev/Highlights#75），`*&#47;path&#47;*` 会得到反向区间 `(6, 2)`；mikepenz 的
 * `MarkdownHighlightedCode` 把这个区间直接丢给 `addStyle`，于是：
 * - 反向区间 → `buildAnnotatedString` 抛 `IllegalArgumentException: Reversed range is not supported`；
 * - 任何 `end > text.length` 的区间 → 做进 `AnnotatedString`，最后在 Compose 无障碍转换
 *   (`AnnotatedString.toAccessibilitySpannableString` → `SpannableString.setSpan`) 抛
 *   `IndexOutOfBoundsException: setSpan ... ends beyond length`。开着无障碍服务的设备
 *   一滚动到这条消息就崩。
 *
 * 这里在进入 `addStyle` 之前把区间夹回 `[0, code.length]` 并丢弃空/反向区间，
 * 保证产出的 [AnnotatedString] 永远合法——高亮库以后再出别的坏区间也不会再把崩溃带到渲染层。
 */
internal fun buildSafeHighlightedAnnotatedString(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder,
): AnnotatedString {
    val highlights = runCatching {
        val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
        highlightsBuilder
            .code(code)
            .let { if (syntaxLanguage != null) it.language(syntaxLanguage) else it }
            .build()
            .getHighlights()
    }.getOrNull() ?: return AnnotatedString(code)

    return buildAnnotatedString {
        append(code)
        highlights.forEach { highlight ->
            val range = when (highlight) {
                is ColorHighlight -> highlight.location
                is BoldHighlight -> highlight.location
            }
            if (!isValidHighlightRange(range.start, range.end, code.length)) return@forEach
            val style = when (highlight) {
                is ColorHighlight -> SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(style, range.start, range.end)
        }
    }
}

/** 高亮区间必须非空且完全落在 `[0, length]` 内。 */
internal fun isValidHighlightRange(start: Int, end: Int, length: Int): Boolean =
    start >= 0 && start < end && end <= length

/**
 * [com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode] 的等价实现，
 * 唯一区别是走 [buildSafeHighlightedAnnotatedString] 校验高亮区间；外观（背景、语言头、间距）
 * 与库组件逐项对齐，避免流式/定稿或代码块/围栏出现两套样式。
 */
@Composable
internal fun SafeMarkdownHighlightedCode(
    code: String,
    language: String?,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean,
    searchRanges: List<IntRange> = emptyList(),
    activeRange: IntRange? = null,
    activeHit: InChatHit? = null,
    onActiveCenter: InChatCenterReport? = null,
) {
    val immediate = LocalInspectionMode.current
    val annotated: AnnotatedString = if (immediate) {
        remember(code, language, highlightsBuilder, searchRanges, activeRange) {
            withSearchHighlights(
                buildSafeHighlightedAnnotatedString(code, language, highlightsBuilder),
                searchRanges,
                activeRange,
            )
        }
    } else {
        val state by produceState(
            initialValue = withSearchHighlights(AnnotatedString(code), searchRanges, activeRange),
            code,
            language,
            highlightsBuilder,
            searchRanges,
            activeRange,
        ) {
            value = withSearchHighlights(
                withContext(Dispatchers.Default) {
                    buildSafeHighlightedAnnotatedString(code, language, highlightsBuilder)
                },
                searchRanges,
                activeRange,
            )
        }
        state
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var originY by remember { mutableFloatStateOf(Float.NaN) }
    MarkdownCodeBackground(
        color = LocalMarkdownColors.current.codeBackground,
        shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        showHeader = showHeader,
        language = language,
        code = code,
    ) {
        MarkdownBasicText(
            text = annotated,
            style = style,
            onTextLayout = { layout = it },
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(LocalMarkdownPadding.current.codeBlock)
                .onGloballyPositioned { originY = it.positionInWindow().y },
        )
    }
    val activeStart = activeRange?.first
    LaunchedEffect(layout, originY, activeStart, activeHit, onActiveCenter) {
        val report = onActiveCenter ?: return@LaunchedEffect
        val hit = activeHit ?: return@LaunchedEffect
        val result = layout ?: return@LaunchedEffect
        val start = activeStart ?: return@LaunchedEffect
        if (originY.isNaN() || start !in 0 until result.layoutInput.text.length) return@LaunchedEffect
        val box = result.getBoundingBox(start)
        report(hit.messageId, hit.segment, hit.start, originY + (box.top + box.bottom) / 2f)
    }
}

internal fun withSearchHighlights(
    base: AnnotatedString,
    ranges: List<IntRange>,
    active: IntRange?,
): AnnotatedString {
    if (ranges.isEmpty()) return base
    return buildAnnotatedString {
        append(base)
        for (range in ranges) {
            val start = range.first
            val end = range.last + 1
            if (!isValidHighlightRange(start, end, base.length)) continue
            val color = if (range == active) InChatHighlightColors.current else InChatHighlightColors.idle
            addStyle(SpanStyle(background = color), start, end)
        }
    }
}

/** 缩进代码块：库用首尾子节点夹出源码，再 [String.replaceIndent]。 */
internal fun indentedCodeSpan(node: ASTNode): Pair<Int, Int>? {
    if (node.children.isEmpty()) return null
    val start = node.children.first().startOffset
    val end = node.children.last().endOffset
    if (start > end) return null
    return start to end
}

/** 围栏代码块：和库的 [com.mikepenz.markdown.compose.elements.MarkdownCodeFence] 用同一对下标。 */
internal fun fenceCodeSpan(node: ASTNode, hasLanguage: Boolean): Pair<Int, Int>? {
    if (node.children.size < 3) return null
    val start = node.children[2].startOffset
    val minCount = if (hasLanguage && node.children.size > 3) 3 else 2
    val end = node.children[(node.children.size - 2).coerceAtLeast(minCount)].endOffset
    if (start > end) return null
    return start to end
}

internal fun codePaint(
    content: String,
    start: Int,
    end: Int,
    displayed: String,
    query: String,
    hits: List<InChatHit>,
    active: InChatHit?,
): CodePaint {
    if (start < 0 || end > content.length || start > end) return CodePaint(emptyList(), null)
    return InChatSearch.paintCode(start, content.substring(start, end), displayed, query, hits, active)
}
