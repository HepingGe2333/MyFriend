package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「# 引导」段的契约测试（`spec/context-profile/README.md`「引导 g12/g17」）。
 *
 * 引导是**这一轮**的语气总调子，跟着心情走：
 * - 愉快度 < 0 或唤醒度 ≤ 2 → g12（有点防备，不轻易示弱）；
 * - 其余（明显不错、能量也够）→ g17（只聊此刻，不翻旧账）；
 * - 没有多维心情 → g17（判不了就不假装他防备）。
 *
 * 位置固定：状态段里心情之后、人设（话风）之前。文案逐字固定，改一个字都要先让这里红。
 */
class GuidanceContractTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 18, 19, 40, 0, 0, zone).toInstant().toEpochMilli()

    private fun inputs(
        mood: MoodReading? = null,
        moodBlock: String = "\n\n## 你现在的心情\n平静（强度 2/5）。"
    ) = PromptBuilder.Inputs(
        name = "林深",
        now = now,
        zone = zone,
        moodBlock = moodBlock,
        moodDetail = mood
    )

    // ------------------------------------------------------------ 选择规则

    @Test
    fun `心情负向时用 g12 有点防备`() {
        val text = PromptBuilder.system(
            inputs(
                mood = MoodReading(label = "烦", intensity = 3, valence = -1, arousal = 4, dominance = 2)
            )
        )
        assertTrue("负向（愉快度 -1）要用 g12", text.contains(PromptBuilder.SECTION_GUIDANCE))
        assertTrue(text.contains("这一轮：有点防备，不轻易示弱"))
        assertFalse("不能同时出现 g17", text.contains("这一轮：只聊此刻"))
    }

    @Test
    fun `唤醒度低时也用 g12 即使愉快度不为负`() {
        val low = PromptBuilder.system(
            inputs(mood = MoodReading(label = "困", intensity = 2, valence = 0, arousal = 1, dominance = 2))
        )
        assertTrue("唤醒度 <2 要用 g12", low.contains("这一轮：有点防备，不轻易示弱"))
        val calm = PromptBuilder.system(
            inputs(mood = MoodReading(label = "平静", intensity = 2, valence = 0, arousal = 2, dominance = 3))
        )
        assertTrue("平静（arousal=2）应用 g17", calm.contains("这一轮：只聊此刻"))
    }

    @Test
    fun `心情正向且不低迷时用 g17 只聊此刻`() {
        val text = PromptBuilder.system(
            inputs(
                mood = MoodReading(label = "开心", intensity = 4, valence = 2, arousal = 4, dominance = 4)
            )
        )
        assertTrue(text.contains("这一轮：只聊此刻，不翻旧账"))
        assertFalse("正向时不该用 g12", text.contains("这一轮：有点防备"))
    }

    @Test
    fun `心情缺失时用 g17`() {
        // 没有多维心情（老调用方 / 人格卡预览 / 读库失败）：判不了，就不假装他防备。
        val text = PromptBuilder.system(inputs(mood = null))
        assertTrue("缺失时兜底 g17", text.contains("这一轮：只聊此刻，不翻旧账"))
        assertFalse(text.contains("这一轮：有点防备"))
        // 连旧的单行心情都没有时也一样。
        val minimal = PromptBuilder.system(inputs(mood = null, moodBlock = ""))
        assertTrue(minimal.contains("这一轮：只聊此刻，不翻旧账"))
        // 空标签 = 还没有心情（与服务端 PromptGuidance.select 同口径），即使维度是负的也不装防备。
        val blank = PromptBuilder.system(
            inputs(mood = MoodReading(label = "", valence = -2, arousal = 1), moodBlock = "")
        )
        assertTrue(blank.contains("这一轮：只聊此刻，不翻旧账"))
    }

    @Test
    fun `g12 与 g17 都逐字固定`() {
        assertEquals(
            listOf(
                "这一轮：有点防备，不轻易示弱",
                "用短句挡一下",
                "被问急了才多露半句",
                "不要掏心掏肺。"
            ),
            PromptBuilder.GUIDANCE_G12
        )
        assertEquals(
            listOf(
                "这一轮：只聊此刻，不翻旧账",
                "回应现在这句话",
                "不延伸过去",
                "不要反问。"
            ),
            PromptBuilder.GUIDANCE_G17
        )
        val g12 = PromptBuilder.guidanceBlock(
            inputs(mood = MoodReading(label = "烦", valence = -2, arousal = 3))
        )
        assertEquals(
            "# 引导\n这一轮：有点防备，不轻易示弱\n- 用短句挡一下\n- 被问急了才多露半句\n- 不要掏心掏肺。",
            g12
        )
        val g17 = PromptBuilder.guidanceBlock(
            inputs(mood = MoodReading(label = "开心", valence = 2, arousal = 5))
        )
        assertEquals(
            "# 引导\n这一轮：只聊此刻，不翻旧账\n- 回应现在这句话\n- 不延伸过去\n- 不要反问。",
            g17
        )
    }

    @Test
    fun `引导位置在心情之后 话风之前`() {
        val text = PromptBuilder.system(
            inputs(mood = MoodReading(label = "累", intensity = 3, valence = -1, arousal = 1))
        )
        val mood = text.indexOf("心情：")
        val guidance = text.indexOf(PromptBuilder.SECTION_GUIDANCE)
        val voice = text.indexOf(PromptBuilder.Scaffold.SECTION_VOICE)
        assertTrue("心情要在引导之前", mood in 0..<guidance)
        assertTrue("引导要在话风之前", guidance in 0..<voice)
        // 只在状态块最后出现一次，不能重复。
        assertEquals(1, Regex(Regex.escape(PromptBuilder.SECTION_GUIDANCE)).findAll(text).count())
    }

    @Test
    fun `引导不改心情数值与判定逻辑`() {
        val reading = MoodReading(label = "烦", intensity = 3, valence = -2, arousal = 5, dominance = 1)
        val text = PromptBuilder.system(inputs(mood = reading))
        // 状态段仍然按原样报三维数值：引导只是多读一次，不重写心情。
        assertTrue(text.contains("愉快度 -2"))
        assertTrue(text.contains("唤醒度 5/5"))
        assertTrue(text.contains("掌控感 1/5"))
        val copy = reading.copy()
        PromptBuilder.system(inputs(mood = copy))
        assertEquals("引导是纯函数，不能改传入的心情", reading, copy)
    }

    @Test
    fun `英文人格的引导标题也跟着本地化`() {
        // 人格语言是英文时，[PromptBuilder.localizeSectionTitles] 也要认得出「# 引导」。
        val zh = PromptBuilder.SECTION_GUIDANCE + "\n这一轮：只聊此刻，不翻旧账"
        assertTrue(
            PromptBuilder.localizeSectionTitles(zh, PromptLanguage.EN).startsWith("# Guidance")
        )
        assertEquals(zh, PromptBuilder.localizeSectionTitles(zh, PromptLanguage.ZH))
    }

    @Test
    fun `英文人格也有对应的引导段`() {
        val en = PromptBuilder.guidanceBlock(
            PromptBuilder.Inputs(
                name = "Lin",
                now = now,
                zone = zone,
                moodDetail = MoodReading(label = "annoyed", valence = -1, arousal = 4),
                language = PromptLanguage.EN
            )
        )
        assertTrue(en.startsWith("# Guidance"))
        assertTrue(en.contains("guarded"))
        assertFalse(en.contains("这一轮"))
        val calm = PromptBuilder.guidanceBlock(
            PromptBuilder.Inputs(
                name = "Lin",
                now = now,
                zone = zone,
                moodDetail = MoodReading(label = "happy", valence = 2, arousal = 4),
                language = PromptLanguage.EN
            )
        )
        assertTrue(calm.contains("only talk about right now"))
    }
}
