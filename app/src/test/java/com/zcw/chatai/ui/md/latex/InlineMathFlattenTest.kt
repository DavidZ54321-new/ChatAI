package com.zcw.chatai.ui.md.latex

import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InlineMathFlattenTest {

    @Test
    fun ellipseInlineFracDoesNotBecomeASquare() {
        // 段落里 `\(C:\frac{x^2}{4}+y^2=1\)` 曾经整段分数变成 □。
        assertEquals("C:x2/4+y2=1", flatten("""C:\frac{x^2}{4}+y^2=1"""))
    }

    @Test
    fun fracWrapsSumsButNotJuxtaposedLetters() {
        assertEquals("y0/(x0+2)", flatten("""\frac{y_0}{x_0+2}"""))
        assertEquals("dA/dt", flatten("""\frac{dA}{dt}"""))
        assertEquals("(a/b)/c", flatten("""\frac{\frac{a}{b}}{c}"""))
    }

    @Test
    fun radicalUsesSqrtPrefixAndParensWhenNeeded() {
        assertEquals("√a", flatten("""\sqrt{a}"""))
        assertEquals("√(1-e2)", flatten("""\sqrt{1-e^2}"""))
        assertEquals("3√x", flatten("""\sqrt[3]{x}"""))
    }

    @Test
    fun leftRightAndBinomKeepDelimiters() {
        assertEquals("(1/2)", flatten("""\left(\frac{1}{2}\right)"""))
        assertEquals("(n,k)", flatten("""\binom{n}{k}"""))
    }

    @Test
    fun accentUsesCombiningMark() {
        val hat = flatten("""\hat{n}""")
        assertEquals("n", hat.take(1))
        assertEquals("\u0302", hat.drop(1))
        val vec = flatten("""\vec{v}""")
        assertEquals("v", vec.take(1))
        assertEquals("\u20D7", vec.drop(1))
    }

    @Test
    fun pmatrixFlattensToDelimitedGrid() {
        assertEquals("(a, b; c, d)", flatten("""\begin{pmatrix}a & b\\c & d\end{pmatrix}"""))
    }

    @Test
    fun incompleteFracHasNoPlaceholderSquare() {
        val samples = listOf(
            """\frac{""",
            """\frac{T^2}""",
            """\sqrt{""",
            """\hat{""",
            """C:\frac{x^2}{4}+y^2=1""",
            """\sqrt{1-e^2}""",
            """\left(\frac{1}{2}\right)""",
        )
        for (latex in samples) {
            val text = flatten(latex)
            assertFalse("unexpected □ in [$latex] → [$text]", text.contains('\u25A1'))
        }
    }

    private fun flatten(latex: String): String =
        buildAnnotatedString { appendInlineMath(latex) }
            .text
            .replace("\u2009", "")
            .replace("\u2002", "")
            .replace("\u2003", "")
}
