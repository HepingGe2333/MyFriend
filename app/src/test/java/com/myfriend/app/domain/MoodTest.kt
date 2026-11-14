package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 心情的纯领域测试：词表规整、随时间淡去、引导时机、提示词措辞，以及他在思考时
 * 通过 agent 协议改心情的解析。
 */
class MoodTest {

    private val hour = 3_600_000L
    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    // ------------------------------------------------------------- 词表

    @Test
    fun `空标签回落到平静`() {
        assertEquals(MoodLexicon.BASELINE, MoodLexicon.normalize(""))
        assertEquals(MoodLexicon.BASELINE, MoodLexicon.normalize("   "))
    }

    @Test
    fun `标签去掉空白与标点并限长`() {
        assertEquals("有点累", MoodLexicon.normalize("  有点 累 "))
        assertEquals(12, MoodLexicon.normalize("一二三四五六七八九十一二三四五").length)
    }

    @Test
    fun `未知词按中性处理不猜极性`() {
        val word = MoodLexicon.classify("说不上来")
        assertEquals(0, word.valence)
        assertEquals(0, word.energy)
        assertEquals("说不上来", word.label)
    }

    @Test
    fun `负面与低能量判断`() {
        assertTrue(MoodLexicon.isNegative("低落"))
        assertFalse(MoodLexicon.isNegative("平静"))
        assertTrue(MoodLexicon.isLowEnergy("有点累"))
        assertFalse(MoodLexicon.isLowEnergy("雀跃"))
    }

    @Test
    fun `展示文案带强度与原因`() {
        val state = MoodState(label = "雀跃", intensity = 4, reason = "刚聊到想去的地方")
        assertEquals("雀跃（4/5） · 刚聊到想去的地方", state.display())
        assertEquals(MoodLexicon.BASELINE, MoodState().display())
    }

    // ------------------------------------------------------------- 衰减

    @Test
    fun `情绪几小时后回到基准`() {
        val state = MoodState(label = "低落", intensity = 4, reason = "被一句话伤到", updatedAt = now)
        val faded = MoodDrift.decay(state, now + MoodDrift.FADE_AFTER_MS)
        assertEquals(MoodLexicon.BASELINE, faded.label)
        assertEquals(1, faded.intensity)
        assertEquals("", faded.reason)
    }

    @Test
    fun `每小时掉一点强度但不会掉到零`() {
        val state = MoodState(label = "雀跃", intensity = 4, reason = "好消息", updatedAt = now)
        val twoHours = MoodDrift.decay(state, now + 2 * hour)
        assertEquals("雀跃", twoHours.label)
        assertEquals(2, twoHours.intensity)

        val fiveHours = MoodDrift.decay(state, now + 5 * hour)
        assertEquals(1, fiveHours.intensity)
    }

    @Test
    fun `淡到最低时负面情绪先被抹平`() {
        val state = MoodState(label = "低落", intensity = 2, reason = "小事", updatedAt = now)
        val faded = MoodDrift.decay(state, now + 2 * hour)
        assertEquals(1, faded.intensity)
        assertEquals(MoodLexicon.BASELINE, faded.label)
        assertEquals("", faded.reason)
    }

    @Test
    fun `刚发生的心情不会被打折`() {
        val state = MoodState(label = "期待", intensity = 3, reason = "明天见面", updatedAt = now)
        assertEquals(state, MoodDrift.decay(state, now + 30_000L))
    }

    @Test
    fun `空心情衰减后至少有个基准词`() {
        assertEquals(MoodLexicon.BASELINE, MoodDrift.decay(MoodState(), now).label)
    }

    // ------------------------------------------------------------- 引导时机

    @Test
    fun `说够三句就该回头看一眼`() {
        val state = MoodState(label = "平静", intensity = 1, updatedAt = now)
        assertFalse(MoodDrift.shouldReflect(state, 2, now + minute))
        assertTrue(MoodDrift.shouldReflect(state, 3, now + minute))
    }

