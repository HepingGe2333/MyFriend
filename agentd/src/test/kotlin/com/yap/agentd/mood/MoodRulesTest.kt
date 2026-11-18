package com.yap.agentd.mood

import com.yap.agentd.store.Mood
import com.yap.app.core.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 情绪规则层（`spec/mood/README.md` §2）：
 * 范围夹取、单次幅度 ≤2、「没明显变化不写」、失败按时间衰减、多维提示词。
 */
class MoodRulesTest {

    private val previous = MoodRules.Values(
        label = "平静",
        valence = 1,
        arousal = 3,
        dominance = 3,
        intensity = 2
    )

    @Test
    fun `parse tolerates prose and string numbers and clamps ranges`() {
        val raw = """
            好的，判断如下：
            {"label":"雀跃","valence":"9","arousal":-3,"dominance":7,"intensity":9,"reason":"聊到想去的地方"}
        """.trimIndent()

        val values = MoodRules.parse(raw)!!
        assertEquals("雀跃", values.label)
        assertEquals("范围夹到 -2..2", 2, values.valence)
        assertEquals("范围夹到 0..5", 0, values.arousal)
        assertEquals(5, values.dominance)
        assertEquals(5, values.intensity)
        assertEquals("聊到想去的地方", values.reason)
    }

    @Test
    fun `parse rejects json without any usable number`() {
        assertNull(MoodRules.parse("not json"))
        assertNull(MoodRules.parse("""{"label":"平静"}"""))
        assertNull(MoodRules.parse(""))
    }

    @Test
    fun `limit caps a single change to two steps per dimension`() {
        val proposed = MoodRules.Values(
            label = "雀跃",
            valence = 2, // 上一轮 1 → 允许到 3，但范围 2
            arousal = 5, // 上一轮 3 → 允许到 5
            dominance = 0, // 上一轮 3 → 只允许到 1
            intensity = 4
        )
        val limited = MoodRules.limit(previous, proposed)

        assertEquals(2, limited.valence)
        assertEquals(5, limited.arousal)
        assertEquals("单次最多降 2", 1, limited.dominance)
        assertEquals(4, limited.intensity)
    }

    @Test
    fun `limit caps a jump from the bottom to the top`() {
        val low = MoodRules.Values(label = "低落", valence = -2, arousal = 0, dominance = 0, intensity = 4)
        val high = MoodRules.Values(label = "雀跃", valence = 2, arousal = 5, dominance = 5, intensity = 5)
        val limited = MoodRules.limit(low, high)

        assertEquals(0, limited.valence)
        assertEquals(2, limited.arousal)
        assertEquals(2, limited.dominance)
    }

    @Test
    fun `noticeable change requires at least one dimension label or intensity to move`() {
        assertFalse(MoodRules.isNoticeable(previous, previous.copy(reason = "换了原因")))
        assertTrue(MoodRules.isNoticeable(previous, previous.copy(valence = 0)))
        assertTrue(MoodRules.isNoticeable(previous, previous.copy(label = "心烦")))
    }

    @Test
    fun `decay walks one step per hour toward baseline`() {
        val value = MoodRules.Values(label = "雀跃", valence = 2, arousal = 5, dominance = 5, intensity = 4)
        val now = 10 * 3_600_000L

        val oneHour = MoodRules.decay(value, updatedAt = now - 3_600_000L, now = now)
        assertEquals(1, oneHour.valence)
        assertEquals(4, oneHour.arousal)
        assertEquals(4, oneHour.dominance)

        val threeHours = MoodRules.decay(value, updatedAt = now - 3 * 3_600_000L, now = now)
        assertEquals(0, threeHours.valence)
        assertEquals(2, threeHours.arousal)
        assertEquals(3, threeHours.dominance)

        assertEquals("不到一小时不衰减", value, MoodRules.decay(value, updatedAt = now - 60_000L, now = now))
    }

    @Test
    fun `prompt section carries all dimensions and forbids self report`() {
        val section = MoodRules.promptSection(
            MoodRules.Values(label = "有点累", valence = -1, arousal = 2, dominance = 3, intensity = 3)
        )
        assertTrue(section.startsWith("# 你的心情"))
        assertTrue(section.contains("愉快度 -1"))
        assertTrue(section.contains("唤醒度 2/5"))
        assertTrue(section.contains("掌控感 3/5"))
        assertTrue(section.contains("不要直接报数值"))
        assertEquals("只出现在禁止自述的那一句里", 1, Regex("我的情绪是").findAll(section).count())
    }

    @Test
    fun `auto evaluation triggers every four user messages or after 24 hours`() {
        assertTrue(MoodService.shouldEvaluate(userMessages = 4, cursor = 0, lastRunAt = 0, now = 1))
        assertFalse(MoodService.shouldEvaluate(userMessages = 3, cursor = 0, lastRunAt = 1, now = 2))
        assertTrue(
            "满 24 小时且有新消息",
            MoodService.shouldEvaluate(userMessages = 1, cursor = 0, lastRunAt = 0, now = 24 * 3_600_000L)
        )
    }

    @Test
    fun `legacy moods json without dimensions is migrated`() {
        val legacy = JsonValue.Obj(
            linkedMapOf(
                "personaId" to JsonValue.Str("p1"),
                "label" to JsonValue.Str("有点累"),
                "intensity" to JsonValue.Num("3"),
                "updatedAt" to JsonValue.Num("123")
            )
        )

        val mood = Mood.fromJson(legacy, "test")

        assertEquals("有点累", mood.label)
        assertEquals("valence 由 label 迁移", -1, mood.valence)
        assertEquals("arousal 由 intensity 推", 3, mood.arousal)
        assertEquals("dominance 默认 3", 3, mood.dominance)
        assertEquals("", mood.reason)
    }
}
