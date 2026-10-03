package com.zcw.chatai.ui.persona

import com.zcw.chatai.data.prefs.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffortSliderTest {

    @Test
    fun stopsRunFromFollowDefaultToMax() {
        assertEquals(
            listOf(
                ReasoningEffort.FOLLOW_DEFAULT,
                ReasoningEffort.OFF,
                ReasoningEffort.LOW,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            EffortSliderMath.stops,
        )
    }

    @Test
    fun fractionRoundTripsEveryStop() {
        EffortSliderMath.stops.indices.forEach { index ->
            val fraction = EffortSliderMath.fractionOf(index)
            assertEquals(index, EffortSliderMath.indexForFraction(fraction))
            assertEquals(EffortSliderMath.stops[index], EffortSliderMath.effortAt(index))
        }
    }

    @Test
    fun fractionSnapsAtMidpoints() {
        // 五档，分界在 0.125 / 0.375 / 0.625 / 0.875。
        assertEquals(0, EffortSliderMath.indexForFraction(0f))
        assertEquals(0, EffortSliderMath.indexForFraction(0.12f))
        assertEquals(1, EffortSliderMath.indexForFraction(0.13f))
        assertEquals(2, EffortSliderMath.indexForFraction(0.5f))
        assertEquals(3, EffortSliderMath.indexForFraction(0.63f))
        assertEquals(4, EffortSliderMath.indexForFraction(0.88f))
        assertEquals(4, EffortSliderMath.indexForFraction(1f))
        assertEquals(0, EffortSliderMath.indexForFraction(-1f))
        assertEquals(4, EffortSliderMath.indexForFraction(2f))
    }

    @Test
    fun thumbStaysInsideTheTrack() {
        val width = 200f
        val radius = 16f
        val inset = 2f
        assertEquals(18f, EffortSliderMath.thumbCenterX(0f, width, radius, inset), 0.001f)
        assertEquals(182f, EffortSliderMath.thumbCenterX(1f, width, radius, inset), 0.001f)
        assertEquals(100f, EffortSliderMath.thumbCenterX(0.5f, width, radius, inset), 0.001f)
        assertEquals(0f, EffortSliderMath.fractionAtX(18f, width, radius, inset), 0.001f)
        assertEquals(1f, EffortSliderMath.fractionAtX(182f, width, radius, inset), 0.001f)
        assertEquals(0.5f, EffortSliderMath.fractionAtX(100f, width, radius, inset), 0.001f)
        assertEquals(0f, EffortSliderMath.fractionAtX(-20f, width, radius, inset), 0.001f)
        assertEquals(1f, EffortSliderMath.fractionAtX(400f, width, radius, inset), 0.001f)
    }

    @Test
    fun sparksStayInsideTheUnitSquareAndDoNotChange() {
        val first = sparkSeeds()
        val second = sparkSeeds()
        assertEquals(26, first.size)
        assertEquals(first, second)
        assertTrue(first.any { it.star })
        assertTrue(first.any { !it.star })
        first.forEach { seed ->
            assertTrue(seed.along in 0f..1f)
            assertTrue(seed.across in 0f..1f)
            assertTrue(seed.radiusScale > 0f)
            assertTrue(seed.speedScale in 0.65f..1.4f)
        }
    }

    @Test
    fun flowSpeedsUpTowardTheRight() {
        val samples = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { EffortFlow.lengthsPerSecond(it) }
        samples.zipWithNext { slower, faster -> assertTrue("$slower -> $faster", faster > slower) }
        assertTrue(samples.last() / samples.first() >= 20f)
    }

    @Test
    fun headWrapsToTheLeftEdge() {
        assertEquals(0.2f, EffortFlow.headAlong(0.9f, 0.3f), 0.0001f)
        assertEquals(0.5f, EffortFlow.headAlong(0.2f, 2.3f), 0.0001f)
        assertEquals(0.9f, EffortFlow.headAlong(0.2f, -0.3f), 0.0001f)
    }

    @Test
    fun tailGrowsFromADotToAMeteor() {
        val slow = EffortFlow.tailFraction(EffortFlow.lengthsPerSecond(0f))
        assertTrue(slow < 0.03f)
        val meteor = EffortFlow.seedTailFraction(fraction = 1f, speedScale = 1.4f, star = true)
        assertEquals(EffortFlow.TAIL_MAX, meteor, 0.0001f)
        assertTrue(meteor <= 0.55f)
        val dust = EffortFlow.seedTailFraction(fraction = 0f, speedScale = 0.65f, star = false)
        assertTrue(dust < slow * 2f)
    }

    @Test
    fun tickLabelsStayShort() {
        assertEquals("跟随", ReasoningEffort.FOLLOW_DEFAULT.sliderTick())
        assertEquals("关", ReasoningEffort.OFF.sliderTick())
        assertEquals("低", ReasoningEffort.LOW.sliderTick())
        assertEquals("高", ReasoningEffort.HIGH.sliderTick())
        assertEquals("最高", ReasoningEffort.MAX.sliderTick())
    }
}
