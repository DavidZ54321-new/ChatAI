package com.zcw.chatai.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextMenuPlacementTest {

    @Test
    fun placesBelowThePressWhenThereIsRoom() {
        val place = placeContextMenu(
            anchorX = 500,
            anchorY = 400,
            menuWidth = 200,
            menuHeight = 300,
            windowWidth = 1080,
            windowHeight = 2400,
            margin = 24,
            gap = 12,
        )
        assertEquals(400, place.x)
        assertEquals(412, place.y)
    }

    @Test
    fun flipsAboveWhenThePressIsTooLow() {
        val place = placeContextMenu(
            anchorX = 540,
            anchorY = 2200,
            menuWidth = 220,
            menuHeight = 400,
            windowWidth = 1080,
            windowHeight = 2400,
            margin = 24,
            gap = 12,
        )
        assertEquals(430, place.x)
        assertEquals(1788, place.y)
    }

    @Test
    fun clampsToTheLeftAndRightMargins() {
        val left = placeContextMenu(
            anchorX = 10,
            anchorY = 400,
            menuWidth = 400,
            menuHeight = 200,
            windowWidth = 1080,
            windowHeight = 2400,
            margin = 24,
            gap = 12,
        )
        assertEquals(24, left.x)

        val right = placeContextMenu(
            anchorX = 1070,
            anchorY = 400,
            menuWidth = 400,
            menuHeight = 200,
            windowWidth = 1080,
            windowHeight = 2400,
            margin = 24,
            gap = 12,
        )
        assertEquals(656, right.x)
    }

    @Test
    fun clampsInsideWhenTheMenuIsTallerThanTheSafeArea() {
        val place = placeContextMenu(
            anchorX = 200,
            anchorY = 400,
            menuWidth = 200,
            menuHeight = 900,
            windowWidth = 400,
            windowHeight = 800,
            margin = 20,
            gap = 10,
        )
        assertEquals(20, place.y)
    }

    @Test
    fun treatsTheComposerTopAsTheBottomEdge() {
        // 窗口底还有空间，但输入栏顶在 1800，菜单若向下会盖住输入栏。
        val place = placeContextMenu(
            anchorX = 500,
            anchorY = 1700,
            menuWidth = 200,
            menuHeight = 200,
            windowWidth = 1080,
            windowHeight = 2400,
            margin = 24,
            gap = 12,
            bottomLimit = 1800,
        )
        assertEquals(1488, place.y)
    }

    @Test
    fun keepsTheMenuBelowTheStatusBarWhenFlippingUp() {
        val place = placeContextMenu(
            anchorX = 400,
            anchorY = 420,
            menuWidth = 200,
            menuHeight = 200,
            windowWidth = 1080,
            windowHeight = 2400,
            margin = 24,
            gap = 12,
            bottomLimit = 500,
            topLimit = 250,
        )
        assertEquals(250, place.y)
    }
}
