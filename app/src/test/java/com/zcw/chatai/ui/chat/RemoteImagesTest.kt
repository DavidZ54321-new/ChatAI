package com.zcw.chatai.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteImagesTest {

    @Test
    fun memoryCacheBudgetIsEighthOfHeapWithinBounds() {
        // 512MB 堆 → 64MB（上限）；256MB → 32MB；64MB → 8MB（下限）。
        assertEquals(64 * 1024 * 1024, RemoteImages.memoryCacheBytes(512L * 1024 * 1024))
        assertEquals(32 * 1024 * 1024, RemoteImages.memoryCacheBytes(256L * 1024 * 1024))
        assertEquals(8 * 1024 * 1024, RemoteImages.memoryCacheBytes(64L * 1024 * 1024))
    }

    @Test
    fun memoryCacheBudgetNeverDropsBelowFloor() {
        assertEquals(8 * 1024 * 1024, RemoteImages.memoryCacheBytes(0L))
        assertEquals(8 * 1024 * 1024, RemoteImages.memoryCacheBytes(-1L))
    }
}
