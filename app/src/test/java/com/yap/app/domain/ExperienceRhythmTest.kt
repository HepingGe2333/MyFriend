package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceRhythmTest {

    @Test
    fun `首条延迟按字数与心情修正并夹在 0_4 到 4 秒`() {
        assertEquals(400L, ExperienceRhythm.typingDelayMillis(0))
        assertEquals(425L, ExperienceRhythm.typingDelayMillis(1))
        assertEquals(625L, ExperienceRhythm.typingDelayMillis(0, arousal = 1, lowEnergy = false))
        assertTrue(ExperienceRhythm.typingDelayMillis(200) <= 4_000L)
        // 低唤醒/累比高唤醒慢；高唤醒明显更快。
        val low = ExperienceRhythm.typingDelayMillis(10, arousal = 0, lowEnergy = true)
        val high = ExperienceRhythm.typingDelayMillis(10, arousal = 5)
        assertTrue("低唤醒应更慢：$low vs $high", low > high)
    }

    @Test
    fun `连发间隔落在 0_8 到 2_5 秒`() {
        listOf(0, 1, 20, 120, 500).forEach { chars ->
            val gap = ExperienceRhythm.sendGapMillis(chars, arousal = 3)
            assertTrue("chars=$chars gap=$gap", gap in 800L..2_500L)
        }
        assertTrue(
            ExperienceRhythm.sendGapMillis(1, arousal = 5) <
                ExperienceRhythm.sendGapMillis(1, arousal = 0, lowEnergy = true)
        )
    }

    @Test
    fun `不带工具的回复按空行拆 1 到 3 条且不丢内容`() {
        assertEquals(emptyList<String>(), ExperienceRhythm.splitPlainReply("   "))
        assertEquals(listOf("在"), ExperienceRhythm.splitPlainReply("在"))
        assertEquals(listOf("在", "忙吗"), ExperienceRhythm.splitPlainReply("在\n\n忙吗"))
        val four = ExperienceRhythm.splitPlainReply("一\n\n二\n\n三\n\n四")
        assertEquals(3, four.size)
        assertTrue(four.first().contains("一") && four.first().contains("二"))
        assertTrue(four.last().contains("四"))
    }

    @Test
    fun `send_message 文本去空白并截到 120 字`() {
        assertEquals("你好", ExperienceRhythm.clampSendText(" 你好 "))
        val long = "字".repeat(200)
        assertEquals(120, ExperienceRhythm.clampSendText(long).length)
        assertEquals("a\n\nb", ExperienceRhythm.clampSendText("a\n\n\n\nb"))
    }
}
