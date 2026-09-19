package com.zcw.chatai.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolModelsTest {

    @Test
    fun parseSplitsOnCommasAndWhitespaceThenDedupes() {
        assertEquals(listOf("a", "b"), ToolModels.parse(" a, b ，a  b "))
        assertEquals(listOf("m1", "m2"), ToolModels.parse("m1\nm2"))
    }

    @Test
    fun blankInputParsesToEmpty() {
        assertTrue(ToolModels.parse("").isEmpty())
        assertTrue(ToolModels.parse("   ").isEmpty())
        assertTrue(ToolModels.parse(null).isEmpty())
    }

    @Test
    fun resolveFallsBackToPresetDefaultWhenBlank() {
        assertEquals(listOf("x", "y"), ToolModels.resolve("", listOf("x", "y")))
        assertEquals(listOf("x", "y"), ToolModels.resolve(null, listOf("x", "y")))
    }

    @Test
    fun resolveCapsAtMaxAndPrefersOverride() {
        assertEquals(listOf("a", "b", "c"), ToolModels.resolve("a,b,c,d", listOf("x")))
        assertEquals(listOf("x", "y", "z"), ToolModels.resolve("", listOf("x", "y", "z", "w")))
    }

    @Test
    fun validateAllowsBlankUpToMaxAndRejectsMore() {
        assertNull(ToolModels.validate(""))
        assertNull(ToolModels.validate("a,b,c"))
        assertNotNull(ToolModels.validate("a,b,c,d"))
    }

    @Test
    fun qwenPresetShipsTheDefaultImageChain() {
        assertEquals(
            listOf("qwen3.8-27b", "qwen3.8-max"),
            ProviderCatalog.byId(ProviderCatalog.QWEN)?.toolModels,
        )
    }
}
