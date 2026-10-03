package com.zcw.chatai.data.image

import com.zcw.chatai.data.image.openai.OpenAiImageOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiImageOptionsTest {

    @Test
    fun blankFieldsStayOmitted() {
        val clean = OpenAiImageOptions().sanitized()
        assertEquals("", clean.quality)
        assertEquals("", clean.size)
        assertEquals("", clean.background)
        assertEquals("", clean.outputFormat)
        assertNull(clean.outputCompression)
        assertEquals(1, clean.count)
    }

    @Test
    fun dropsUnknownValuesAndClampsCount() {
        val clean = OpenAiImageOptions(
            quality = "hd",
            size = "256x256",
            background = "checker",
            outputFormat = "gif",
            outputCompression = 40,
            count = 99,
        ).sanitized()
        assertEquals("", clean.quality)
        assertEquals("", clean.size)
        assertEquals("", clean.background)
        assertEquals("", clean.outputFormat)
        assertNull(clean.outputCompression)
        assertEquals(OpenAiImageOptions.MAX_COUNT, clean.count)
    }

    @Test
    fun transparentJpegBecomesPngAndKeepsWebpCompression() {
        val png = OpenAiImageOptions(background = "transparent", outputFormat = "jpeg").sanitized()
        assertEquals("transparent", png.background)
        assertEquals("png", png.outputFormat)

        val webp = OpenAiImageOptions(
            outputFormat = "webp",
            outputCompression = 150,
        ).sanitized()
        assertEquals("webp", webp.outputFormat)
        assertEquals(100, webp.outputCompression)
    }

    @Test
    fun sizeRulesMatchTheGuide() {
        assertNull(OpenAiImageOptions.sizeProblem(""))
        assertNull(OpenAiImageOptions.sizeProblem("auto"))
        assertNull(OpenAiImageOptions.sizeProblem("1536x864"))
        assertNull(OpenAiImageOptions.sizeProblem("3840x2160"))
        assertTrue(OpenAiImageOptions.sizeProblem("1536x860")!!.contains("16"))
        assertTrue(OpenAiImageOptions.sizeProblem("512x512")!!.contains("像素"))
        assertTrue(OpenAiImageOptions.sizeProblem("3840x1024")!!.contains("3:1"))
        assertTrue(OpenAiImageOptions.experimentalSize("3840x2160"))
        assertTrue(!OpenAiImageOptions.experimentalSize("1024x1024"))
    }

    @Test
    fun suggestionKeepsWireIdAndDisplayName() {
        val named = ImageModelOption("wordart-texture", "WordArt锦书-文字变形", true)
        assertEquals("wordart-texture · WordArt锦书-文字变形", named.suggestion())
        assertEquals("wordart-texture", imageModelIdFromSuggestion(named.suggestion()))
        val cased = ImageModelOption("qwen-image-3.0-pro", "Qwen-Image-3.0-Pro", true)
        assertEquals("qwen-image-3.0-pro", cased.suggestion())
        val same = ImageModelOption("gpt-image-2.5-flare", "gpt-image-2.5-flare", true)
        assertEquals("gpt-image-2.5-flare", same.suggestion())
        assertEquals("gpt-image-2", imageModelIdFromSuggestion("gpt-image-2"))
    }
}
