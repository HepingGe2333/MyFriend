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

    /** 反 AI 段的正文：到下一段（语言段，没有语言段时是边界段）为止。 */
    private fun antiAiBody(text: String): String {
        val rest = text.substringAfter(PromptBuilder.SECTION_ANTI_AI)
        val cut = listOf(PromptContract.Section.LANGUAGE, PromptBuilder.Scaffold.SECTION_BOUNDARIES)
            .map { rest.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: rest.length
        return rest.take(cut)
    }

    @Test
    fun `反 AI 味段存在且就是 core 五条`() {
        val text = PromptBuilder.system(inputs(mood = MoodReading.of(MoodState())))
        assertTrue("要有独立的一段", text.contains(PromptBuilder.SECTION_ANTI_AI))
        val section = antiAiBody(text)
        // `spec/context-profile/README.md`：只保留不反问/不总结/不列点/不总安慰/不旁白五条，逐字固定。
        val rules = section.lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ") }
        assertEquals("反 AI 段恰好五条（core）", 5, rules.size)
        assertEquals(PromptBuilder.ANTI_AI_RULES, rules)
        rules.forEach { assertTrue("每条都要逐字进提示词：$it", text.contains("- $it")) }
        // 五条各覆盖一个禁忌。
        assertTrue(rules[0].contains("反问"))
        assertTrue(rules[1].contains("总结对方"))
        assertTrue(rules[2].contains("列点"))
        assertTrue(rules[3].contains("安慰"))
        assertTrue("可以沉默要指向 stay_silent 工具", rules[3].contains(ToolCatalog.STAY_SILENT))
        assertTrue("第五条是旁白/舞台说明", rules[4].contains("旁白") && rules[4].contains("舞台说明"))
    }

    @Test
    fun `删掉的两条反 AI 规则不再出现`() {
        val text = PromptBuilder.system(inputs(mood = MoodReading.of(MoodState())))
        // 「句子可以短、可以不完整」与「不主动给建议」两条在 0.15 被删（Bench v2 core profile）。
        assertFalse("不再要求句子短/不完整", text.contains("句子可以短、可以不完整"))
        assertFalse("不再写「不主动给建议」", text.contains("不主动给建议"))
        // 「心理分析」只剩边界段那一条（「不要心理分析对方」），不再是反 AI 段的独立规则。
        assertFalse("反 AI 段里不该再有心理分析", antiAiBody(text).contains("心理分析"))
    }

    @Test
    fun `英文人格的反 AI 也是五条 且与中文一一对应`() {
        val en = PromptBuilder.antiAiSection(PromptLanguage.EN)
        assertTrue("英文人格要有英文标题", en.startsWith("# Don't sound like an AI"))
        val rules = en.lines().drop(1).filter { it.startsWith("- ") }.map { it.removePrefix("- ") }
        assertEquals("英文人格也恰好五条", 5, rules.size)
        assertEquals(PromptBuilder.EN_ANTI_AI_RULES, rules)
        assertTrue("不反问", rules[0].contains("question"))
        assertTrue("不总结", rules[1].contains("summarize"))
        assertTrue("不列点", rules[2].contains("bullet lists"))
        assertTrue("不总安慰", rules[3].contains("comfort"))
        assertTrue("不旁白", rules[4].contains("narration"))
        assertTrue("可以沉默指向 stay_silent", rules[3].contains("stay_silent"))
    }

    @Test
    fun `反 AI 味段在工具之后 边界仍然是最后一段`() {
        val text = PromptBuilder.system(inputs(mood = MoodReading.of(MoodState())))
        val anti = text.indexOf(PromptBuilder.SECTION_ANTI_AI)
        val boundaries = text.indexOf(PromptBuilder.Scaffold.SECTION_BOUNDARIES)
        val tools = text.indexOf("# 你能做什么")
        assertTrue("反 AI 在工具之后（state_first profile）", tools in 0..<anti)
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
