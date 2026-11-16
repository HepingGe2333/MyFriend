package com.yap.agentd.proactive

import com.yap.app.domain.MoodLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心情：**衰减走 `:agentcore` 的 `MoodDrift.decay`**（每小时 -1、6 小时回到平静/1），
 * 事件走本模块的 [MoodEvent]。两条规则都是纯函数，时间显式传入，所以边界可以逐条钉。
 */
class MoodStateTest {

    private val hour = 3_600_000L
    private val t0 = 1_700_000_000_000L

    @Test
    fun `mood fades one step per hour`() {
        val mood = MoodState(label = "雀跃", intensity = 4, updatedAt = t0)
        val afterOneHour = mood.decayed(t0 + hour)
        assertEquals("雀跃", afterOneHour.label)
        assertEquals(3, afterOneHour.intensity)

        val afterTwoHours = mood.decayed(t0 + 2 * hour)
        assertEquals("雀跃", afterTwoHours.label)
        assertEquals(2, afterTwoHours.intensity)
    }

    @Test
    fun `mood fades back to calm after six hours`() {
        val mood = MoodState(label = "低落", intensity = 5, updatedAt = t0)
        val faded = mood.decayed(t0 + 7 * hour)
        assertEquals(MoodLexicon.BASELINE, faded.label)
        assertEquals(1, faded.intensity)
    }

    @Test
    fun `empty mood becomes calm not blank`() {
        val faded = MoodState().decayed(t0 + 7 * hour)
        assertEquals(MoodLexicon.BASELINE, faded.label)
        assertEquals(1, faded.intensity)
    }

    @Test
    fun `negative mood label resets to calm once intensity is gone`() {
        val mood = MoodState(label = "难过", intensity = 2, updatedAt = t0)
        // 2 级、过两小时 → 1 级 + 负面词 → 回到平静（MoodDrift 的规则）。
        val faded = mood.decayed(t0 + 2 * hour)
        assertEquals(MoodLexicon.BASELINE, faded.label)
        assertEquals(1, faded.intensity)
    }

    @Test
    fun `user reply makes him happy`() {
        val mood = MoodState().onEvent(MoodEvent.USER_REPLIED, t0)
        assertEquals("开心", mood.label)
        assertEquals(3, mood.intensity)
        assertEquals(t0, mood.updatedAt)
        assertFalse(mood.isEmpty())
    }

    @Test
    fun `same event again only bumps intensity up to five`() {
        var mood = MoodState(label = "开心", intensity = 3, updatedAt = t0)
        mood = mood.onEvent(MoodEvent.USER_REPLIED, t0 + 1)
        assertEquals(4, mood.intensity)
        mood = mood.onEvent(MoodEvent.USER_REPLIED, t0 + 2)
        assertEquals(5, mood.intensity)
        mood = mood.onEvent(MoodEvent.USER_REPLIED, t0 + 3)
        assertEquals(5, mood.intensity)
    }

    @Test
    fun `different event replaces label and intensity`() {
        val mood = MoodState(label = "开心", intensity = 5, updatedAt = t0)
            .onEvent(MoodEvent.NIGHT, t0 + 1)
        assertEquals("犯困", mood.label)
        assertEquals(2, mood.intensity)
    }

    @Test
    fun `event after decay uses the decayed intensity`() {
        val decayed = MoodState(label = "开心", intensity = 4, updatedAt = t0).decayed(t0 + 2 * hour)
        assertEquals(2, decayed.intensity)
        val bumped = decayed.onEvent(MoodEvent.USER_REPLIED, t0 + 2 * hour)
        assertEquals(3, bumped.intensity)
    }

    @Test
    fun `display label falls back to the baseline`() {
        assertEquals(MoodLexicon.BASELINE, MoodState().displayLabel())
        assertEquals("想念", MoodState(label = "想念").displayLabel())
        assertEquals("想念", MoodState(label = "想念").label)
    }

    @Test
    fun `core round trip keeps label intensity and updated at`() {
        val mood = MoodState(label = "期待", intensity = 3, updatedAt = t0)
        val core = mood.core()
        assertEquals("期待", core.label)
        assertEquals(3, core.intensity)
        assertEquals(t0, core.updatedAt)
        assertEquals(mood, MoodState.fromCore(core))
    }

    @Test
    fun `core clamps out of range intensity`() {
        assertEquals(5, MoodState(label = "开心", intensity = 99).core().intensity)
        assertEquals(1, MoodState(label = "开心", intensity = -3).core().intensity)
    }

    @Test
    fun `mood affects the daily cap through the shared policy`() {
        val calm = LevelPolicy(level = 3)
        assertEquals(3, calm.dailyCap(MoodState.BASELINE))
        assertEquals(4, calm.dailyCap(MoodState(label = "雀跃", intensity = 4)))
        assertEquals(2, calm.dailyCap(MoodState(label = "低落", intensity = 4)))
    }

    @Test
    fun `event intensity stays within one to five`() {
        MoodEvent.entries.forEach { event ->
            assertTrue(event.intensity in 1..5)
            assertTrue(event.label.isNotBlank())
        }
    }
}
