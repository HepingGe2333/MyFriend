package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多维心情的纯领域测试（`spec/mood/README.md` §1 / §3 / §5）。
 *
 * 这里钉住的是「提示词里到底写了什么」和「旧数据怎么折算」：
 * 维度取值错一格不会崩，但会让他一整晚的语气都不对，而这种错没有人会报 bug——
 * 用户只会觉得「他今天怪怪的」。
 */
class MoodDimensionsTest {

    private val hour = 3_600_000L

    // ------------------------------------------------------------- 范围与规整

    @Test
    fun `维度值被夹到规范范围`() {
        val reading = MoodDimensions.normalize(
            label = "  有点 累 ",
            intensity = 9,
            valence = -7,
            arousal = 11,
            dominance = -3
        )
        assertEquals("有点累", reading.label)
        assertEquals(5, reading.intensity)
        assertEquals(-2, reading.valence)
        assertEquals(5, reading.arousal)
        assertEquals(0, reading.dominance)
    }

    @Test
    fun `空标签回落到平静`() {
        assertEquals(MoodLexicon.BASELINE, MoodDimensions.normalize("", 2, 0, 2, 3).label)
    }

    // ------------------------------------------------------------- 旧数据映射

    @Test
    fun `旧标签映射到 valence 累与烦是负 平静是零 开心是正`() {
        assertEquals(-1, MoodDimensions.valenceForLabel("有点累"))
        assertEquals(-1, MoodDimensions.valenceForLabel("累"))
        // 「烦」在词表里是 -2，但旧数据看不出强度时只给「略偏」：规范写的是 -1。
        assertEquals(-1, MoodDimensions.valenceForLabel("心烦"))
        assertEquals(0, MoodDimensions.valenceForLabel("平静"))
        assertEquals(0, MoodDimensions.valenceForLabel(""))
        assertEquals(1, MoodDimensions.valenceForLabel("开心"))
        assertEquals(1, MoodDimensions.valenceForLabel("雀跃"))
        // 认不出的词不猜极性。
        assertEquals(0, MoodDimensions.valenceForLabel("说不上来"))
    }

    @Test
    fun `旧数据由 intensity 推出 arousal 且掌控感固定为 3`() {
        val tired = MoodDimensions.fromLegacy("有点累", intensity = 4, reason = "加班", updatedAt = 123L)
        assertEquals("有点累", tired.label)
        assertEquals(4, tired.arousal)
        assertEquals(3, tired.dominance)
        assertEquals(-1, tired.valence)
        assertEquals("加班", tired.reason)
        assertEquals(123L, tired.updatedAt)

        assertEquals(MoodDimensions.BASELINE_AROUSAL, MoodDimensions.fromLegacy("平静", 1).arousal)
        // 越界强度也兜住。
        assertEquals(5, MoodDimensions.fromLegacy("累", 99).intensity)
        assertEquals(1, MoodDimensions.fromLegacy("累", -1).intensity)
    }

    // ------------------------------------------------------------- 变化幅度

    @Test
    fun `单次变化每个维度最多 2`() {
        val previous = MoodReading(label = "平静", intensity = 2, valence = 0, arousal = 2, dominance = 3)
        val wild = MoodReading(label = "心烦", intensity = 5, valence = -2, arousal = 5, dominance = 0)
        val limited = MoodDimensions.limitChange(previous, wild)
        assertEquals(-2, limited.valence)
        assertEquals("arousal 从 2 最多涨到 4", 4, limited.arousal)
        assertEquals("dominance 从 3 最多掉到 1", 1, limited.dominance)
        assertEquals("强度也受同一上限", 4, limited.intensity)
        // 标签不做限制：词本身就是模型对语气最直接的判断。
        assertEquals("心烦", limited.label)
    }

    @Test
    fun `幅度限制不会把值推出合法范围`() {
        val previous = MoodReading(label = "平静", intensity = 1, valence = 2, arousal = 5, dominance = 5)
        val limited = MoodDimensions.limitChange(
            previous,
            MoodReading(label = "平静", intensity = 1, valence = 2, arousal = 5, dominance = 5)
        )
        assertEquals(2, limited.valence)
        assertEquals(5, limited.arousal)
        assertEquals(5, limited.dominance)
    }

    @Test
    fun `没有明显变化时不写库`() {
        val previous = MoodReading(label = "平静", intensity = 2, valence = 0, arousal = 2, dominance = 3)
        assertFalse(MoodDimensions.isMeaningfulChange(previous, previous.copy(updatedAt = 999L)))
        assertTrue(MoodDimensions.isMeaningfulChange(previous, previous.copy(label = "有点累")))
        assertTrue(MoodDimensions.isMeaningfulChange(previous, previous.copy(arousal = 3)))
        assertTrue(MoodDimensions.isMeaningfulChange(previous, previous.copy(intensity = 3)))
        // 维度变了但标签没变也算变化：语气不一样了。
        assertTrue(MoodDimensions.isMeaningfulChange(previous, previous.copy(dominance = 1)))
    }

    // ------------------------------------------------------------- 时间衰减

