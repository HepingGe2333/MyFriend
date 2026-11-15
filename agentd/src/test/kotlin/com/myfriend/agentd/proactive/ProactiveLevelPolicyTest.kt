package com.myfriend.agentd.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动程度 → 每日上限 / 心跳权重：**直接跑 `spec/agent-core/cases/proactive-cap.json` 的全部用例**。
 *
 * [LevelPolicy] 不自己算上限：它调 `:agentcore` 的 `InProcessAgentCore.proactiveCap`
 * （`ProactiveLevel.dailyCap` + `MoodBehavior.effectiveDailyCap`），也就是契约里那份唯一实现。
 * 这个测试证明的是「服务端接线后的输出与契约逐条一致」，包括越界夹取（0→1、99→5）、
 * 心情只挪一格（+1 / -1 且不会被顶到 6 或压到 0）。
 */
class ProactiveLevelPolicyTest {

    @Test
    fun `proactive cap cases match the level policy`() {
        SpecCases.forEachCase("proactive-cap") { _, input, expect ->
            val level = input.int("level") ?: 3
            val mood = input.obj("mood")?.let { mood ->
                MoodState(
                    label = mood.str("label").orEmpty(),
                    intensity = mood.int("intensity") ?: 2
                )
            } ?: MoodState()
            val policy = LevelPolicy(level = level)

            assertEquals("normalizedLevel", expect.int("normalizedLevel"), policy.normalizedLevel())
            assertEquals("baseDailyCap", expect.int("baseDailyCap"), policy.baseDailyCap())
            assertEquals("dailyCap", expect.int("dailyCap"), policy.dailyCap(mood))
            assertEquals(
                "heartbeatWeight",
                requireNotNull(expect.double("heartbeatWeight")),
                policy.heartbeatWeight(),
                1e-9
            )
        }
    }

    @Test
    fun `level normalisation clamps out of range values`() {
        assertEquals(1, LevelPolicy(level = 0).normalizedLevel())
        assertEquals(5, LevelPolicy(level = 99).normalizedLevel())
        assertEquals(1, LevelPolicy(level = -3).normalizedLevel())
        assertEquals(3, LevelPolicy().normalizedLevel())
    }

    @Test
    fun `daily cap override wins over the level and the mood`() {
        val policy = LevelPolicy(level = 5, dailyCapOverride = 2)
        assertEquals(2, policy.dailyCap(MoodState(label = "雀跃", intensity = 5)))
        assertEquals(0, LevelPolicy(level = 3, dailyCapOverride = -4).dailyCap())
    }

    @Test
    fun `base daily cap ignores the mood while the effective cap applies it`() {
        val policy = LevelPolicy(level = 3)
        assertEquals(3, policy.baseDailyCap())
        assertEquals(4, policy.dailyCap(MoodState(label = "兴奋", intensity = 5)))
        assertEquals(2, policy.dailyCap(MoodState(label = "疲惫", intensity = 4)))
    }

    @Test
    fun `mood without a label does not change the cap`() {
        val policy = LevelPolicy(level = 4)
        assertEquals(4, policy.dailyCap(MoodState()))
        assertEquals(4, policy.dailyCap(MoodState(label = "  ", intensity = 5)))
    }

    @Test
    fun `calm mood never changes the cap`() {
        assertEquals(4, LevelPolicy(level = 4).dailyCap(MoodState(label = "平静", intensity = 5)))
    }

    @Test
    fun `heartbeat weight is monotonic`() {
        val weights = (1..5).map { LevelPolicy(level = it).heartbeatWeight() }
        assertEquals(weights.sorted(), weights)
        assertTrue(weights.first() < weights.last())
    }
}
