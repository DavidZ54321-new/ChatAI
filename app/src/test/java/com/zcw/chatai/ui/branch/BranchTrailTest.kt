package com.zcw.chatai.ui.branch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BranchTrailTest {

    @Test
    fun startHoldsOnlyTheRoot() {
        assertEquals(listOf("a"), BranchTrail.start("a"))
    }

    @Test
    fun drillAppendsOneLevel() {
        assertEquals(listOf("a", "b"), BranchTrail.drill(listOf("a"), "b"))
        assertEquals(listOf("a", "b", "c"), BranchTrail.drill(listOf("a", "b"), "c"))
    }

    /** 数据被手工改出环（a → b → a）时不能无限加深；空 id 也不进轨迹。 */
    @Test
    fun drillIgnoresIdsAlreadyOnTheTrailAndBlankIds() {
        assertEquals(listOf("a", "b"), BranchTrail.drill(listOf("a", "b"), "a"))
        assertEquals(listOf("a"), BranchTrail.drill(listOf("a"), ""))
        assertEquals(listOf("a"), BranchTrail.drill(listOf("a"), "   "))
    }

    @Test
    fun drillBlocksAReversedCycle() {
        var trail = BranchTrail.start("a")
        trail = BranchTrail.drill(trail, "b")
        trail = BranchTrail.drill(trail, "a")
        assertEquals(listOf("a", "b"), trail)
    }

    /** 返回一层；已经在起点返回 null（调用方据此关掉页面）。 */
    @Test
    fun backPopsOneLevelUntilTheRoot() {
        assertEquals(listOf("a"), BranchTrail.back(listOf("a", "b")))
        assertEquals(listOf("a", "b"), BranchTrail.back(listOf("a", "b", "c")))
        assertNull(BranchTrail.back(listOf("a")))
        assertNull(BranchTrail.back(emptyList()))
    }

    @Test
    fun pickTruncatesToThePickedSegment() {
        assertEquals(listOf("a"), BranchTrail.pick(listOf("a", "b", "c"), 0))
        assertEquals(listOf("a", "b"), BranchTrail.pick(listOf("a", "b", "c"), 1))
    }

    /** 点当前那一段（或越界/负数）是空操作，不该把轨迹改坏。 */
    @Test
    fun pickOnCurrentSegmentOrOutOfRangeIsANoOp() {
        val trail = listOf("a", "b", "c")
        assertEquals(trail, BranchTrail.pick(trail, 2))
        assertEquals(trail, BranchTrail.pick(trail, 9))
        assertEquals(trail, BranchTrail.pick(trail, -1))
    }

    @Test
    fun currentIsTheLastSegment() {
        assertEquals("b", BranchTrail.current(listOf("a", "b")))
        assertEquals("a", BranchTrail.current(listOf("a")))
        assertNull(BranchTrail.current(emptyList()))
    }
}
