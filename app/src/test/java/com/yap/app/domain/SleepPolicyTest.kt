package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 睡眠 / 免打扰 / 唤醒阈值（规格 §六）。
 *
 * 这几条判定直接决定「他半夜会不会回你」「被吵醒会不会有起床气」，
 * 所以每个边界都单独钉：不睡觉、dnd 开/关、阈值到达/差一条。
 */
class SleepPolicyTest {

    private fun evaluate(
        sleepEnabled: Boolean = true,
        sleep: Int? = 23 * 60 + 30,
        wake: Int? = 8 * 60,
        minute: Int = 2 * 60,
        dnd: Boolean = false,
        recent: Int = 0,
        threshold: Int = 6
    ) = SleepPolicy.evaluate(sleepEnabled, sleep, wake, minute, dnd, recent, threshold)

    @Test
    fun `不睡觉的人任何时间都会回`() {
        val decision = evaluate(sleepEnabled = false, minute = 3 * 60, recent = 0)
        assertFalse(decision.asleep)
        assertTrue(decision.shouldReply)
    }

    @Test
    fun `在自己的作息窗口里是睡着的`() {
        assertTrue(evaluate(minute = 2 * 60).asleep)
        assertTrue(evaluate(minute = 23 * 60 + 45).asleep)
        assertFalse("早上 8 点应该醒了", evaluate(minute = 8 * 60).asleep)
        assertFalse(evaluate(minute = 15 * 60).asleep)
    }

    @Test
    fun `没填作息时按默认 23-30 到 08-00`() {
        assertEquals(
            23 * 60 + 30 to 8 * 60,
            SleepPolicy.window(null, null)
        )
        assertTrue(evaluate(sleep = null, wake = null, minute = 1 * 60).asleep)
        assertFalse(evaluate(sleep = null, wake = null, minute = 12 * 60).asleep)
        assertEquals("08:00", SleepPolicy.wakeClockText(true, null, null))
    }

    @Test
    fun `免打扰开着时 消息再多也不会醒`() {
        val decision = evaluate(dnd = true, recent = 999, threshold = 1)
        assertTrue(decision.asleep)
        assertTrue(decision.dnd)
        assertFalse(decision.woken)
        assertFalse("dnd 开着一律不回", decision.shouldReply)
    }

    @Test
    fun `没到阈值不叫醒 只标已读`() {
        val decision = evaluate(dnd = false, recent = 5, threshold = 6)
        assertTrue(decision.asleep)
        assertFalse(decision.woken)
        assertFalse(decision.shouldReply)
    }

    @Test
    fun `到了阈值就被叫醒 这一轮正常回复`() {
        val decision = evaluate(dnd = false, recent = 6, threshold = 6)
        assertTrue(decision.asleep)
        assertTrue(decision.woken)
        assertTrue(decision.shouldReply)
    }

    @Test
    fun `阈值会被夹在 1 到 20 之间`() {
        assertEquals(1, SleepPolicy.normalizeThreshold(0))
        assertEquals(20, SleepPolicy.normalizeThreshold(99))
        assertEquals(6, SleepPolicy.normalizeThreshold(6))
        assertTrue(evaluate(recent = 1, threshold = 0).woken)
    }

    @Test
    fun `跨午夜的窗口算得对`() {
        assertTrue(SleepPolicy.inWindow(minuteOfDay = 23 * 60 + 50, sleepMinutes = 22 * 60, wakeMinutes = 7 * 60))
        assertTrue(SleepPolicy.inWindow(minuteOfDay = 6 * 60, sleepMinutes = 22 * 60, wakeMinutes = 7 * 60))
        assertFalse(SleepPolicy.inWindow(minuteOfDay = 12 * 60, sleepMinutes = 22 * 60, wakeMinutes = 7 * 60))
    }

    @Test
    fun `睡和醒同一个点时按默认窗口处理 不是全天睡`() {
        val (sleep, wake) = SleepPolicy.window(12 * 60, 12 * 60)
        assertEquals(SleepPolicy.DEFAULT_SLEEP_MINUTES, sleep)
        assertEquals(SleepPolicy.DEFAULT_WAKE_MINUTES, wake)
    }

    @Test
    fun `醒来时间和距离醒来的分钟数可以给界面用`() {
        assertEquals("08:00", SleepPolicy.wakeClockText(true, 23 * 60 + 30, 8 * 60))
        assertNull("不睡觉的人没有「几点醒」", SleepPolicy.wakeClockText(false, 23 * 60 + 30, 8 * 60))
        assertEquals(60, SleepPolicy.minutesUntilWake(7 * 60, 23 * 60 + 30, 8 * 60))
        assertEquals(0, SleepPolicy.minutesUntilWake(12 * 60, 23 * 60 + 30, 8 * 60))
    }
}
