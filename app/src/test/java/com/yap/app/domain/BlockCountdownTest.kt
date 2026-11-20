package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.15 拉黑倒计时：剩余时间格式与到期消失。
 */
class BlockCountdownTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 3_600_000L

    @Test
    fun `剩余时间拆成小时与分钟`() {
        val parts = BlockCountdown.parts(now + 3 * hour + 12 * minute, now)!!
        assertEquals(3, parts.hours)
        assertEquals(12, parts.minutes)
        assertTrue(parts.active)
    }

    @Test
    fun `分钟向上取整 不显示零分`() {
        assertEquals(1, BlockCountdown.parts(now + 1_000L, now)!!.minutes)
        assertEquals(1, BlockCountdown.parts(now + minute, now)!!.minutes)
        assertEquals(2, BlockCountdown.parts(now + minute + 1L, now)!!.minutes)
        assertEquals(59, BlockCountdown.parts(now + 59 * minute, now)!!.minutes)
    }

    @Test
    fun `中文时长格式`() {
        assertEquals("3 小时 12 分", BlockCountdown.duration(now + 3 * hour + 12 * minute, now))
        assertEquals("3 小时", BlockCountdown.duration(now + 3 * hour, now))
        assertEquals("12 分", BlockCountdown.duration(now + 12 * minute, now))
        assertEquals("不到 1 分钟", BlockCountdown.duration(now + 500L, now))
    }

    @Test
    fun `到期即消失`() {
        assertNull(BlockCountdown.duration(now, now))
        assertNull(BlockCountdown.duration(now - 1L, now))
        assertNull(BlockCountdown.remaining(now, now))
        assertFalse(BlockCountdown.isActive(now, now))
        assertFalse(BlockCountdown.shouldTick(now - hour, now))
        assertTrue(BlockCountdown.shouldTick(now + hour, now))
    }

    @Test
    fun `空值与脏值当作没拉黑`() {
        assertNull(BlockCountdown.remaining(null, now))
        assertNull(BlockCountdown.remaining(0L, now))
        assertNull(BlockCountdown.remaining(-5L, now))
        assertNull(BlockCountdown.duration(null, now))
        assertFalse(BlockCountdown.isActive(null, now))
    }

    @Test
    fun `长时间拉黑也正确显示`() {
        val parts = BlockCountdown.parts(now + 24 * hour, now)!!
        assertEquals(24, parts.hours)
        assertEquals(0, parts.minutes)
        assertEquals("24 小时", BlockCountdown.duration(now + 24 * hour, now))
    }

    @Test
    fun `时钟倒退按仍有剩余处理 不崩`() {
        val parts = BlockCountdown.parts(now + hour, now - hour)!!
        assertEquals(2, parts.hours)
    }
}
