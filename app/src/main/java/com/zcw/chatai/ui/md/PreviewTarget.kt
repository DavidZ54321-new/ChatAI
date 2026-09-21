package com.zcw.chatai.ui.md

import androidx.compose.runtime.compositionLocalOf

/** 可预览的围栏语言；其余语言按普通代码块渲染，没有预览入口。 */
enum class PreviewLanguage { MERMAID, SVG, HTML, PLANTUML }

/** 全屏 viewer 的目标：围栏语言 + 去掉围栏行后的源码。 */
data class PreviewTarget(val language: PreviewLanguage, val code: String)

/** 全屏 viewer 打开器：由 ChatScreen 提供（previewPage state），列表内的代码块只管调用。 */
val LocalPreviewOpener = compositionLocalOf<((PreviewTarget) -> Unit)?> { null }

/**
 * 围栏 info string 的首个 token（大小写不敏感）对应的可预览语言；不可预览返回 null。
 *
 * 切首个 token 用 `isWhitespace()` 手写循环：仓库约定不写 `Regex`
 * （ICU 引擎不认识 Java 专有语法，会在类初始化时炸）。
 */
fun previewLanguageOf(infoString: String?): PreviewLanguage? {
    val trimmed = infoString?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val end = trimmed.indexOfFirst { it.isWhitespace() }.takeIf { it >= 0 } ?: trimmed.length
    return when (trimmed.substring(0, end).lowercase()) {
        "mermaid" -> PreviewLanguage.MERMAID
        "svg" -> PreviewLanguage.SVG
        "html" -> PreviewLanguage.HTML
        // PlantUML 的围栏语言常见三种写法：plantuml / puml / uml。
        "plantuml", "puml", "uml" -> PreviewLanguage.PLANTUML
        else -> null
    }
}
