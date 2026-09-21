package com.zcw.chatai.ui.md

import androidx.compose.ui.text.AnnotatedString
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码高亮出站文本的 span 不变量回归。
 *
 * `dev.snipme:highlights` 1.1.0 的 `MultilineCommentLocator` 对 `*&#47;path&#47;*` 这类代码
 * 会返回**反向区间** `(6, 2)`（SnipMeDev/Highlights#75），mikepenz 的原版
 * `buildHighlightedAnnotatedString` 直接把它丢给 `addStyle`，导致
 * `IllegalArgumentException: Reversed range is not supported`；任何越界区间则会做进
 * `AnnotatedString`，最终在 Compose 无障碍转换里抛
 * `IndexOutOfBoundsException: setSpan ... ends beyond length`。
 * 本测试钉住 [buildSafeHighlightedAnnotatedString] 永远只产出合法 span。
 */
class SafeHighlightedCodeTest {

    private val samples = listOf(
        "val x = 1",
        "val x = 1\n",
        "*/path/*",
        "/* comment",
        "/* unterminated",
        "/**/ */ /*",
        "*/ */ /*",
        "public class ExampleClass {}",
        "fun main() { println(\"hi\") }",
        "def f():\n    return 1",
        "# heading\n**bold**",
        "SELECT * FROM t WHERE a = 1;",
        "{\"a\": 1}",
        "<div>hi</div>",
        "const a = () => 1",
        "int main() { return 0; }",
        "// trailing comment",
        "x = 1 # comment",
        "import os\nprint(os.name)",
        "a",
        "",
        "\n",
        "```",
        "\$\$x\$\$",
        "\\frac{a}{b}",
        "1+1=2",
        "class A { fun b() {} }",
        "\u00e9 = '\u4e2d'",
        "*/",
        "/*",
        "*/ /* */",
    )

    private val languages = listOf(
        null, "kotlin", "java", "python", "javascript", "typescript",
        "c", "cpp", "csharp", "go", "rust", "json", "xml", "html",
        "sql", "bash", "shell", "css", "yaml", "markdown", "swift",
        "php", "ruby", "plaintext",
    )

    private fun builder() = Highlights.Builder().theme(SyntaxThemes.atom(darkMode = true))

    @Test
    fun isValidHighlightRangeRejectsBadRanges() {
        assertTrue(isValidHighlightRange(0, 1, 3))
        assertTrue(isValidHighlightRange(2, 3, 3))
        assertFalse("reversed", isValidHighlightRange(6, 2, 8))
        assertFalse("empty", isValidHighlightRange(2, 2, 8))
        assertFalse("beyond length", isValidHighlightRange(0, 9, 8))
        assertFalse("negative", isValidHighlightRange(-1, 2, 8))
        assertFalse("zero length text", isValidHighlightRange(0, 1, 0))
    }

    @Test
    fun knownBadMultilineCommentCodeDoesNotThrowAndKeepsText() {
        val annotated = buildSafeHighlightedAnnotatedString("*/path/*", "kotlin", builder())
        assertEquals("*/path/*", annotated.text)
        assertSpansValid(annotated)
    }

    @Test
    fun everySampleAndLanguageProducesOnlyValidSpans() {
        val violations = mutableListOf<String>()
        for (code in samples) {
            for (language in languages) {
                val annotated = runCatching {
                    buildSafeHighlightedAnnotatedString(code, language, builder())
                }.getOrElse { e ->
                    violations += "THROW code=[$code] lang=$language: ${e::class.simpleName}: ${e.message}"
                    continue
                }
                if (annotated.text != code) {
                    violations += "text changed code=[$code] lang=$language -> [${annotated.text}]"
                }
                annotated.spanStyles.forEach { span ->
                    if (span.start < 0 || span.start >= span.end || span.end > annotated.length) {
                        violations += "code=[$code] lang=$language len=${annotated.length} " +
                            "span=(${span.start},${span.end})"
                    }
                }
            }
        }
        assertTrue("invalid spans:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun fuzzProducedOnlyValidSpans() {
        val rnd = java.util.Random(20260922L)
        val pool = "ab \n/*`\"'#$(){}[];:=<>-+\\\t\u00e9\u4e2d*/".toCharArray()
        val langs = listOf(
            null, "kotlin", "java", "python", "javascript", "c", "cpp",
            "json", "xml", "html", "sql", "bash", "css", "markdown", "ruby",
            "php", "go", "rust", "swift",
        )
        val violations = mutableListOf<String>()
        repeat(60000) {
            val n = rnd.nextInt(60)
            val sb = StringBuilder(n)
            repeat(n) { sb.append(pool[rnd.nextInt(pool.size)]) }
            val code = sb.toString()
            for (language in langs) {
                val annotated = runCatching {
                    buildSafeHighlightedAnnotatedString(code, language, builder())
                }.getOrElse { e ->
                    if (violations.size < 20) {
                        violations += "THROW code=[$code] lang=$language: ${e.message}"
                    }
                    continue
                }
                annotated.spanStyles.forEach { span ->
                    if (span.start < 0 || span.start >= span.end || span.end > annotated.length) {
                        if (violations.size < 20) {
                            violations += "code=[$code] lang=$language len=${annotated.length} " +
                                "span=(${span.start},${span.end})"
                        }
                    }
                }
            }
        }
        assertTrue("invalid spans:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    private fun assertSpansValid(annotated: AnnotatedString) {
        annotated.spanStyles.forEach { span ->
            assertTrue("span=(${span.start},${span.end}) len=${annotated.length}",
                span.start >= 0 && span.start < span.end && span.end <= annotated.length)
        }
    }
}
