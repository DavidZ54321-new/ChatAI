package com.zcw.chatai.data.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsxFormatsTest {

    @Test
    fun builtinDateIds() {
        assertTrue(XlsxFormats.isDateFormat(14, null))
        assertTrue(XlsxFormats.isDateFormat(22, null))
        assertTrue(XlsxFormats.isDateFormat(57, null))
        assertFalse(XlsxFormats.isDateFormat(0, null))
        assertFalse(XlsxFormats.isDateFormat(49, null))
        assertFalse(XlsxFormats.isDateFormat(9, "0%"))
    }

    @Test
    fun customCodesWithDateTokens() {
        assertTrue(XlsxFormats.isDateFormat(164, "yyyy-mm-dd"))
        assertTrue(XlsxFormats.isDateFormat(164, "hh:mm:ss"))
        assertTrue(XlsxFormats.isDateFormat(164, "[$-409]yyyy/m/d"))
        assertFalse(XlsxFormats.isDateFormat(164, "General"))
        assertFalse(XlsxFormats.isDateFormat(164, "#,##0.00"))
        assertFalse(XlsxFormats.isDateFormat(164, "\"Total: \"0"))
    }

    @Test
    fun convertsCommonPatterns() {
        assertEquals("yyyy-MM-dd", XlsxFormats.toJavaPattern("yyyy-mm-dd"))
        assertEquals("HH:mm:ss", XlsxFormats.toJavaPattern("hh:mm:ss"))
        assertEquals("yyyy-MM-dd HH:mm", XlsxFormats.toJavaPattern("yyyy-mm-dd hh:mm"))
        assertEquals("M/d/yy", XlsxFormats.toJavaPattern("m/d/yy"))
        assertEquals("h:mm a", XlsxFormats.toJavaPattern("h:mm AM/PM"))
    }

    @Test
    fun formatsSerialDates() {
        // 2026-09-20：序列数 45924（1900 制式）。
        val text = XlsxFormats.formatDate(46285.0, "yyyy-mm-dd", false)
        assertTrue(text?.contains("2026-09-20") == true)
        // 带时间小数。
        val timed = XlsxFormats.formatDate(46285.5, "yyyy-mm-dd hh:mm", false)
        assertTrue(timed?.contains("2026-09-20") == true)
        assertTrue(timed?.contains("12:00") == true)
        assertNull(XlsxFormats.formatDate(-1.0, "yyyy-mm-dd", false))
    }

    @Test
    fun serialBase1904() {
        // 同一天：1904 制式序列数 = 1900 制式 - 1462。
        val a = XlsxFormats.formatDate(46285.0, "yyyy-mm-dd", false)
        val b = XlsxFormats.formatDate(46285.0 - 1462, "yyyy-mm-dd", true)
        assertEquals(a, b)
    }
}
