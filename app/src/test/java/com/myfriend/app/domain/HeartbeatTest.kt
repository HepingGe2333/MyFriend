package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冲动曲线的纯领域测试。
 *
 * 这里断言的不是「大概像个人」，而是曲线的形状本身：
 * 刚互动完不高、先降到一个清晰的谷底、之后单调爬升、三个小时之后停在上限；
 * 外加情绪放大、深夜减半、坏输入（负时间 / NaN / 无穷大）不产生 NaN 或负概率，
 * 以及掷骰子与重置时钟的边界。
 */
class HeartbeatTest {

    private fun p(
        minutes: Double,
        mood: MoodState = MoodState(),
        hour: Int = 14
    ): Double = Heartbeat.probability(minutes, mood, now = 0L, hourOfDay = hour)

    private fun mood(label: String, intensity: Int) =
        MoodState(label = label, intensity = intensity, updatedAt = 0L)

    /** 所有断言都留一点浮点余量：这些是数学形状，不是像素。 */
    private val eps = 1e-9

    // ------------------------------------------------------------- 曲线形状

    @Test
    fun `刚互动完不是最高点`() {
        // 他刚说完话，人是收回来的：0.35 只是起点，远不是上限。
        assertEquals(Heartbeat.INITIAL, p(0.0), eps)
        assertTrue(p(0.0) < p(180.0))
    }

    @Test
    fun `十分钟前后落到谷底`() {
        val floor = p(Heartbeat.COOL_DOWN_MINUTES)
        assertTrue("谷底应该在 0.10 上方一点，实际 $floor", floor > Heartbeat.FLOOR)
        assertTrue("谷底应该明显低于起点，实际 $floor", floor < 0.13)
    }

    @Test
    fun `谷底低于起点与一小时后`() {
        val bottom = p(10.0)
        assertTrue(p(0.0) > bottom)
        assertTrue(bottom < p(60.0))
    }

    @Test
    fun `谷底在所有取样点里最小`() {
        val bottom = p(10.0)
        listOf(0.0, 5.0, 15.0, 20.0, 30.0, 60.0, 120.0, 180.0).forEach { t ->
            assertTrue("t=$t 不该低于谷底", p(t) >= bottom - eps)
        }
    }

    @Test
    fun `十分钟到三小时单调上升`() {
        var previous = p(Heartbeat.COOL_DOWN_MINUTES)
        var t = Heartbeat.COOL_DOWN_MINUTES
        while (t <= Heartbeat.RAMP_MINUTES) {
            val current = p(t)
            assertTrue("t=$t 应当不低于上一拍（$previous vs $current）", current >= previous - eps)
            previous = current
            t += 5.0
        }
    }

    @Test
    fun `三小时之后维持在上限`() {
        assertEquals(Heartbeat.CEILING, p(180.0), 1e-6)
        assertEquals(p(180.0), p(240.0), eps)
        assertEquals(p(180.0), p(600.0), eps)
    }

    @Test
    fun `六十分钟已经明显想他`() {
        // 三小时是上限，一小时应该走完一大半：0.10 到 0.85 之间的 0.32 左右。
        val at60 = p(60.0)
        assertTrue("一小时应该高于 0.30，实际 $at60", at60 > 0.30)
        assertTrue(at60 < Heartbeat.CEILING)
    }

    // ------------------------------------------------------------- 情绪放大

    @Test
    fun `强烈的正面情绪放大冲动`() {
        assertTrue(p(60.0, mood("雀跃", 4)) > p(60.0))
    }

    @Test
    fun `强烈的负面情绪也放大冲动`() {
        // 难过的时候同样想找人说话：这不是「负面才被照顾」，是「心里不静」。
        assertTrue(p(60.0, mood("低落", 4)) > p(60.0))
    }

    @Test
    fun `强度不够时不放大`() {
        assertEquals(p(60.0), p(60.0, mood("开心", 2)), eps)
    }

    @Test
    fun `平静时不放大`() {
        assertEquals(1.0, Heartbeat.moodBoost(mood("平静", 5)), eps)
        assertEquals(p(60.0), p(60.0, mood("平静", 5)), eps)
    }

    @Test
    fun `认不出的心情不放大`() {
        // 词表里没有的词按中性处理：不猜他的心情，也就不改他的节奏。
        assertEquals(1.0, Heartbeat.moodBoost(mood("说不上来", 5)), eps)
    }

    @Test
    fun `想念这种轻情绪也算心里不静`() {
        // 想念的极性是 0，但劲头是负的：照样放大。
        assertTrue(p(60.0, mood("想念", 3)) > p(60.0))
    }

