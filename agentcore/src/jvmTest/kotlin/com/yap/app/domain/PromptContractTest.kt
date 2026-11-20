package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享提示词常量（0.15 下沉）：标题、工具段格式、心情、反 AI、边界、备忘录、心理活动、时间格式。
 *
 * 每个断言都对着一个**现有实现**：六段标题来自 [PersonaPromptScaffold]，边界来自
 * `scaffold.json`，反 AI 七条来自 Android/iOS 的同名字符串。迁移时这些值不许变。
 */
class PromptContractTest {

    // ------------------------------------------------------------------ 标题

    @Test
    fun `six scaffold titles delegate to the existing shared source`() {
        assertEquals(com.yap.app.domain.PersonaPromptScaffold.Section.IDENTITY, PromptContract.Section.IDENTITY)
        assertEquals(com.yap.app.domain.PersonaPromptScaffold.Section.WORLD, PromptContract.Section.WORLD)
        assertEquals(com.yap.app.domain.PersonaPromptScaffold.Section.STATE, PromptContract.Section.STATE)
        assertEquals(com.yap.app.domain.PersonaPromptScaffold.Section.VOICE, PromptContract.Section.VOICE)
        assertEquals(com.yap.app.domain.PersonaPromptScaffold.Section.RELATIONSHIP, PromptContract.Section.RELATIONSHIP)
        assertEquals(com.yap.app.domain.PersonaPromptScaffold.Section.BOUNDARIES, PromptContract.Section.BOUNDARIES)
    }

    @Test
    fun `new titles match the spec wording`() {
        assertEquals("# 你能做什么", PromptContract.Section.CAPABILITIES)
        assertEquals("# 你的心情", PromptContract.Section.MOOD)
        assertEquals("# 别像 AI", PromptContract.Section.ANTI_AI)
        assertEquals("# 你刚才心里想", PromptContract.Section.MENTAL_ACTIVITY)
        assertEquals("# 你的备忘录", PromptContract.Section.MEMO)
        assertEquals("## 语言", PromptContract.Section.LANGUAGE)
        assertEquals(12, PromptContract.Section.all.size)
        assertEquals(
            PromptContract.Section.CAPABILITIES,
            PromptContract.Section.all.getValue("capabilities")
        )
    }

    // ------------------------------------------------------------------ 工具能力段

    @Test
    fun `tool signature puts required before optional with question marks`() {
        assertEquals(
            "block_user(hours, reason?)",
            PromptContract.ToolCapability.signature(
                name = " block_user ",
                required = listOf(" hours "),
                optional = listOf("reason")
            )
        )
        assertEquals(
            "recall(query)",
            PromptContract.ToolCapability.signature("recall", required = listOf("query"))
        )
        // 没有参数时不印空括号；空参数名被丢掉。
        assertEquals("stay_silent", PromptContract.ToolCapability.signature("stay_silent"))
        assertEquals(
            "tool(hours)",
            PromptContract.ToolCapability.signature("tool", listOf("", "hours"), listOf("  "))
        )
    }

    @Test
    fun `tool capability line matches the spec shape`() {
        assertEquals(
            "- recall(query)：在记忆里找相关的事\n  · 区别：查的是长期记忆",
            PromptContract.ToolCapability.line(
                signature = "recall(query)",
                description = "在记忆里找相关的事",
                contrast = "查的是长期记忆"
            )
        )
        // 没有区别句时不加第二行；没有用途时不印空冒号。
        assertEquals(
            "- recall(query)：在记忆里找相关的事",
            PromptContract.ToolCapability.line("recall(query)", "在记忆里找相关的事", "  ")
        )
        assertEquals("- stay_silent", PromptContract.ToolCapability.line("stay_silent", ""))
    }

    @Test
    fun `tool capability section starts with the title`() {
        val section = PromptContract.ToolCapability.section(
            listOf(
                PromptContract.ToolCapability.line("recall(query)", "找记忆"),
                PromptContract.ToolCapability.line("stay_silent", "只读不回")
            )
        )
        assertEquals("# 你能做什么", section.lineSequence().first())
        assertTrue(section.contains("- recall(query)：找记忆"))
        assertTrue(section.contains("- stay_silent：只读不回"))
    }

    // ------------------------------------------------------------------ 心情

    @Test
    fun `mood line clamps intensity and trims label`() {
        assertEquals("心情：有点累（强度 3/5）。", PromptContract.Mood.line(" 有点累 ", 3))
        assertEquals("心情：开心（强度 1/5）。", PromptContract.Mood.line("开心", 0))
        assertEquals("心情：开心（强度 5/5）。", PromptContract.Mood.line("开心", 9))
    }

    @Test
    fun `mood dimensions clamp to physical ranges`() {
        assertEquals(
            "愉快度 -2 / 唤醒度 5/5 / 掌控感 5/5。",
            PromptContract.Mood.dimensionsLine(valence = -9, arousal = 9, dominance = 99)
        )
        assertEquals(
            "愉快度 1 / 唤醒度 2/5 / 掌控感 3/5。",
            PromptContract.Mood.dimensionsLine(valence = 1, arousal = 2, dominance = 3)
        )
    }

