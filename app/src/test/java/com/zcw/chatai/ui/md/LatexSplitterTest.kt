package com.zcw.chatai.ui.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexSplitterTest {

    @Test
    fun textWithoutFormulaReturnsSingleMarkdownSegment() {
        val text = "hello **world**\nsecond line"
        assertEquals(listOf(MdSegment.Markdown(text)), LatexSplitter.split(text))
    }

    @Test
    fun inlineBlockFormulaBetweenTextIsSplit() {
        assertEquals(
            listOf(
                MdSegment.Markdown("前文 "),
                MdSegment.BlockMath("x^2"),
                MdSegment.Markdown(" 后文"),
            ),
            LatexSplitter.split("前文 \$\$x^2\$\$ 后文"),
        )
    }

    @Test
    fun multilineBlockFormulaIsSplit() {
        assertEquals(
            listOf(
                MdSegment.Markdown("before\n"),
                MdSegment.BlockMath("\\frac{a}{b}"),
                MdSegment.Markdown("\nafter"),
            ),
            LatexSplitter.split("before\n\$\$\n\\frac{a}{b}\n\$\$\nafter"),
        )
    }

    @Test
    fun formulaSurroundedByOnlyWhitespaceIsTrimmed() {
        assertEquals(listOf(MdSegment.BlockMath("x^2")), LatexSplitter.split("\$\$\n  x^2  \n\$\$"))
    }

    @Test
    fun backtickFenceContentIsNotSplit() {
        val text = "```\n\$\$x\$\$\n```\nafter \$\$y\$\$"
        assertEquals(
            listOf(
                MdSegment.Markdown("```\n\$\$x\$\$\n```\nafter "),
                MdSegment.BlockMath("y"),
            ),
            LatexSplitter.split(text),
        )
    }

    @Test
    fun tildeFenceContentIsNotSplit() {
        val text = "~~~\n\$\$x\$\$\n~~~"
        assertEquals(listOf(MdSegment.Markdown(text)), LatexSplitter.split(text))
    }

    @Test
    fun indentedFenceIsRecognized() {
        val text = "  ```\n\$\$x\$\$\n  ```"
        assertEquals(listOf(MdSegment.Markdown(text)), LatexSplitter.split(text))
    }

    @Test
    fun fenceIsClosedOnlyBySameCharacter() {
        val text = "```\n\$\$x\$\$\n~~~\n\$\$y\$\$\n```"
        assertEquals(listOf(MdSegment.Markdown(text)), LatexSplitter.split(text))
    }

    @Test
    fun unclosedFormulaAtEndOfStreamIsEmitted() {
        assertEquals(
            listOf(MdSegment.Markdown("text "), MdSegment.BlockMath("x^2")),
            LatexSplitter.split("text \$\$x^2"),
        )
    }

    @Test
    fun singleDollarDoesNotSplit() {
        for (text in listOf("\$x\$", "\$5", "price is \$5 and \$6")) {
            assertEquals(listOf(MdSegment.Markdown(text)), LatexSplitter.split(text))
        }
    }

    @Test
    fun singleDollarInsideFormulaStaysLiteral() {
        assertEquals(
            listOf(MdSegment.BlockMath("a \$ b")),
            LatexSplitter.split("\$\$a \$ b\$\$"),
        )
    }

    @Test
    fun adjacentMarkdownFragmentsAreMergedIntoOneSegment() {
        val text = "before\n```\n\$\$inside\$\$\n```\nafter"
        assertEquals(listOf(MdSegment.Markdown(text)), LatexSplitter.split(text))
    }

    @Test
    fun backToBackFormulasProduceNoEmptyMarkdownSegment() {
        assertEquals(
            listOf(MdSegment.BlockMath("a"), MdSegment.BlockMath("b")),
            LatexSplitter.split("\$\$a\$\$\$\$b\$\$"),
        )
    }

    @Test
    fun formulaAtStartAndEndOfText() {
        assertEquals(
            listOf(MdSegment.BlockMath("a"), MdSegment.Markdown("mid"), MdSegment.BlockMath("b")),
            LatexSplitter.split("\$\$a\$\$mid\$\$b\$\$"),
        )
    }

    @Test
    fun emptyFormulaIsDropped() {
        assertEquals(listOf(MdSegment.Markdown("x \$\$ \$\$ y")), LatexSplitter.split("x \$\$ \$\$ y"))
    }

    @Test
    fun emptyInputProducesNoSegments() {
        assertTrue(LatexSplitter.split("").isEmpty())
    }

    @Test
    fun segmentsRoundTripToOriginalText() {
        val text = "a\n\$\$b\$\$\nc\n```\n\$\$d\$\$\n```\nf \$\$g\$\$ h"
        val segments = LatexSplitter.split(text)
        assertEquals(
            listOf("b", "g"),
            segments.filterIsInstance<MdSegment.BlockMath>().map { it.latex },
        )
        val joined = segments.joinToString("") { segment ->
            when (segment) {
                is MdSegment.Markdown -> segment.text
                is MdSegment.BlockMath -> "\$\$" + segment.latex + "\$\$"
            }
        }
        assertEquals(text, joined)
    }
}
