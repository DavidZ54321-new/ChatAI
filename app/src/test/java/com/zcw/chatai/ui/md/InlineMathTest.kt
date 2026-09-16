package com.zcw.chatai.ui.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMathTest {

    private val open = '\uE000'
    private val close = '\uE001'

    @Test
    fun replacesSimpleInlineMath() {
        val prepared = prepareInlineMath("复杂度是 \$O(n \\log n)\$ 级别")

        assertEquals(listOf("O(n \\log n)"), prepared.formulas)
        assertEquals("复杂度是 ${open}0$close 级别", prepared.text)
        assertFalse(prepared.text.contains("$"))
    }

    @Test
    fun replacesMultipleFormulasInOrder() {
        val prepared = prepareInlineMath("先 \$a\$ 再 \$b^2\$ 结束")

        assertEquals(listOf("a", "b^2"), prepared.formulas)
        assertEquals("先 ${open}0$close 再 ${open}1$close 结束", prepared.text)
    }

    @Test
    fun keepsDollarInsideCodeFence() {
        val source = "```\nval price = \$5\n\$x\$\n```"
        val prepared = prepareInlineMath(source)

        assertTrue(prepared.formulas.isEmpty())
        assertEquals(source, prepared.text)
    }

    @Test
    fun keepsDollarInsideInlineCode() {
        val prepared = prepareInlineMath("用 `\$x\$` 表示")

        assertTrue(prepared.formulas.isEmpty())
        assertEquals("用 `\$x\$` 表示", prepared.text)
    }

    @Test
    fun keepsBlockMathForSplitter() {
        val source = "推导 \$\$T(n) = 2T(n/2)\$\$ 结束"
        val prepared = prepareInlineMath(source)

        assertTrue(prepared.formulas.isEmpty())
        assertEquals(source, prepared.text)
    }

    @Test
    fun rejectsCurrencyAmounts() {
        val prepared = prepareInlineMath("花了 \$5 和 \$3 块钱")

        assertTrue(prepared.formulas.isEmpty())
    }

    @Test
    fun rejectsUnterminatedInlineMath() {
        val prepared = prepareInlineMath("没有闭合 \$x^2")

        assertTrue(prepared.formulas.isEmpty())
    }

    @Test
    fun rejectsWhitespaceImmediatelyInsideDelimiters() {
        val prepared = prepareInlineMath("不是公式 \$ x \$ 收尾")

        assertTrue(prepared.formulas.isEmpty())
    }

    @Test
    fun rejectsFormulaSpanningLines() {
        val prepared = prepareInlineMath("跨行 \$x +\ny\$ 收尾")

        assertTrue(prepared.formulas.isEmpty())
    }

    @Test
    fun ignoresEscapedDollar() {
        val prepared = prepareInlineMath("转义 \\\$x 不是公式")

        assertTrue(prepared.formulas.isEmpty())
    }

    @Test
    fun splitsPlaceholdersIntoTypedPieces() {
        val pieces = splitPlaceholders("前${open}0${close}中${open}1${close}后")

        assertEquals(5, pieces.size)
        assertEquals(InlinePiece.Text("前"), pieces[0])
        assertEquals(InlinePiece.Formula(0), pieces[1])
        assertEquals(InlinePiece.Text("中"), pieces[2])
        assertEquals(InlinePiece.Formula(1), pieces[3])
        assertEquals(InlinePiece.Text("后"), pieces[4])
    }

    @Test
    fun plainTextSplitsIntoSingleTextPiece() {
        val pieces = splitPlaceholders("没有占位符")

        assertEquals(listOf(InlinePiece.Text("没有占位符")), pieces)
    }
}
