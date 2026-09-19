package com.zcw.chatai.ui.md

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnimations
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.parseMarkdown
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.resolveImageAlt
import com.mikepenz.markdown.utils.resolveImageLink
import com.zcw.chatai.ui.chat.ChatMetrics
import com.zcw.chatai.ui.chat.RemoteImage
import com.zcw.chatai.ui.chat.RemoteImagePreviewDialog
import com.zcw.chatai.ui.md.latex.MathFormula
import com.zcw.chatai.ui.md.latex.appendInlineMath
import com.zcw.chatai.ui.theme.ChatTheme
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownTokenTypes

/**
 * 表格单元格的固定宽度：2 列就会超过手机气泡宽度，触发库的
 * `horizontalScroll + requiredWidth` 横向滚动；格内文字换行显示全文。
 */
private val TableCellWidth = 220.dp

/** 表格字号相对正文的比例：表格是辅助信息，不抢正文视觉权重。 */
private const val TableTextScale = 0.8f

@Composable
fun MessageMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    cacheable: Boolean = true,
) {
    val segments = remember(content) { ImageRowSplitter.split(content) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (segment in segments) {
            when (segment) {
                is ContentSegment.Markdown -> LatexContent(segment.text, cacheable)
                is ContentSegment.ImageRow -> MarkdownImageRow(segment.images)
            }
        }
    }
}

/** 块级公式仍在 Markdown 片段内部单独分段渲染。 */
@Composable
private fun LatexContent(text: String, cacheable: Boolean) {
    val segments = remember(text) { LatexSplitter.split(text) }
    for (segment in segments) {
        when (segment) {
            is MdSegment.Markdown -> MarkdownBlock(segment.text, cacheable)
            is MdSegment.BlockMath -> BlockMathView(segment.latex)
        }
    }
}

/**
 * 独占行的网图：多张排成一行、超出横向滑动（与块级公式同一种浏览方式）；
 * 单张按原图比例铺开（宽 ≤ 视窗 80%、高 ≤ 视窗 55%）。点开走全屏预览。
 *
 * 多张用 [LazyRow]：一屏放不下的图（如 16 张的图搜回答）不会一次性全部发起下载/解码。
 */
@Composable
private fun MarkdownImageRow(images: List<MarkdownImageRef>) {
    if (images.size == 1) {
        MarkdownSingleImage(images.first())
        return
    }
    val windowWidth = LocalWindowInfo.current.containerDpSize.width
    val imageHeight = ChatMetrics.markdownImageHeight(windowWidth)
    val maxWidth = ChatMetrics.markdownImageMaxWidth(windowWidth)
    var preview by remember { mutableStateOf<MarkdownImageRef?>(null) }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(images, key = { index, image -> "$index:${image.url}" }) { _, image ->
            RemoteImage(
                url = image.url,
                contentDescription = image.alt,
                maxEdge = 1024,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(imageHeight)
                    .widthIn(min = ChatMetrics.MARKDOWN_IMAGE_MIN_WIDTH, max = maxWidth)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { preview = image },
            )
        }
    }
    preview?.let { image ->
        RemoteImagePreviewDialog(
            url = image.url,
            title = image.alt,
            onDismiss = { preview = null },
        )
    }
}

/**
 * 单张网图：先按「视窗宽 80%」满铺，超过「视窗高 55%」就按高度收窄，始终保原图比例。
 * 图片加载完才量到宽高比，之前用宽度上限 + 占位高度过渡。
 */
@Composable
private fun MarkdownSingleImage(image: MarkdownImageRef) {
    val window = LocalWindowInfo.current.containerDpSize
    var aspect by remember(image.url) { mutableFloatStateOf(0f) }
    var preview by remember { mutableStateOf<MarkdownImageRef?>(null) }
    val size = ChatMetrics.markdownSingleImageSize(aspect, window.width, window.height)
    val sizeModifier = if (size.height > 0.dp) {
        Modifier.size(size)
    } else {
        // 宽高比还没量到：占位先按宽度上限铺开，高度只封顶（防止超长图撑一帧）。
        Modifier
            .widthIn(max = size.width)
            .fillMaxWidth()
            .heightIn(max = ChatMetrics.markdownSingleImageMaxHeight(window.height))
    }
    RemoteImage(
        url = image.url,
        contentDescription = image.alt,
        maxEdge = 1280,
        contentScale = ContentScale.Fit,
        onAspect = { aspect = it },
        modifier = sizeModifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { preview = image },
    )
    preview?.let { image ->
        RemoteImagePreviewDialog(
            url = image.url,
            title = image.alt,
            onDismiss = { preview = null },
        )
    }
}

