package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 心情驱动行为的纯领域测试。
 *
 * 覆盖：各心情档的乘数、每日上限的增减与边界、间隔的夹取、深夜的安静、
 * 「未知词不猜」，以及提示词只加很短的一行。
 */
class MoodBehaviorTest {

    /** 2026-05-01 的某个本地时刻；用本地时间构造，换时区也不会飘。 */
    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 5, 1, hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    /** 下午两点：肯定不是深夜。 */
    private val day = at(14)

    /** 凌晨三点：深夜档。 */
    private val night = at(3)

    private fun mood(label: String, intensity: Int = 3, reason: String = "") =
        MoodState(label = label, intensity = intensity, reason = reason, updatedAt = day)

    // ------------------------------------------------------------- 基准

    @Test
    fun `平静时行为一点不改`() {
        assertTrue(MoodBehavior.from(MoodState(label = "平静", intensity = 5), day).isNeutral())
        assertTrue(MoodBehavior.from(MoodState(), day).isNeutral())
    }

    @Test
    fun `未知心情不猜也不改行为`() {
        // 词表里没有的词按中性处理：宁可不猜，也不要替他想出一个心情。
        assertTrue(MoodBehavior.from(mood("说不上来", 4), day).isNeutral())
    }

    @Test
    fun `淡淡的正面只让节奏轻一点点`() {
        val tuning = MoodBehavior.from(mood("温柔", 2), day)
        assertEquals(0.95, tuning.tickIntervalMultiplier, 0.0001)
        assertEquals(0, tuning.dailyMessageCapDelta)
        assertTrue(tuning.replyLengthHint.isBlank())
        assertTrue(tuning.note.isBlank())
        assertTrue(MoodBehavior.wantsToBrowse(tuning))
    }

    @Test
    fun `轻负面只稍微收一点且不写进提示词`() {
        val tuning = MoodBehavior.from(mood("想念", 2), day)
        assertEquals(1.1, tuning.tickIntervalMultiplier, 0.0001)
        assertEquals(0.8, tuning.browseBias, 0.0001)
        assertTrue(tuning.replyLengthHint.isBlank())
    }

    // ------------------------------------------------------------- 低能量 / 低落

    @Test
    fun `没劲时想得少也说得少`() {
        val tuning = MoodBehavior.from(mood("有点累", 2), day)
        assertEquals(1.3, tuning.tickIntervalMultiplier, 0.0001)
        assertEquals(-1, tuning.dailyMessageCapDelta)
        assertEquals(0.35, tuning.browseBias, 0.0001)
        assertEquals(0.30, tuning.shareBias, 0.0001)
        assertFalse(MoodBehavior.wantsToBrowse(tuning))
        assertFalse(MoodBehavior.wantsToShare(tuning))
        assertTrue(tuning.replyLengthHint.contains("短一点"))
        assertTrue(tuning.note.isNotBlank())
    }

    @Test
    fun `低落比没劲更想得少`() {
        val tired = MoodBehavior.from(mood("有点累", 3), day)
        val low = MoodBehavior.from(mood("低落", 3), day)
        assertTrue(low.tickIntervalMultiplier > tired.tickIntervalMultiplier)
        assertTrue(low.tickIntervalMultiplier in 1.2..1.6)
        assertEquals(-1, low.dailyMessageCapDelta)
    }

    @Test
    fun `心情越浓间隔越长`() {
        val faint = MoodBehavior.from(mood("有点累", 1), day)
        val strong = MoodBehavior.from(mood("有点累", 5), day)
        assertEquals(1.2, faint.tickIntervalMultiplier, 0.0001)
        assertEquals(1.6, strong.tickIntervalMultiplier, 0.0001)
        assertTrue(strong.tickIntervalMultiplier > faint.tickIntervalMultiplier)
    }

    @Test
    fun `低落拉满时乘数被夹住`() {
        // 1.3 + 0.1*4 = 1.7，必须夹回 1.6：再难过也不能把循环拖死。
        assertEquals(1.6, MoodBehavior.from(mood("难过", 5), day).tickIntervalMultiplier, 0.0001)
    }

    // ------------------------------------------------------------- 雀跃

