package com.zcw.chatai.ui.md.latex

import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉死行内数学的 AnnotatedString 不变量：所有 span 区间必须落在文本长度内。
 *
 * 背景：Compose 无障碍转换 (`toAccessibilitySpannableString`) 会按 spanStyle 的原始区间
 * 调 `SpannableString.setSpan`；一旦 `end > text.length` 就抛
 * `IndexOutOfBoundsException: setSpan ... ends beyond length`。
 * `AnnotatedString.Builder.toAnnotatedString()` 只对 `pushStyle` 的开放式区间用
 * `text.length` 兜底，**显式 `addStyle(style, start, end)` 的 end 不做校验**。
 */
class InlineMathSpanBoundsTest {

    private val samples = listOf(
        "a", "x^2", "x_1", "x^2_1", """\frac{a}{b}""", """\frac{\frac{a}{b}}{c}""",
        """\sqrt{1-e^2}""", """\sqrt[3]{x}""", """\hat{n}""", """\vec{v}""", """\bar{x}""",
        """\overline{abc}""", """\mathbb{R}""", """\mathbb{ABC}""", """\mathcal{L}""",
        """\left(\frac{1}{2}\right)""", """\binom{n}{k}""",
        """\sum_{i=1}^{n} i""", """\int_0^\infty e^{-x}dx""", """\lim_{x\to0}""",
        """\begin{pmatrix}a & b\\c & d\end{pmatrix}""",
        """\begin{cases}a & b\\c & d\end{cases}""",
        """\text{hello world}""", """\mathbf{x}""", """\boldsymbol{\alpha}""",
        """\frac{""", """\sqrt{""", """\hat{""", """x^{""", """x_{""", """\""", """\\""",
        """\left(\right)""", """\begin{pmatrix}""", """\middle""", """$$\$""",
        "x^2/4", """C:\frac{x^2}{4}+y^2=1""", """\alpha\beta\gamma\Delta\Omega""",
        """\sin\cos\log\ln\exp""", """\cdot\cdots\ldots\times\pm\mp""",
        """\notacommand{x}""", """\hat{}""", """\frac{}{}""",
        "1234567890", "", " ", "\u2009", "é", "中", "\uD835\uDD3B",
    )

    @Test
    fun inlineMathSpansAreWithinTextLength() {
        val violations = mutableListOf<String>()
        for (latex in samples) {
            val annotated = runCatching {
                buildAnnotatedString { appendInlineMath(latex) }
            }.getOrElse { e ->
                violations += "THROW latex=[$latex]: ${e::class.simpleName}: ${e.message}"
                continue
            }
            annotated.spanStyles.forEach { span ->
                if (span.start < 0 || span.end > annotated.length || span.start > span.end) {
                    violations += "latex=[$latex] len=${annotated.length} span=(${span.start},${span.end})"
                }
            }
            annotated.getStringAnnotations(0, annotated.length).forEach { ann ->
                if (ann.start < 0 || ann.end > annotated.length || ann.start > ann.end) {
                    violations += "latex=[$latex] annotation=(${ann.start},${ann.end})"
                }
            }
        }
        assertTrue(
            "out-of-range math spans:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun inlineMathNeverLeavesPlaceholdersInOutput() {
        val violations = mutableListOf<String>()
        for (latex in samples) {
            val text = runCatching { buildAnnotatedString { appendInlineMath(latex) }.text }
                .getOrElse { e ->
                    violations += "THROW latex=[$latex]: ${e.message}"
                    continue
                }
            if (text.contains('\uE000') || text.contains('\uE001')) {
                violations += "latex=[$latex] still has placeholder: [$text]"
            }
        }
        assertTrue("placeholder leaked:\n" + violations.joinToString("\n"), violations.isEmpty())
    }
}
