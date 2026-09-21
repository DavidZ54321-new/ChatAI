package com.zcw.chatai.ui.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewLanguageTest {

    @Test
    fun detectsAllPreviewLanguages() {
        assertEquals(PreviewLanguage.MERMAID, previewLanguageOf("mermaid"))
        assertEquals(PreviewLanguage.SVG, previewLanguageOf("svg"))
        assertEquals(PreviewLanguage.HTML, previewLanguageOf("html"))
    }

    @Test
    fun plantUmlAliasesMapToPlantUml() {
        assertEquals(PreviewLanguage.PLANTUML, previewLanguageOf("plantuml"))
        assertEquals(PreviewLanguage.PLANTUML, previewLanguageOf("puml"))
        assertEquals(PreviewLanguage.PLANTUML, previewLanguageOf("uml"))
    }

    @Test
    fun languageMatchingIsCaseInsensitive() {
        assertEquals(PreviewLanguage.MERMAID, previewLanguageOf("Mermaid"))
        assertEquals(PreviewLanguage.SVG, previewLanguageOf("SVG"))
        assertEquals(PreviewLanguage.PLANTUML, previewLanguageOf("PlantUML"))
        assertEquals(PreviewLanguage.PLANTUML, previewLanguageOf("PUML"))
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

    @Test
    fun htmlIsTheOnlyLanguageWithoutPngExport() {
        assertTrue(PreviewLanguage.MERMAID.supportsPngExport)
        assertTrue(PreviewLanguage.SVG.supportsPngExport)
        assertTrue(PreviewLanguage.PLANTUML.supportsPngExport)
        assertFalse(PreviewLanguage.HTML.supportsPngExport)
    }
}