    @Test
    fun `情绪越浓放大越多`() {
        val b3 = Heartbeat.moodBoost(mood("雀跃", 3))
        val b4 = Heartbeat.moodBoost(mood("雀跃", 4))
        val b5 = Heartbeat.moodBoost(mood("雀跃", 5))
        assertTrue(b3 < b4)
        assertTrue(b4 < b5)
        assertTrue(b3 >= Heartbeat.MOOD_BOOST_MIN - eps)
        assertTrue(b5 <= Heartbeat.MOOD_BOOST_MAX + eps)
    }

    @Test
    fun `情绪放大的倍数不会失控`() {
        // 强度传 99 也只到 1.55，夹在 1.15~1.6 之间。
        val boost = Heartbeat.moodBoost(mood("兴奋", 99))
        assertTrue(boost <= Heartbeat.MOOD_BOOST_MAX + eps)
        assertEquals(1.55, boost, eps)
    }

    // ------------------------------------------------------------- 深夜

    @Test
    fun `深夜整体减半`() {
        assertEquals(p(60.0, hour = 14) * 0.5, p(60.0, hour = 3), eps)
        assertEquals(p(0.0, hour = 14) * 0.5, p(0.0, hour = 0), eps)
    }

    @Test
    fun `凌晨五点是深夜六点不是`() {
        assertTrue(p(60.0, hour = 5) < p(60.0, hour = 14))
        assertEquals(p(60.0, hour = 14), p(60.0, hour = 6), eps)
    }

    @Test
    fun `深夜与情绪叠加`() {
        val expected = p(60.0) * Heartbeat.moodBoost(mood("雀跃", 4)) * 0.5
        assertEquals(expected, p(60.0, mood("雀跃", 4), hour = 3), eps)
    }

    @Test
    fun `深夜里曲线的形状不变`() {
        // 夜里只是整体调暗，谷底和爬升还在。
        assertTrue(p(10.0, hour = 3) < p(60.0, hour = 3))
        assertTrue(p(60.0, hour = 3) < p(180.0, hour = 3))
    }

    // ------------------------------------------------------------- 坏输入

    @Test
    fun `负时间当作刚刚互动完`() {
        assertEquals(p(0.0), p(-30.0), eps)
    }

    @Test
    fun `NaN 当作刚刚互动完`() {
        assertEquals(p(0.0), p(Double.NaN), eps)
    }

    @Test
    fun `超大时间不溢出`() {
        assertEquals(Heartbeat.CEILING, p(1e9), 1e-6)
        assertEquals(Heartbeat.CEILING, p(Double.POSITIVE_INFINITY), 1e-6)
    }

    @Test
    fun `概率永远夹在安全区间里`() {
        val times = listOf(-1.0, 0.0, 5.0, 10.0, 60.0, 180.0, 1e6, Double.NaN)
        val moods = listOf(
            MoodState(),
            mood("雀跃", 5),
            mood("低落", 5),
            mood("平静", 5),
            mood("说不上来", 5),
            mood("兴奋", 0)
        )
        val hours = listOf(-5, 0, 3, 7, 12, 23, 99)
        times.forEach { t ->
            moods.forEach { m ->
                hours.forEach { h ->
                    val value = p(t, m, h)
                    assertFalse("不该出现 NaN：t=$t mood=$m hour=$h", value.isNaN())
                    assertTrue("不该是负数：$value", value >= Heartbeat.MIN_PROBABILITY - eps)
                    assertTrue("不该超过上限：$value", value <= Heartbeat.MAX_PROBABILITY + eps)
                }
            }
        }
    }

    @Test
    fun `极强情绪不会把概率推过上限`() {
        // 三小时（已在上限 0.85）再乘 1.55：夹到 0.9，而不是 1.3。
        assertEquals(Heartbeat.MAX_PROBABILITY, p(180.0, mood("兴奋", 5)), 1e-6)
    }

    // ------------------------------------------------------------- 掷骰子

    @Test
    fun `随机数为零一定触发`() {
        assertTrue(Heartbeat.shouldFire(probability = Heartbeat.MIN_PROBABILITY, roll = 0.0))
    }

    @Test
    fun `随机数为一一定不触发`() {
        assertFalse(Heartbeat.shouldFire(probability = 0.5, roll = 1.0))
    }

    @Test
    fun `随机数刚好等于概率不算触发`() {
        assertFalse(Heartbeat.shouldFire(probability = 0.5, roll = 0.5))
        assertTrue(Heartbeat.shouldFire(probability = 0.5, roll = 0.4999))
    }

