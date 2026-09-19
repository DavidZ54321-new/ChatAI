package com.zcw.chatai.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFilterTest {

    private val models = listOf(
        "deepseek-v4.1-flash",
        "deepseek-v4-flash",
        "deepseek-v4-flash-vision-exp",
        "qwen3.8-max",
        "qwen3.8-27b",
        "GLM-5.3-Flash",
    )

    @Test
    fun blankQueryReturnsAll() {
        assertEquals(models, ModelFilter.filter(models, ""))
        assertEquals(models, ModelFilter.filter(models, "   "))
    }

    @Test
    fun matchesSubstringIgnoringCaseAndTrimmingQuery() {
        assertEquals(
            listOf("deepseek-v4-flash", "deepseek-v4-flash-vision-exp"),
            ModelFilter.filter(models, " v4-FLASH "),
        )
        assertEquals(listOf("deepseek-v4.1-flash"), ModelFilter.filter(models, "v4.1"))
    }

    @Test
    fun noMatchYieldsEmpty() {
        assertTrue(ModelFilter.filter(models, "does-not-exist").isEmpty())
    }
}