    @Test
    fun `雀跃时更勤快也更想看外面`() {
        val tuning = MoodBehavior.from(mood("雀跃", 4), day)
        assertEquals(0.85, tuning.tickIntervalMultiplier, 0.0001)
        assertEquals(1, tuning.dailyMessageCapDelta)
        assertEquals(1.25, tuning.browseBias, 0.0001)
        assertEquals(1.35, tuning.shareBias, 0.0001)
        assertTrue(MoodBehavior.wantsToBrowse(tuning))
        assertTrue(tuning.replyLengthHint.contains("多"))
    }

    @Test
    fun `雀跃但不浓时只稍微勤快`() {
        val tuning = MoodBehavior.from(mood("雀跃", 2), day)
        assertEquals(0.92, tuning.tickIntervalMultiplier, 0.0001)
        assertEquals(0, tuning.dailyMessageCapDelta)
    }

    // ------------------------------------------------------------- 每日上限

    @Test
    fun `低落时每日上限减一`() {
        val tuning = MoodBehavior.from(mood("低落", 3), day)
        assertEquals(2, MoodBehavior.effectiveDailyCap(3, tuning))
        assertEquals(1, MoodBehavior.effectiveDailyCap(1, tuning))
    }

    @Test
    fun `雀跃时每日上限加一但最高五`() {
        val tuning = MoodBehavior.from(mood("雀跃", 5), day)
        assertEquals(4, MoodBehavior.effectiveDailyCap(3, tuning))
        assertEquals(5, MoodBehavior.effectiveDailyCap(4, tuning))
        assertEquals(5, MoodBehavior.effectiveDailyCap(5, tuning))
    }

    @Test
    fun `主动消息关掉时心情不能替他打开`() {
        val happy = MoodBehavior.from(mood("雀跃", 5), day)
        assertEquals(0, MoodBehavior.effectiveDailyCap(0, happy))
        assertEquals(0, MoodBehavior.effectiveDailyCap(-1, happy))
    }

    // ------------------------------------------------------------- 间隔

    @Test
    fun `间隔换算后夹在一到六十分钟`() {
        val low = MoodBehavior.from(mood("低落", 5), day)   // 1.6
        assertEquals(10, MoodBehavior.adjustDelayMinutes(6, low))   // 9.6 -> 10
        assertEquals(60, MoodBehavior.adjustDelayMinutes(45, low))  // 72 -> 60
        assertEquals(60, MoodBehavior.adjustDelayMinutes(60, low))
        val happy = MoodBehavior.from(mood("雀跃", 4), day)  // 0.85
        assertEquals(1, MoodBehavior.adjustDelayMinutes(1, happy))  // 0.85 -> 1
        assertEquals(1, MoodBehavior.adjustDelayMinutes(-3, happy)) // 先夹成 1 再算
    }

    // ------------------------------------------------------------- 深夜

    @Test
    fun `深夜大家都更安静`() {
        val daytime = MoodBehavior.from(mood("有点累", 3), day)
        val deepNight = MoodBehavior.from(mood("有点累", 3), night)
        assertTrue(deepNight.tickIntervalMultiplier > daytime.tickIntervalMultiplier)
        assertTrue(deepNight.browseBias < daytime.browseBias)
        assertTrue(deepNight.note.isNotBlank())
    }

    // ------------------------------------------------------------- 提示词

    @Test
    fun `心情淡的时候提示词不加任何东西`() {
        assertEquals("", MoodBehavior.promptLine(MoodState(label = "平静", intensity = 4), day))
    }

    @Test
    fun `心情浓的时候提示词只加很短的一行`() {
        val line = MoodBehavior.promptLine(mood("有点累", 3), day)
        assertTrue(line.startsWith("\n\n"))
        assertTrue(line.contains("短一点"))
        assertTrue(line.length < 60)
    }

    @Test
    fun `分享倾向随心情变化`() {
        assertFalse(MoodBehavior.wantsToShare(MoodBehavior.from(mood("有点累", 3), day)))
        assertTrue(MoodBehavior.wantsToShare(MoodBehavior.from(mood("温柔", 2), day)))
    }

    @Test
    fun `心情带原因也不影响换算`() {
        val withReason = MoodBehavior.from(mood("低落", 3, reason = "对方说这周很忙"), day)
        val without = MoodBehavior.from(mood("低落", 3), day)
        assertEquals(without, withReason)
    }
}