    @Test
    fun `每小时向基准 0 2 3 靠一步`() {
        val reading = MoodReading(label = "心烦", intensity = 4, valence = -2, arousal = 5, dominance = 0)
        val one = MoodDimensions.decay(reading, 1)
        assertEquals(-1, one.valence)
        assertEquals(4, one.arousal)
        assertEquals(1, one.dominance)

        val three = MoodDimensions.decay(reading, 3)
        assertEquals("负值三步回到 0", 0, three.valence)
        assertEquals("唤醒度三步从 5 到 2", 2, three.arousal)
        assertEquals("掌控感三步从 0 到 3", 3, three.dominance)
    }

    @Test
    fun `衰减不会越过基准 零小时原样返回`() {
        val baseline = MoodReading(label = "平静", intensity = 1, valence = 0, arousal = 2, dominance = 3)
        assertEquals(baseline, MoodDimensions.decay(baseline, 0))
        assertEquals(baseline, MoodDimensions.decay(baseline, 12))
        assertEquals(baseline, MoodDimensions.decay(baseline, -4))
    }

    @Test
    fun `过了几小时按整点算`() {
        val now = 1_000_000_000_000L
        assertEquals(0L, MoodDimensions.elapsedHours(now - 59 * 60_000L, now))
        assertEquals(1L, MoodDimensions.elapsedHours(now - hour, now))
        assertEquals(0L, MoodDimensions.elapsedHours(now + hour, now))
    }

    // ------------------------------------------------------------- 行为提示

    @Test
    fun `累的时候提示说得更短 少开话题`() {
        val hints = MoodDimensions.behaviorHints(
            MoodReading(label = "有点累", intensity = 4, valence = -1, arousal = 1, dominance = 3)
        )
        assertTrue("累的提示必须排在最前面", hints.first().contains("短"))
        assertTrue(hints.first().contains("少主动开新话题"))
        assertTrue("最多两句", hints.size in 1..2)
    }

    @Test
    fun `愉快的时候提示可以多说一句`() {
        val hints = MoodDimensions.behaviorHints(
            MoodReading(label = "开心", intensity = 3, valence = 2, arousal = 3, dominance = 3)
        )
        assertTrue(hints.any { it.contains("多说一句") })
    }

    @Test
    fun `掌控感低的时候更被动`() {
        val hints = MoodDimensions.behaviorHints(
            MoodReading(label = "不安", intensity = 3, valence = -1, arousal = 3, dominance = 0)
        )
        assertTrue(hints.any { it.contains("被动") })
        assertTrue(hints.any { it.contains("少下判断") })
    }

    @Test
    fun `行为提示最多两句`() {
        val hints = MoodDimensions.behaviorHints(
            MoodReading(label = "低落", intensity = 5, valence = -2, arousal = 5, dominance = 0)
        )
        assertTrue("1–2 句就够，多了会变成说明书", hints.size in 1..2)
    }

    @Test
    fun `基准心情也给一句自然`() {
        val hints = MoodDimensions.behaviorHints(MoodDimensions.fromLegacy("平静", 1))
        assertEquals(1, hints.size)
        assertTrue(hints.first().contains("自然"))
    }

    // ------------------------------------------------------------- 提示词

    @Test
    fun `多维心情段按规范 §3 写出三个维度`() {
        val lines = MoodDimensions.promptLines(
            MoodReading(label = "有点累", intensity = 3, valence = -1, arousal = 1, dominance = 2, reason = "今天加班")
        )
        val text = lines.joinToString("\n")
        assertTrue(text.contains("心情：有点累（强度 3/5）"))
        assertTrue(text.contains("愉快度 -1"))
        assertTrue(text.contains("唤醒度 1/5"))
        assertTrue(text.contains("掌控感 2/5"))
        assertTrue(text.contains("原因：今天加班"))
        assertTrue(text.contains("- 这会影响你怎么说："))
        assertTrue(text.contains("不要直接报数值"))
        assertTrue(text.contains("用说话方式体现"))
    }

    @Test
    fun `多维心情段不许出现我的情绪是这种自述`() {
        val text = MoodDimensions.promptLines(
            MoodReading(label = "开心", intensity = 3, valence = 1, arousal = 3, dominance = 4)
        ).joinToString("\n")
        // 「我的情绪是」只允许作为禁令的反例出现一次，不能有任何示范句这样写。
        val hits = Regex("我的情绪是").findAll(text).toList()
        assertEquals(1, hits.size)
        val before = text.substring((hits.first().range.first - 12).coerceAtLeast(0), hits.first().range.first)
        assertTrue("那一次必须是在说「不要这样说」", before.contains("不要说"))
    }

    @Test
    fun `MoodReading 与旧 MoodState 可以互相读出同一句`() {
        val state = MoodState(label = "雀跃", intensity = 4, reason = "好消息", updatedAt = 5L)
        val reading = MoodReading.of(state)
        assertEquals("雀跃", reading.label)
        assertEquals(4, reading.intensity)
        assertEquals("好消息", reading.reason)
        assertEquals(5L, reading.updatedAt)
        assertEquals(state.display(), reading.display())
        assertNotEquals(MoodState(), reading.toMoodState())
    }
}
