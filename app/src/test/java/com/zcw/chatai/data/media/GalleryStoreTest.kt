package com.zcw.chatai.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryStoreTest {

    @Test
    fun mimeFollowsExtension() {
        assertEquals("image/png", GalleryStore.mimeFor("a.png"))
        assertEquals("image/gif", GalleryStore.mimeFor("a.gif"))
        assertEquals("image/jpeg", GalleryStore.mimeFor("a.jpg"))
        assertEquals("image/jpeg", GalleryStore.mimeFor("a.jpeg"))
        assertEquals("image/webp", GalleryStore.mimeFor("a.webp"))
    }

    @Test
    fun mimeIsCaseInsensitive() {
        assertEquals("image/png", GalleryStore.mimeFor("shot.PNG"))
        assertEquals("image/gif", GalleryStore.mimeFor("anim.GIF"))
    }

    @Test
    fun unknownExtensionFallsBackToJpeg() {
        assertEquals("image/jpeg", GalleryStore.mimeFor("noext"))
        assertEquals("image/jpeg", GalleryStore.mimeFor("weird.xyz"))
    }

    @Test
    fun exportFileNameNormalizesExtension() {
        assertEquals("chat_ai_123.png", GalleryStore.exportFileName(123L, "png"))
        assertEquals("chat_ai_123.png", GalleryStore.exportFileName(123L, ".png"))
        assertEquals("chat_ai_123.png", GalleryStore.exportFileName(123L, "PNG"))
    }
}