    @Test
    fun `很久没动过心情时聊一句也值得看看`() {
        val state = MoodState(label = "平静", intensity = 1, updatedAt = now)
        assertTrue(MoodDrift.shouldReflect(state, 1, now + 5 * hour))
        assertFalse(MoodDrift.shouldReflect(state, 0, now + 5 * hour))
    }

    @Test
    fun `刚被问过就不会再问同样一句`() {
        val state = MoodState(label = "平静", intensity = 1, updatedAt = now)
        val nudgedAt = now + 2 * hour
        assertFalse(MoodDrift.shouldReflect(state, 0, now + 5 * hour, lastTouchAt = nudgedAt))
    }

    // ------------------------------------------------------------- 提示词

    @Test
    fun `提示词里带上心情与原因`() {
        val line = MoodPrompt.line(MoodState(label = "有点累", intensity = 3, reason = "今天加班"))
        assertTrue(line.contains("有点累"))
        assertTrue(line.contains("3/5"))
        assertTrue(line.contains("今天加班"))
        assertTrue(line.contains("更短"))
    }

    @Test
    fun `没有心情时也给一句基准描述`() {
        val line = MoodPrompt.line(MoodState())
        assertTrue(line.contains("心情"))
        assertTrue(line.contains(MoodLexicon.BASELINE))
    }

    @Test
    fun `引导词是第一人称的自言自语`() {
        val nudge = MoodPrompt.reflectionNudge(MoodState(label = "平静", intensity = 1), 4)
        assertTrue(nudge.contains("现在我的心情变得"))
        assertTrue(nudge.contains("set_mood"))
        assertTrue(nudge.contains("4"))
        // 不要机械指令
        assertFalse(nudge.contains("请更新"))
    }

    // ------------------------------------------------------------- 他在思考时改心情

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun decision(json: String): AgentDecision? =
        AgentDecisionCodec.parse(json, now, zone)?.let { AgentDecisionCodec.normalize(it, now) }

    @Test
    fun `agent 协议能解析心情动作`() {
        val parsed = decision(
            """{"thought":"聊完之后心里松了一点","actions":[{"type":"mood","mood":"放松","intensity":3,"reason":"他说话的语气软下来了"}]}"""
        )
        val mood = parsed?.actions?.filterIsInstance<AgentAction.Mood>()?.firstOrNull()
        assertNotNull(mood)
        assertEquals("放松", mood!!.label)
        assertEquals(3, mood.intensity)
        assertEquals("他说话的语气软下来了", mood.reason)
        assertTrue(parsed.summary().contains("心情"))
    }

    @Test
    fun `中文别名与缺失强度都能兜住`() {
        val parsed = decision("""{"thought":"有点烦","actions":[{"type":"心情","mood":" 心烦 "}]}""")
        val mood = parsed?.actions?.filterIsInstance<AgentAction.Mood>()?.firstOrNull()
        assertNotNull(mood)
        assertEquals("心烦", mood!!.label)
        assertTrue(mood.intensity in 1..5)
    }

    @Test
    fun `没写心情词的动作被丢掉`() {
        val parsed = decision("""{"thought":"","actions":[{"type":"mood","reason":"说不出"}]}""")
        assertTrue(parsed?.actions.orEmpty().none { it is AgentAction.Mood })
    }

    @Test
    fun `心情动作能序列化后重新读回`() {
        val original = AgentDecision(
            thought = "有点雀跃",
            actions = listOf(AgentAction.Mood("雀跃", 4, "刚聊到想去的地方"))
        )
        val round = decision(AgentDecisionCodec.toJson(original))
        val mood = round?.actions?.filterIsInstance<AgentAction.Mood>()?.firstOrNull()
        assertNotNull(mood)
        assertEquals("雀跃", mood!!.label)
        assertEquals(4, mood.intensity)
    }

    @Test
    fun `心情一类的动作强度会被夹在范围内`() {
        val parsed = decision("""{"thought":"","actions":[{"type":"mood","mood":"兴奋","intensity":9}]}""")
        val mood = parsed?.actions?.filterIsInstance<AgentAction.Mood>()?.firstOrNull()
        assertNotNull(mood)
        assertTrue(mood!!.intensity in 1..5)
    }
}