    @Test
    fun `概率为零或坏值时不触发`() {
        assertFalse(Heartbeat.shouldFire(probability = 0.0, roll = 0.0))
        assertFalse(Heartbeat.shouldFire(probability = Double.NaN, roll = 0.0))
        assertFalse(Heartbeat.shouldFire(probability = 0.5, roll = Double.NaN))
    }

    // ------------------------------------------------------------- 重置时钟

    @Test
    fun `重置只动开始时间`() {
        val before = HeartbeatState(lastResetAt = 1_000L, lastFiredAt = 500L)
        val after = Heartbeat.nextReset(before, now = 20_000L)
        assertEquals(20_000L, after.lastResetAt)
        assertEquals(500L, after.lastFiredAt)
    }

    @Test
    fun `触发时顺手记下这一次真的想了`() {
        val after = Heartbeat.nextReset(HeartbeatState(lastResetAt = 1_000L), now = 20_000L, fired = true)
        assertEquals(20_000L, after.lastResetAt)
        assertEquals(20_000L, after.lastFiredAt)
    }

    @Test
    fun `时钟回拨不会把状态弄坏`() {
        val before = HeartbeatState(lastResetAt = 50_000L, lastFiredAt = 40_000L)
        assertEquals(before, Heartbeat.nextReset(before, now = 1_000L))
        assertEquals(before, Heartbeat.nextReset(before, now = 0L))
    }

    @Test
    fun `最近一次动静取最大值`() {
        assertEquals(100L, Heartbeat.latestMark(null, 0L, 100L, 50L))
        assertEquals(0L, Heartbeat.latestMark(null, null))
        assertEquals(100L, Heartbeat.latestMark(-5L, 100L))
        assertEquals(0L, Heartbeat.latestMark(-5L))
    }

    @Test
    fun `距离重置的分钟数算得对`() {
        assertEquals(1.0, Heartbeat.minutesBetween(60_000L, 120_000L), eps)
        assertEquals(0.5, Heartbeat.minutesBetween(60_000L, 90_000L), eps)
    }

    @Test
    fun `没有起点或时钟回拨都算刚刚`() {
        assertEquals(0.0, Heartbeat.minutesBetween(0L, 120_000L), eps)
        assertEquals(0.0, Heartbeat.minutesBetween(500_000L, 100_000L), eps)
    }

    // ------------------------------------------------------------ 每分钟 → 每一拍

    @Test
    fun `一分钟的概率换算到半分钟一拍`() {
        // 两次独立的半分钟机会，合起来才是这一分钟的概率。
        val perBeat = Heartbeat.perBeatProbability(0.10, 0.5)
        assertEquals(1.0 - Math.pow(0.9, 0.5), perBeat, 1e-9)
        assertEquals(0.10, 1.0 - (1.0 - perBeat) * (1.0 - perBeat), 1e-9)
    }

    @Test
    fun `拍得越稀本拍概率越高但一分钟总概率不变`() {
        val half = Heartbeat.perBeatProbability(0.5, 0.5)
        val two = Heartbeat.perBeatProbability(0.5, 2.0)
        assertTrue(two > half)
        // 四拍半分钟 ≈ 一次两分钟
        val fourBeats = 1.0 - Math.pow(1.0 - half, 4.0)
        assertEquals(two, fourBeats, 1e-9)
    }

    @Test
    fun `换算函数对坏输入不炸`() {
        assertEquals(0.0, Heartbeat.perBeatProbability(0.0, 0.5), eps)
        assertEquals(0.0, Heartbeat.perBeatProbability(Double.NaN, 0.5), eps)
        assertEquals(0.0, Heartbeat.perBeatProbability(-1.0, 0.5), eps)
        // 间隔坏掉时退回默认节拍，而不是除零或返回 NaN
        val fallback = Heartbeat.perBeatProbability(0.2, Double.NaN)
        assertEquals(Heartbeat.perBeatProbability(0.2, Heartbeat.BEAT_MINUTES), fallback, 1e-9)
        assertTrue(Heartbeat.perBeatProbability(1.0, 0.5) <= 1.0)
    }

    @Test
    fun `收尾期不掷骰子是控制器的事而曲线本身仍然很低`() {
        // 曲线在 t=0 仍有值（0.35），是控制器用 COOL_DOWN_MINUTES 决定这段时间干脆不掷。
        assertTrue(Heartbeat.probability(0.0) > 0.3)
        assertTrue(Heartbeat.probability(Heartbeat.COOL_DOWN_MINUTES) < 0.2)
    }
}