@Composable
private fun MarkdownBlock(text: String, cacheable: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val colors = ChatTheme.colors
    val chatType = ChatTheme.typography
    val body = chatType.messageBody
    val codeStyle = chatType.code
    val materialType = MaterialTheme.typography
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
    val markdownColors = markdownColor(
        text = scheme.onSurface,
        codeBackground = colors.codeBackground,
        inlineCodeBackground = colors.surfaceCreamStrong,
        dividerColor = colors.hairline,
        tableBackground = colors.surfaceCard,
    )
    val markdownType = markdownTypography(
        h1 = materialType.headlineLarge,
        h2 = materialType.headlineMedium,
        h3 = materialType.headlineSmall,
        h4 = materialType.titleMedium,
        h5 = materialType.titleSmall,
        h6 = materialType.titleSmall,
        text = body,
        paragraph = body,
        code = codeStyle.copy(color = colors.codeOnBackground),
        inlineCode = codeStyle.copy(
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
    )
    val dimens = markdownDimens(tableCellWidth = TableCellWidth)
    val components = markdownComponents(
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
            // 正文里的网图（如文搜图返回的 markdown 图片）走统一的远程加载器。
            // 库的默认 image/inlineImage 都依赖 Coil 风格的 transformer（没接就什么都不画），
            // 且 URL 必须从 AST 节点解析（model.content 是整段 markdown，不是链接）。
            image = { model -> MarkdownNetworkImage(model) },
            inlineImage = { model -> MarkdownNetworkImage(model) },
        )
    Markdown(
        state = rememberParsedMarkdown(prepared.text, cacheable),
        colors = markdownColors,
        typography = markdownType,
        modifier = Modifier.fillMaxWidth(),
        dimens = dimens,
        annotator = remember(mathStrings) { inlineMathAnnotator(mathStrings) },
        components = components,
        animations = NoTextSizeAnimation,
    )
}

/**
 * 已定稿的消息优先命中 [MarkdownParseCache]（库自带的 `rememberMarkdownState` 不跨 item 复用，
 * 滚动回看会反复解析）；流式中的消息走库的 conflate 解析路径，不把每个增量前缀塞进缓存。
 */
@Composable
private fun rememberParsedMarkdown(content: String, cacheable: Boolean): State {
    if (cacheable) {
        // 命中缓存直接返回，连一次 Loading 都不闪。
        MarkdownParseCache.get(content)?.let { return it }
    } else {
        val streaming by rememberMarkdownState(content, retainState = true).state.collectAsState()
        return streaming
    }
    val parsed by produceState<State>(initialValue = State.Loading(), content) {
        val hit = MarkdownParseCache.get(content)
        if (hit != null) {
            value = hit
            return@produceState
        }
        // parseMarkdown 自身把解析异常收成 State.Error，这里只需换线程执行。
        val result = withContext(Dispatchers.Default) { parseMarkdown(content) }
        if (result is State.Success) MarkdownParseCache.put(content, result)
        value = result
    }
    return parsed
}

/** 不做尺寸动画：正文流式重排时动画只会推高每帧的重组/布局成本。 */
private val NoTextSizeAnimation = object : MarkdownAnimations {
    override val animateTextSize: Modifier.() -> Modifier = { this }
}

@Composable
private fun MarkdownNetworkImage(model: MarkdownComponentModel) {
    // inline 图片路径：model.content 直接就是链接；
    // 块级图片路径：URL 在 AST 节点里（content 是整段 markdown，节点偏移只对它成立）。
    // 两种形态都要兼容，且解析失败不能崩。
    val direct = model.content.trim().takeIf {
        it.startsWith("http://") || it.startsWith("https://")
    }
    val handler = LocalReferenceLinkHandler.current
    val link = direct ?: runCatching {
        model.node.resolveImageLink(model.content, handler)
    }.getOrNull()
    ?: return
    val alt = if (direct != null) {
        null
    } else {
        runCatching { model.node.resolveImageAlt(model.content) }.getOrNull()
    }
    RemoteImage(
        url = link,
        contentDescription = alt,
        maxEdge = 1280,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
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
