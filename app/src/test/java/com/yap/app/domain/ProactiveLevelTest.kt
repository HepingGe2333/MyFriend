package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动上限由人格决定（规格 §一 / §3）：
 * proactiveLevel 1–5 → 每日 1–5 次主动开口，同时给心跳概率一个 0.7–1.3 的权重。
 *
 * 设置页里已经没有任何「每天最多主动几次」的输入；这个测试守住「设置删了，行为还在」。
 */
class ProactiveLevelTest {

    @Test
    fun `每日上限就是人格的 1 到 5`() {
        assertEquals(1, ProactiveLevel.dailyCap(1))
        assertEquals(3, ProactiveLevel.dailyCap(3))
        assertEquals(5, ProactiveLevel.dailyCap(5))
    }

    @Test
    fun `越界值被夹到 1 到 5`() {
        assertEquals(1, ProactiveLevel.dailyCap(-3))
        assertEquals(1, ProactiveLevel.dailyCap(0))
        assertEquals(5, ProactiveLevel.dailyCap(99))
    }

    @Test
    fun `心跳权重随主动程度单调上升`() {
        val weights = (1..5).map { ProactiveLevel.heartbeatWeight(it) }
        weights.zipWithNext().forEach { (a, b) ->
            assertTrue("主动程度越高，心跳权重不该更低：$weights", b > a)
        }
        assertEquals(0.7, weights.first(), 0.0001)
        assertEquals(1.0, ProactiveLevel.heartbeatWeight(3), 0.0001)
        assertEquals(1.3, weights.last(), 0.0001)
    }

    @Test
    fun `心情只能在人格上限上挪一格 不会超过 5 或掉到 0`() {
        val base1 = ProactiveLevel.dailyCap(1)
        val base5 = ProactiveLevel.dailyCap(5)
        val negative = MoodTuning(dailyMessageCapDelta = -1)
        val positive = MoodTuning(dailyMessageCapDelta = +1)
        assertEquals(1, MoodBehavior.effectiveDailyCap(base1, negative))
        assertEquals(5, MoodBehavior.effectiveDailyCap(base5, positive))
        assertEquals(2, MoodBehavior.effectiveDailyCap(base1, positive))
        assertEquals(4, MoodBehavior.effectiveDailyCap(base5, negative))
    }

    @Test
    fun `每个人话解释都不为空`() {
        (1..5).forEach { assertTrue(ProactiveLevel.description(it).isNotBlank()) }
    }
}
