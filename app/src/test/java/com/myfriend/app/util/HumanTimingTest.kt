package com.myfriend.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanTimingTest {

    @Test
    fun immediatePaceNeverWaits() {
        assertEquals(0L, HumanTiming.readDelayMillis(120, 2, paceFactor = 0f))
    }

    @Test
    fun shortMessageWaitsAboutHalfASecond() {
        val delay = HumanTiming.readDelayMillis(6, 0, paceFactor = 1f, jitter = 0f)
        assertEquals(750L, delay) // 450 + 6*50 = 750
    }

    @Test
    fun longerMessageWaitsLonger() {
        val short = HumanTiming.readDelayMillis(10, 0, 1f, jitter = 0f)
        val long = HumanTiming.readDelayMillis(40, 0, 1f, jitter = 0f)
        assertTrue(long > short)
    }

    @Test
    fun delayIsClampedToUpperBound() {
        val delay = HumanTiming.readDelayMillis(5000, 10, 1f, jitter = 0f)
        assertEquals(HumanTiming.MAX_MS, delay)
    }

    @Test
    fun imagesCostExtraTime() {
        val textOnly = HumanTiming.readDelayMillis(10, 0, 1f, jitter = 0f)
        val withImage = HumanTiming.readDelayMillis(10, 1, 1f, jitter = 0f)
        assertTrue(withImage > textOnly)
    }

    @Test
    fun slowPaceIsSlowerThanNatural() {
        val natural = HumanTiming.readDelayMillis(20, 0, 1f, jitter = 0f)
        val slow = HumanTiming.readDelayMillis(20, 0, 1.6f, jitter = 0f)
        assertTrue(slow > natural)
    }

    @Test
    fun jitterStaysWithinBounds() {
        repeat(50) {
            val delay = HumanTiming.readDelayMillis(20, 0, 1f, jitter = 0.15f)
            val base = 450L + 20 * 50L
            assertTrue(delay in (base * 0.84).toLong()..(base * 1.16).toLong())
        }
    }

    @Test
    fun regenerateDelayIsShortButNotZero() {
        assertTrue(HumanTiming.REGENERATE_MS in 200L..800L)
    }
}
