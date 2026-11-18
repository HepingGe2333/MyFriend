package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 系统提示词里「多维心情」与「反 AI 味」两段的契约测试
 * （`spec/mood/README.md` §3 / §5）。
 *
 * 为什么值得单独两份契约：这两段都是**产品行为**，改一个词模型的表现就会变；
 * 而它们又不像脚手架那样有 JSON 对照，最容易在后续重构里被悄悄删掉或改软。
 */
class PromptMoodContractTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 18, 19, 40, 0, 0, zone).toInstant().toEpochMilli()

    private fun inputs(
        mood: MoodReading? = null,
        moodBlock: String = ""
    ) = PromptBuilder.Inputs(
        name = "林深",
        card = PersonaCard(),
        now = now,
        zone = zone,
        moodBlock = moodBlock,
        moodDetail = mood,
        capabilitiesSection = ToolCatalog.capabilityPrompt(webEnabled = true)
    )

    // ------------------------------------------------------------- 多维心情

    @Test
    fun `状态段里是多维心情而不是只报一个词`() {
        val text = PromptBuilder.system(
            inputs(
                mood = MoodReading(
                    label = "有点累",
                    intensity = 3,
                    valence = -1,
                    arousal = 1,
                    dominance = 2,
                    reason = "今天加班"
                )
            )
        )
        val state = text.substringAfter(PromptBuilder.Scaffold.SECTION_STATE)
        assertTrue("多维心情属于「你此刻的状态」", state.contains("心情：有点累（强度 3/5）"))
        assertTrue(state.contains("愉快度 -1"))
        assertTrue(state.contains("唤醒度 1/5"))
        assertTrue(state.contains("掌控感 2/5"))
        assertTrue("行为提示要按维度给", state.contains("- 这会影响你怎么说："))
        assertTrue("累的时候要更短、少开新话题", state.contains("少主动开新话题"))
        assertTrue("不许把数值念给用户", state.contains("不要直接报数值"))
        assertTrue("不许自述式表达", state.contains("不要说「我的情绪是…」"))
        // 状态段必须在话风之前（规范 §1 的顺序），多维心情不能把顺序挤乱。
        assertTrue(
            text.indexOf(PromptBuilder.Scaffold.SECTION_STATE) <
                text.indexOf(PromptBuilder.Scaffold.SECTION_VOICE)
        )
    }

    @Test
    fun `整份提示词里心情只写一次 且没有我的情绪是式自述`() {
        val text = PromptBuilder.system(
            inputs(
                mood = MoodReading(label = "开心", intensity = 3, valence = 1, arousal = 3, dominance = 4),
                // 即使调用方两个都传了，也只渲染多维那一段，不能出现两遍心情。
                moodBlock = "\n\n## 你现在的心情\n开心（强度 3/5，明显）。"
            )
        )
        assertEquals("心情只该出现一次", 1, Regex("心情：").findAll(text).count())
        // 「我的情绪是」只允许出现在「不要说…」这条禁令里，不能出现在任何示范句里。
        val hits = Regex("我的情绪是").findAll(text).toList()
        assertEquals("只该出现一次（作为禁令的反例）", 1, hits.size)
        val before = text.substring((hits.first().range.first - 12).coerceAtLeast(0), hits.first().range.first)
        assertTrue("出现的那一次必须是在说「不要这样说」", before.contains("不要说"))
    }

    @Test
    fun `没有多维心情时退回旧的单行心情 保持向后兼容`() {
        val text = PromptBuilder.system(inputs(moodBlock = "\n\n## 你现在的心情\n有点累（强度 3/5，明显）。"))
        assertTrue(text.contains("心情：有点累（强度 3/5）。"))
        assertFalse("没有多维数据时不该硬凑维度", text.contains("愉快度"))
    }

    // ------------------------------------------------------------- 反 AI 味

    @Test
    fun `反 AI 味段存在且规则齐全`() {
        val text = PromptBuilder.system(inputs(mood = MoodReading.of(MoodState())))
        assertTrue("要有独立的一段", text.contains(PromptBuilder.SECTION_ANTI_AI))
        val section = text.substringAfter(PromptBuilder.SECTION_ANTI_AI)
            .substringBefore(PromptBuilder.Scaffold.SECTION_BOUNDARIES)
        assertTrue("不要每句反问", section.contains("反问"))
        assertTrue("不要总结对方", section.contains("总结对方"))
        assertTrue("不要列点", section.contains("列点"))
        assertTrue("不要总安慰/认同", section.contains("安慰"))
        assertFalse("必须允许沉默", !section.contains("不回"))
        assertTrue("不重复口头禅", section.contains("口头禅"))
        assertTrue("不主动给清单/心理分析", section.contains("心理分析"))
        assertTrue("不知道就说不知道", section.contains("不知道就说不知道"))
        assertTrue("可以沉默要指向 stay_silent 工具", section.contains(ToolCatalog.STAY_SILENT))
    }

    @Test
    fun `反 AI 味段在边界之前 边界仍然是最后一段`() {
        val text = PromptBuilder.system(inputs(mood = MoodReading.of(MoodState())))
        val anti = text.indexOf(PromptBuilder.SECTION_ANTI_AI)
        val boundaries = text.indexOf(PromptBuilder.Scaffold.SECTION_BOUNDARIES)
        assertTrue(anti in 0..<boundaries)
        assertFalse(
            "边界之后不该再有段",
            text.substringAfter(PromptBuilder.Scaffold.SECTION_BOUNDARIES).contains("\n# ")
        )
    }

    @Test
    fun `反 AI 味段不进入 base 只进完整 system`() {
        // base() 给主动思考/人格卡预览用，那里没有对话搭档，不该背一份「不要总结对方」。
        val base = PromptBuilder.base(inputs(mood = MoodReading.of(MoodState())))
        assertFalse(base.contains(PromptBuilder.SECTION_ANTI_AI))
        assertEquals(
            "反 AI 味段是固定文案，不随输入变化",
            PromptBuilder.antiAiSection(),
            PromptBuilder.antiAiSection()
        )
    }
}
