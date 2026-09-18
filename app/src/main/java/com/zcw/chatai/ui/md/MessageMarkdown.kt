package com.zcw.chatai.ui.md

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.rememberMarkdownState
import com.zcw.chatai.ui.md.latex.MathFormula
import com.zcw.chatai.ui.md.latex.appendInlineMath
import com.zcw.chatai.ui.theme.ChatTheme
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import org.intellij.markdown.MarkdownTokenTypes

/**
 * 表格单元格的固定宽度：2 列就会超过手机气泡宽度，触发库的
 * `horizontalScroll + requiredWidth` 横向滚动；格内文字换行显示全文。
 */
private val TableCellWidth = 220.dp

/** 表格字号相对正文的比例：表格是辅助信息，不抢正文视觉权重。 */
private const val TableTextScale = 0.8f

@Composable
fun MessageMarkdown(content: String, modifier: Modifier = Modifier) {
    val segments = remember(content) { LatexSplitter.split(content) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (segment in segments) {
            when (segment) {
                is MdSegment.Markdown -> MarkdownBlock(segment.text)
                is MdSegment.BlockMath -> BlockMathView(segment.latex)
            }
        }
    }
}

@Composable
private fun MarkdownBlock(text: String) {
    val scheme = MaterialTheme.colorScheme
    val colors = ChatTheme.colors
    val body = ChatTheme.typography.messageBody
    val prepared = remember(text) { prepareInlineMath(text) }
    val mathStrings = remember(prepared.formulas, scheme.onSurface) {
        prepared.formulas.map { latex ->
            buildAnnotatedString {
                withStyle(SpanStyle(color = scheme.onSurface)) {
                    appendInlineMath(latex)
                }
            }
        }
    }
    val highlightsBuilder = remember {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = true))
    }
    Markdown(
        markdownState = rememberMarkdownState(prepared.text, retainState = true),
        colors = markdownColor(
            text = scheme.onSurface,
            codeBackground = colors.codeBackground,
            inlineCodeBackground = colors.surfaceCreamStrong,
            dividerColor = colors.hairline,
            tableBackground = colors.surfaceCard,
        ),
        typography = markdownTypography(
            h1 = MaterialTheme.typography.headlineLarge,
            h2 = MaterialTheme.typography.headlineMedium,
            h3 = MaterialTheme.typography.headlineSmall,
            h4 = MaterialTheme.typography.titleMedium,
            h5 = MaterialTheme.typography.titleSmall,
            h6 = MaterialTheme.typography.titleSmall,
            text = body,
            paragraph = body,
            code = ChatTheme.typography.code.copy(color = colors.codeOnBackground),
            inlineCode = ChatTheme.typography.code.copy(
                color = scheme.onSurface,
                fontSize = TextUnit.Unspecified,
            ),
            quote = body.copy(color = scheme.onSurfaceVariant),
            list = body,
            ordered = body,
            bullet = body,
            table = body.copy(
                fontSize = body.fontSize * TableTextScale,
                lineHeight = body.lineHeight * TableTextScale,
            ),
            textLink = TextLinkStyles(
                style = SpanStyle(
                    color = scheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
        ),
        modifier = Modifier.fillMaxWidth(),
        dimens = markdownDimens(tableCellWidth = TableCellWidth),
        annotator = remember(mathStrings) { inlineMathAnnotator(mathStrings) },
        components = markdownComponents(
            table = { model ->
                MarkdownTable(
                    content = model.content,
                    node = model.node,
                    style = model.typography.table,
                    headerBlock = { content, header, tableWidth, style ->
                        MarkdownTableHeader(
                            content = content,
                            header = header,
                            tableWidth = tableWidth,
                            style = style,
                            maxLines = Int.MAX_VALUE,
                        )
                    },
                    rowBlock = { content, row, tableWidth, style ->
                        MarkdownTableRow(
                            content = content,
                            header = row,
                            tableWidth = tableWidth,
                            style = style,
                            maxLines = Int.MAX_VALUE,
                        )
                    },
                )
            },
            codeBlock = {
                MarkdownHighlightedCodeBlock(
                    content = it.content,
                    node = it.node,
                    highlightsBuilder = highlightsBuilder,
                    showHeader = true,
                )
            },
            codeFence = {
                MarkdownHighlightedCodeFence(
                    content = it.content,
                    node = it.node,
                    highlightsBuilder = highlightsBuilder,
                    showHeader = true,
                )
            },
        ),
        animations = markdownAnimations(animateTextSize = { this }),
    )
}

@Composable
private fun BlockMathView(latex: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                MathFormula(latex = latex, display = true, baseSize = 20.sp)
            }
        }
    }
}

private fun inlineMathAnnotator(mathStrings: List<AnnotatedString>): MarkdownAnnotator =
    markdownAnnotator { content, child ->
        if (child.type == MarkdownTokenTypes.TEXT) {
            val raw = content.substring(child.startOffset, child.endOffset)
            if (raw.indexOf(PLACEHOLDER_OPEN) < 0) {
                false
            } else {
                for (piece in splitPlaceholders(raw)) {
                    when (piece) {
                        is InlinePiece.Text -> append(piece.text)
                        is InlinePiece.Formula -> {
                            mathStrings.getOrNull(piece.index)?.let { append(it) }
                        }
                    }
                }
                true
            }
        } else {
            false
        }
    }
