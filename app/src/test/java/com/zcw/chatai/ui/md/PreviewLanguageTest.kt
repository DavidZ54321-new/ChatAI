package com.zcw.chatai.ui.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewLanguageTest {

    @Test
    fun detectsAllThreePreviewLanguages() {
        assertEquals(PreviewLanguage.MERMAID, previewLanguageOf("mermaid"))
        assertEquals(PreviewLanguage.SVG, previewLanguageOf("svg"))
        assertEquals(PreviewLanguage.HTML, previewLanguageOf("html"))
    }

    @Test
    fun languageMatchingIsCaseInsensitive() {
        assertEquals(PreviewLanguage.MERMAID, previewLanguageOf("Mermaid"))
        assertEquals(PreviewLanguage.SVG, previewLanguageOf("SVG"))
    }

    @Test
    fun extraInfoStringTokensAreIgnored() {
        assertEquals(PreviewLanguage.HTML, previewLanguageOf("html run"))
        assertEquals(PreviewLanguage.MERMAID, previewLanguageOf("  mermaid  extra"))
    }

    @Test
    fun unknownOrMissingLanguageHasNoPreview() {
        assertNull(previewLanguageOf("kotlin"))
        assertNull(previewLanguageOf(null))
        assertNull(previewLanguageOf(""))
        assertNull(previewLanguageOf("   "))
    }
}
