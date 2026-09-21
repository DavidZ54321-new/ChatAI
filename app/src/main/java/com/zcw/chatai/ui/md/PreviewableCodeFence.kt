package com.zcw.chatai.ui.md

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.zcw.chatai.ui.theme.ChatTheme
import dev.snipme.highlights.Highlights
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * 围栏代码块：**外观与库默认的 `MarkdownHighlightedCodeFence` 完全一致**（同一个
 * [MarkdownHighlightedCode]，语言头也由它自己画），只是当 info string 是可预览语言
 * （mermaid / svg / html）且围栏**已闭合**时，在语言头右侧多叠一个「预览」按钮。
 *
 * 以前这份内容在围栏闭合后被 `PreviewBlockSplitter` 抽走、换成另一套卡片，
 * 于是流式与定稿是两种样式；现在闭合前后是同一个组件，只有按钮出现/消失，代码块本身不变。
 * 流式中（围栏未闭合）不挂入口：半截的图/页面预览出来只会更糟。
 */
@Composable
fun PreviewableCodeFence(
    content: String,
    node: ASTNode,
    highlightsBuilder: Highlights.Builder,
    onPreview: ((PreviewTarget) -> Unit)?,
) {
    MarkdownCodeFence(content, node) { code, language, style ->
        // 空代码块不值得给预览入口（旧 PreviewBlockSplitter 同样跳过空白块）。
        val previewLanguage = previewLanguageOf(language)
        val target = if (previewLanguage != null && code.isNotBlank() && hasClosingFence(node)) {
            PreviewTarget(previewLanguage, code)
        } else {
            null
        }
        Box {
            MarkdownHighlightedCode(
                code = code,
                language = language,
                style = style,
                highlightsBuilder = highlightsBuilder,
                showHeader = true,
            )
            if (target != null && onPreview != null) {
                PreviewHeaderButton(
                    onClick = { onPreview(target) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // 语言头几何：外框上留白 8dp + 行内上留白 4dp，24dp 高与复制按钮同心；
                        // 右端让开 8dp 行内留白 + 24dp 复制按钮 + 8dp 间距。
                        .padding(top = 12.dp, end = 40.dp)
                        .height(24.dp),
                )
            }
        }
    }
}

/** 围栏是否闭合：AST 里出现 `CODE_FENCE_END` 子节点。流式中（未闭合）不挂预览入口。 */
internal fun hasClosingFence(node: ASTNode): Boolean =
    node.children.any { it.type == MarkdownTokenTypes.CODE_FENCE_END }

@Composable
private fun PreviewHeaderButton(onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClickLabel = "预览", onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "预览",
            // 代码块背景在所有主题里都是深色（Claude #181715 / ChatGPT #0D0D0D），
            // 所以这里用 codeHeaderText 而不是 colorScheme.primary——ChatGPT 浅色主题的
            // primary 是纯黑，压在深色代码底上会看不见。下划线给一个可点的暗示。
            style = MaterialTheme.typography.labelMedium.copy(
                textDecoration = TextDecoration.Underline,
            ),
            color = ChatTheme.colors.codeHeaderText,
            maxLines = 1,
        )
    }
}
