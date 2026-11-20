package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MentalActivityTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `活跃窗口内每三轮或三分钟触发 窗口外不触发`() {
        assertEquals(
            MentalTrigger.ROUNDS,
            MentalActivity.trigger(now, now - 1_000, 0, 3, false, 2, 0)
        )
        assertEquals(
            MentalTrigger.TIME,
            MentalActivity.trigger(now, now - 1_000, now - 181_000, 0, false, 2, 0)
        )
        assertNull(MentalActivity.trigger(now, now - 11 * 60_000, now - 181_000, 9, false, 2, 0))
    }

    @Test
    fun `激动或心情变化两格必做`() {
        assertEquals(
            MentalTrigger.AROUSED,
            MentalActivity.trigger(now, now - 1_000, now - 1_000, 0, false, 4, 0)
        )
        assertEquals(
            MentalTrigger.MOOD_SHIFT,
            MentalActivity.trigger(now, now - 1_000, now - 1_000, 0, false, 2, 2)
        )
    }

    @Test
    fun `解析 JSON 并夹取每个维度到正负一`() {
        val draft = MentalActivity.parse(
            """{"thought":"他刚才那句话有点刺","valence":-9,"arousal":5,"dominance":0}"""
        )
        assertNotNull(draft)
        assertEquals("他刚才那句话有点刺", draft!!.thought)
        assertEquals(-1, draft.valenceDelta)
        assertEquals(1, draft.arousalDelta)
        assertEquals(0, draft.dominanceDelta)
    }

    @Test
    fun `念头最多六十字且下一轮提示词带固定前缀`() {
        val long = MentalActivity.parse("字".repeat(100))!!
        assertEquals(60, long.thought.length)
        assertEquals("你刚才心里想：嗯", MentalActivity.promptBlock("嗯", now, now))
        assertEquals("", MentalActivity.promptBlock("嗯", now - 31 * 60_000, now))
    }

    @Test
    fun `心情变化取三个维度最大差`() {
        val a = MoodReading(valence = 1, arousal = 1, dominance = 1)
        val b = MoodReading(valence = -1, arousal = 4, dominance = 2)
        assertEquals(2 + 3 + 1, MentalActivity.moodShift(a, b))
        assertEquals(0, MentalActivity.moodShift(null, b))
    }
}
