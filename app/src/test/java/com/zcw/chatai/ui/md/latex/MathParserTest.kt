package com.zcw.chatai.ui.md.latex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MathParserTest {

    @Test
    fun powerAndFractionParseIntoThreeAtoms() {
        val atom = MathParser.parse("""x^2+\frac{a}{b}""")

        assertTrue("expected Group, got $atom", atom is Group)
        val atoms = (atom as Group).atoms
        assertEquals(3, atoms.size)

        val script = atoms[0]
        assertTrue("expected Script, got $script", script is Script)
        script as Script
        assertEquals(Sym("x", SymKind.VARIABLE), script.base)
        assertEquals(Sym("2", SymKind.ORDINARY), script.sup)
        assertNull(script.sub)

        assertEquals(Sym("+", SymKind.BIN_OP), atoms[1])

        val frac = atoms[2]
        assertTrue("expected Frac, got $frac", frac is Frac)
        frac as Frac
        assertEquals(Sym("a", SymKind.VARIABLE), frac.num)
        assertEquals(Sym("b", SymKind.VARIABLE), frac.den)
        assertTrue(frac.drawBar)
    }

    @Test
    fun sumWithLimitsKeepsSubAndSuperscript() {
        val atom = MathParser.parse("""\sum_{i=1}^{n} i""")

        assertTrue("expected Group, got $atom", atom is Group)
        val atoms = (atom as Group).atoms
        assertEquals(2, atoms.size)

        val op = atoms[0]
        assertTrue("expected LargeOp, got $op", op is LargeOp)
        op as LargeOp
        assertEquals("\u2211", op.symbol)
        assertNotNull(op.sub)
        assertNotNull(op.sup)

        assertEquals(Sym("i", SymKind.VARIABLE), atoms[1])
    }

    @Test
    fun pmatrixParsesIntoTwoByTwoGrid() {
        val atom = MathParser.parse("""\begin{pmatrix}a & b\\c & d\end{pmatrix}""")

        assertTrue("expected Matrix, got $atom", atom is Matrix)
        val matrix = atom as Matrix
        assertEquals(MatrixDelim.PAREN, matrix.delim)
        assertEquals(MatrixAlign.CENTERED, matrix.alignMode)
        assertEquals(2, matrix.rows.size)
        assertEquals(2, matrix.rows[0].size)
        assertEquals(2, matrix.rows[1].size)
        assertEquals(Sym("a", SymKind.VARIABLE), matrix.rows[0][0])
        assertEquals(Sym("b", SymKind.VARIABLE), matrix.rows[0][1])
        assertEquals(Sym("c", SymKind.VARIABLE), matrix.rows[1][0])
        assertEquals(Sym("d", SymKind.VARIABLE), matrix.rows[1][1])
    }

    @Test
    fun greekLetterAndRadicalParse() {
        val atom = MathParser.parse("""\sqrt{\alpha}""")

        assertTrue("expected Radical, got $atom", atom is Radical)
        val radical = atom as Radical
        assertNull(radical.index)
        assertEquals(Sym("\u03B1", SymKind.VARIABLE), radical.radicand)
    }

    @Test
    fun leftRightDelimiterParses() {
        val atom = MathParser.parse("""\left(\frac{1}{2}\right)""")

        assertTrue("expected Delim, got $atom", atom is Delim)
        val delim = atom as Delim
        assertEquals("(", delim.left)
        assertEquals(")", delim.right)
        val content = delim.content as Group
        assertEquals(1, content.atoms.size)
        assertTrue("expected Frac, got ${content.atoms[0]}", content.atoms[0] is Frac)
    }

    @Test
    fun singleAtomInputReturnsTheAtomDirectly() {
        assertEquals(Sym("x", SymKind.VARIABLE), MathParser.parse("x"))
    }

    @Test
    fun blankInputProducesEmptyGroup() {
        val atom = MathParser.parse("")

        assertTrue("expected Group, got $atom", atom is Group)
        assertTrue((atom as Group).atoms.isEmpty())
    }

    @Test
    fun unknownCommandDegradesToLiteralSymbol() {
        assertEquals(Sym("""\notacommand""", SymKind.ORDINARY), MathParser.parse("""\notacommand"""))
    }

    @Test(timeout = 5_000)
    fun malformedInputDoesNotThrow() {
        val samples = listOf(
            """\frac{""",
            "{{{{",
            """\left(""",
            """\begin{pmatrix}a""",
            "}}",
            "\$",
            "\\",
            """\right""",
            """\end{""",
        )
        for (latex in samples) {
            assertNotNull(MathParser.parse(latex))
        }
    }

    @Test(timeout = 5_000)
    fun strayClosingBracesTerminate() {
        val samples = listOf(
            "}",
            "}}",
            "a}b",
            """\frac{1}{2}}""",
            "x^2}}}}",
            """\begin{pmatrix}a}b\\c\end{pmatrix}""",
            "}}}}}}}}",
        )
        for (latex in samples) {
            assertNotNull(MathParser.parse(latex))
        }
    }

    @Test
    fun incompleteFracKeepsEmptyDenominatorGroup() {
        val atom = MathParser.parse("""\frac{T^2}""")

        assertTrue("expected Frac, got $atom", atom is Frac)
        val frac = atom as Frac
        assertTrue("expected Script numerator, got ${frac.num}", frac.num is Script)
        assertTrue("expected empty Group den, got ${frac.den}", frac.den is Group)
        assertTrue((frac.den as Group).atoms.isEmpty())
    }

    @Test
    fun formulasProduceNonEmptyAtomTrees() {
        val samples = listOf(
            """x^2+\frac{a}{b}""",
            """\sum_{i=1}^{n} i""",
            """\begin{pmatrix}a & b\\c & d\end{pmatrix}""",
            """\int_0^\infty e^{-x} dx""",
            """\vec{v} \cdot \hat{n}""",
            """\mathbb{R} \subset \mathbb{C}""",
        )
        for (latex in samples) {
            val flat = flatten(MathParser.parse(latex))
            assertTrue("expected non-empty tree for [$latex]", flat.isNotEmpty())
        }
    }

    private fun flatten(atom: MathAtom): List<MathAtom> = when (atom) {
        is Sym, is Space -> listOf(atom)
        is Group -> {
            val nested = atom.atoms.flatMap { flatten(it) }
            if (nested.isEmpty()) emptyList() else listOf(atom)
        }
        is Frac -> listOf(atom) + flatten(atom.num) + flatten(atom.den)
        is Script -> listOf(atom) + flatten(atom.base) +
            (atom.sub?.let { flatten(it) } ?: emptyList()) +
            (atom.sup?.let { flatten(it) } ?: emptyList())
        is Radical -> listOf(atom) + flatten(atom.radicand)
        is LargeOp -> listOf(atom)
        is Delim -> listOf(atom) + flatten(atom.content)
        is Styled -> listOf(atom) + atom.atoms.flatMap { flatten(it) }
        is Accent -> listOf(atom) + flatten(atom.base)
        is Matrix -> listOf(atom) + atom.rows.flatMap { row -> row.flatMap { flatten(it) } }
    }
}