    // ------------------------------------------------------------------ 反 AI / 边界

    @Test
    fun `anti ai rules keep the seven existing sentences`() {
        val rules = PromptContract.AntiAi.RULES
        assertEquals(7, rules.size)
        assertEquals("不要每句话都反问；不需要每轮都把话题抛回去。", rules.first())
        assertEquals(
            "不想说话时调用 stay_silent（只已读不回），不要用文字说「我不回」；对方在说重要的事时不要用。",
            rules.last()
        )
        val section = PromptContract.AntiAi.section()
        assertEquals("# 别像 AI", section.lineSequence().first())
        assertEquals(7, section.lineSequence().count { it.startsWith("- ") })
    }

    @Test
    fun `boundaries reuse the scaffold rules verbatim`() {
        assertEquals(
            com.yap.app.domain.PersonaPromptScaffold.Line.boundaryRules,
            PromptContract.Boundaries.RULES
        )
        assertEquals(4, PromptContract.Boundaries.RULES.size)
        assertEquals("# 边界", PromptContract.Boundaries.section().lineSequence().first())
    }

    // ------------------------------------------------------------------ 备忘录 / 心理活动

    @Test
    fun `memo aliases memory contract titles and limits`() {
        assertEquals("# 你的备忘录", PromptContract.Memo.TITLE)
        assertEquals("## 长期记忆", PromptContract.Memo.LONG_TITLE)
        assertEquals("## 短期记忆", PromptContract.Memo.SHORT_TITLE)
        assertEquals("## 对话主题", PromptContract.Memo.TOPIC_TITLE)
        assertEquals(500, PromptContract.Memo.LONG_MAX_CHARS)
        assertEquals(200, PromptContract.Memo.SHORT_MAX_CHARS)
        assertEquals(120, PromptContract.Memo.TOPIC_SEGMENT_MAX_CHARS)
        assertEquals(12, PromptContract.Memo.TOPIC_MAX_SEGMENTS)
        // 委托给 MemoryContract，不产生第二份拼装逻辑。
        assertEquals(
            MemoryContract.Injection.notes(longTerm = "L", shortTerm = "S"),
            PromptContract.Memo.block(longTerm = "L", shortTerm = "S")
        )
    }

    @Test
    fun `mental activity flattens and caps the thought at sixty chars`() {
        assertEquals("a b", PromptContract.MentalActivity.sanitizeThought("  a\n b  "))
        assertEquals(
            "你刚才心里想：a b",
            PromptContract.MentalActivity.line(" a\nb ")
        )
        assertEquals(60, PromptContract.MentalActivity.sanitizeThought("字".repeat(100)).length)
        assertNull(PromptContract.MentalActivity.line("   "))
        assertEquals("", PromptContract.MentalActivity.block(""))
        val block = PromptContract.MentalActivity.block("有点烦")
        assertEquals("# 你刚才心里想", block.lineSequence().first())
        assertTrue(block.contains("你刚才心里想：有点烦"))
    }

    // ------------------------------------------------------------------ 时间

    @Test
    fun `weekday names are monday based and out of range is null`() {
        assertEquals("周一", PromptContract.Time.weekdayZh(0))
        assertEquals("周日", PromptContract.Time.weekdayZh(6))
        assertNull(PromptContract.Time.weekdayZh(-1))
        assertNull(PromptContract.Time.weekdayZh(7))
        assertEquals(7, PromptContract.Time.WEEKDAYS_ZH.size)
    }

    @Test
    fun `zone offset label matches the spec UTC plus minus HH mm form`() {
        assertEquals("UTC+08:00", PromptContract.Time.zoneOffsetLabel(8 * 60))
        assertEquals("UTC-05:30", PromptContract.Time.zoneOffsetLabel(-(5 * 60 + 30)))
        assertEquals("UTC", PromptContract.Time.zoneOffsetLabel(0))
        assertEquals("UTC+01:30", PromptContract.Time.zoneOffsetLabel(90))
        // 地球上不会有 33 小时的时区：夹到 ±18 小时，不印出 33。
        assertEquals("UTC+18:00", PromptContract.Time.zoneOffsetLabel(33 * 60))
        assertEquals("UTC-18:00", PromptContract.Time.zoneOffsetLabel(-33 * 60))
    }

    @Test
    fun `state line and clock text match existing formatting`() {
        assertEquals("现在是周三 19:40。", PromptContract.Time.stateLine(" 周三 ", "19:40 "))
        assertEquals(
            com.yap.app.domain.PersonaPromptScaffold.Line.STATE_LINE
                .replace("{weekday}", "周三")
                .replace("{time}", "19:40"),
            PromptContract.Time.stateLine("周三", "19:40")
        )
        assertEquals(
            "2026-11-18 周三 19:40（UTC+08:00）",
            PromptContract.Time.clockText("2026-11-18", "周三", "19:40", 8 * 60)
        )
        assertEquals("HH:mm", PromptContract.Time.HH_MM_PATTERN)
        assertEquals("yyyy-MM-dd", PromptContract.Time.DATE_PATTERN)
        assertEquals("UTC", PromptContract.Time.UTC_PREFIX)
    }
}
