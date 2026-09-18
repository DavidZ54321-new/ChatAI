package com.zcw.chatai.data.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlToTextTest {

    @Test
    fun dropsScriptStyleAndKeepsBlockText() {
        val html = "<html><head><style>p{}</style></head><body><script>evil()</script><h1>标题</h1><p>第一段</p><p>第二段</p></body></html>"
        val text = HtmlToText.toText(html)
        assertTrue(text, text.contains("标题"))
        assertTrue(text, text.contains("第一段"))
        assertTrue(text, text.contains("第二段"))
        assertFalse(text, text.contains("evil"))
        assertFalse(text, text.contains("p{}"))
    }

    @Test
    fun keepsAnchorTextAndListItems() {
        val text = HtmlToText.toText("<ul><li>甲</li><li><a href=\"https://a\">乙</a></li></ul>")
        assertTrue(text, text.contains("甲"))
        assertTrue(text, text.contains("乙"))
    }

    @Test
    fun decodesEntities() {
        val text = HtmlToText.toText("<p>A &amp; B &#20320;</p>")
        assertTrue(text, text.contains("A & B"))
        assertTrue(text, text.contains("你"))
    }
}
