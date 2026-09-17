package com.zcw.chatai.ui.md.latex

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

private val SCRIPT_FONT_SIZE = 12.sp

private val ACCENT_COMBINING = mapOf(
    AccentKind.HAT to '\u0302',
    AccentKind.WIDEHAT to '\u0302',
    AccentKind.TILDE to '\u0303',
    AccentKind.WIDETILDE to '\u0303',
    AccentKind.BAR to '\u0304',
    AccentKind.OVERLINE to '\u0305',
    AccentKind.DOT to '\u0307',
    AccentKind.DDOT to '\u0308',
    AccentKind.VEC to '\u20D7',
)

/**
 * Appends an inline math fragment into an [AnnotatedString] so it flows with the surrounding
 * paragraph text. Structural atoms that need a 2-D layout (fractions, radicals, …) flatten to a
 * 1-D approximation (`x^2/4`, `√(1-e^2)`) because the markdown annotator can only emit spans.
 */
fun AnnotatedString.Builder.appendInlineMath(latex: String) {
    when (val atom = MathParser.parse(latex)) {
        is Group -> for (inner in atom.atoms) appendAtomInline(inner, Color.Unspecified)
        else -> appendAtomInline(atom, Color.Unspecified)
    }
}

internal fun AnnotatedString.Builder.appendAtomInline(atom: MathAtom, color: Color) {
    when (atom) {
        is Sym -> append(symSpan(atom))

        is Space -> {
            val raw = atom.emWidth
            when {
                raw <= 0f -> Unit
                raw < 0.3f -> append('\u2009')
                raw < 0.8f -> append('\u2002')
                raw < 1.5f -> append('\u2003')
                else -> append('\u2003').also { append('\u2003') }
            }
        }

        is Styled -> {
            val span = styleSpan(atom.style)
            withStyle(span) {
                if (atom.style == MathStyle.DOUBLE_STRUCK || atom.style == MathStyle.CALLIGRAPHIC) {
                    for (inner in atom.atoms) appendMapped(inner, atom.style)
                } else {
                    for (inner in atom.atoms) appendAtomInline(inner, color)
                }
            }
        }

        is Group -> for (inner in atom.atoms) appendAtomInline(inner, color)

        is Script -> {
            appendAtomInline(atom.base, color)
            appendScripts(atom.sub, atom.sup, color)
        }

        is LargeOp -> {
            append(atom.symbol)
            appendScripts(atom.sub, atom.sup, color)
        }

        is Frac -> appendFracInline(atom, color)

        is Radical -> {
            appendScripts(sub = null, sup = atom.index, color = color)
            append('\u221A')
            appendAtomInlineMaybeParens(atom.radicand, color)
        }

        is Delim -> {
            append(atom.left)
            appendAtomInline(atom.content, color)
            append(atom.right)
        }

        is Accent -> {
            appendAtomInline(atom.base, color)
            ACCENT_COMBINING[atom.kind]?.let { append(it) }
        }

        is Matrix -> appendMatrixInline(atom, color)
    }
}

internal fun symSpan(sym: Sym): AnnotatedString {
    val italic = sym.kind == SymKind.VARIABLE &&
        sym.text.length == 1 &&
        sym.text[0].isLetter() &&
        !isGreek(sym.text[0])
    return buildAnnotatedString {
        val spacing = kindSpacing(sym.kind)
        if (spacing.first > 0) append('\u2009')
        withStyle(SpanStyle(fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal)) {
            append(sym.text)
        }
        if (spacing.second > 0) append('\u2009')
    }
}

private fun AnnotatedString.Builder.appendFracInline(frac: Frac, color: Color) {
    if (!frac.drawBar) {
        appendAtomInline(frac.num, color)
        append(',')
        appendAtomInline(frac.den, color)
        return
    }
    val numEmpty = isEmptyAtom(frac.num)
    val denEmpty = isEmptyAtom(frac.den)
    if (!numEmpty) appendAtomInlineMaybeParens(frac.num, color)
    if (!numEmpty && !denEmpty) append('/')
    if (!denEmpty) appendAtomInlineMaybeParens(frac.den, color)
}

private fun AnnotatedString.Builder.appendMatrixInline(matrix: Matrix, color: Color) {
    append(matrix.delim.left)
    matrix.rows.forEachIndexed { rowIndex, row ->
        if (rowIndex > 0) append("; ")
        row.forEachIndexed { colIndex, cell ->
            if (colIndex > 0) append(", ")
            appendAtomInline(cell, color)
        }
    }
    append(matrix.delim.right)
}

private fun AnnotatedString.Builder.appendAtomInlineMaybeParens(atom: MathAtom, color: Color) {
    if (needsInlineParens(atom)) {
        append('(')
        appendAtomInline(atom, color)
        append(')')
    } else {
        appendAtomInline(atom, color)
    }
}

private fun AnnotatedString.Builder.appendScripts(sub: MathAtom?, sup: MathAtom?, color: Color) {
    sup?.let {
        withStyle(SpanStyle(fontSize = SCRIPT_FONT_SIZE, baselineShift = BaselineShift.Superscript)) {
            appendAtomInline(it, color)
        }
    }
    sub?.let {
        withStyle(SpanStyle(fontSize = SCRIPT_FONT_SIZE, baselineShift = BaselineShift.Subscript)) {
            appendAtomInline(it, color)
        }
    }
}

private fun AnnotatedString.Builder.appendMapped(atom: MathAtom, style: MathStyle) {
    when (atom) {
        is Sym -> {
            val mapped = atom.text.map { ch ->
                when (style) {
                    MathStyle.DOUBLE_STRUCK -> MathSymbols.mapDoubleStruck(ch)
                    MathStyle.CALLIGRAPHIC -> MathSymbols.mapCalligraphic(ch)
                    else -> ch.toString()
                }
            }.joinToString("")
            append(mapped)
        }

        is Group -> for (inner in atom.atoms) appendMapped(inner, style)

        else -> appendAtomInline(atom, Color.Unspecified)
    }
}

private fun needsInlineParens(atom: MathAtom): Boolean = when (atom) {
    is Frac, is Matrix, is LargeOp -> true
    is Group -> {
        val kids = atom.atoms.filterNot(::isEmptyAtom)
        kids.size > 1 && kids.any { child ->
            child is Frac || child is LargeOp || child is Matrix ||
                (child is Sym && (child.kind == SymKind.BIN_OP || child.kind == SymKind.REL_OP))
        }
    }
    else -> false
}

private fun isEmptyAtom(atom: MathAtom): Boolean = when (atom) {
    is Space -> true
    is Group -> atom.atoms.isEmpty() || atom.atoms.all(::isEmptyAtom)
    else -> false
}

private fun kindSpacing(kind: SymKind): Pair<Int, Int> = when (kind) {
    SymKind.BIN_OP, SymKind.REL_OP -> 1 to 1
    SymKind.PUNCT -> 0 to 1
    SymKind.FUNCTION -> 0 to 1
    else -> 0 to 0
}

private fun isGreek(ch: Char): Boolean = ch.code in 0x0370..0x03FF

private fun styleSpan(style: MathStyle): SpanStyle = when (style) {
    MathStyle.TEXT -> SpanStyle(fontFamily = FontFamily.Default, fontStyle = FontStyle.Normal)
    MathStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    MathStyle.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
    MathStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    MathStyle.ROMAN -> SpanStyle(fontStyle = FontStyle.Normal)
    MathStyle.DOUBLE_STRUCK -> SpanStyle()
    MathStyle.CALLIGRAPHIC -> SpanStyle()
}
