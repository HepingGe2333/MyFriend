package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「# 你能做什么」段与工具注册表的一致性契约（`docs/design/persona-evolution.md` §1）。
 *
 * 这一段的全部内容都由 [ToolCatalog.all] 生成，测试因此只需要盯两件事：
 *
 * 1. **集合一致**：段里出现的工具名 == 注册表里的工具名（关掉联网时 == 注册表减去联网工具）。
 *    手写工具清单最容易出的问题就是「加了工具、提示词忘了写」，模型于是永远不知道那只手存在；
 * 2. **该说清的区别都说了**：`set_activity` 不等于睡觉、`set_quiet_hours` 才是睡觉、
 *    `diary` 是自己的备忘、`update_persona` 只能有限地改人设。这些句子来自 [ToolSpec.contrast]，
 *    不是另一份手写文案——所以只断言「关键词存在」，不断言整句措辞。
 */
class ToolCapabilitySectionTest {

    /** 段里每一条工具行的名字（`- name：…` 的形状）。 */
    private fun namesIn(section: String): List<String> = section.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("- ") && it.contains("：") }
        .map { it.removePrefix("- ").substringBefore("：").trim() }
        .filter { it.isNotBlank() }
        .toList()

    @Test
    fun `段标题是你能做什么 并且由注册表生成`() {
        val section = ToolCatalog.capabilityPrompt()
        assertTrue("第一行必须是段标题", section.lineSequence().first().trim() == "# 你能做什么")
    }

    @Test
    fun `段内工具集合与注册表完全一致`() {
        val section = ToolCatalog.capabilityPrompt()
        assertEquals(
            "「你能做什么」段里的工具必须与 ToolCatalog.all 完全一致（多了会说出不存在的工具，少了模型不知道那只手）",
            ToolCatalog.all.map { it.name }.toSet(),
            namesIn(section).toSet()
        )
    }

    @Test
    fun `关掉联网时联网工具整条消失 并写清没有联网`() {
        val section = ToolCatalog.capabilityPrompt(webEnabled = false)
        val names = namesIn(section).toSet()
        assertEquals(
            ToolCatalog.all.map { it.name }.toSet() - setOf(ToolCatalog.WEB_SEARCH, ToolCatalog.WEB_FETCH),
            names
        )
        assertTrue("要说清此刻没有联网能力，而不是勾引它去查", section.contains("没有联网能力"))
        assertTrue("不能留下一个会被误用的工具名", ToolCatalog.WEB_SEARCH !in names)
    }

    @Test
    fun `每个工具的用途都来自它的定义 不是另抄一份`() {
        val section = ToolCatalog.capabilityPrompt()
        ToolCatalog.all.forEach { spec ->
            assertTrue(
                "段里必须原样带上 ${spec.name} 的定义文案（用途只有一处真源）",
                section.contains(spec.description)
            )
        }
    }

    @Test
    fun `set_activity 是说我在做事 不是睡觉`() {
        val section = ToolCatalog.capabilityPrompt()
        val activity = contractOf(section, ToolCatalog.SET_ACTIVITY)
        assertTrue("活动段要写明「不是睡觉」：$activity", activity.contains("不等于睡觉"))
        assertTrue("要指向 set_quiet_hours：$activity", activity.contains(ToolCatalog.SET_QUIET_HOURS))
    }

    @Test
    fun `set_quiet_hours 才管睡觉与安静时段`() {
        val section = ToolCatalog.capabilityPrompt()
        val quiet = contractOf(section, ToolCatalog.SET_QUIET_HOURS)
        assertTrue("安静时段段要写明睡觉/不被打扰：$quiet", quiet.contains("睡觉"))
        assertTrue("要指向 set_activity：$quiet", quiet.contains(ToolCatalog.SET_ACTIVITY))
    }

    @Test
    fun `diary 是他自己的备忘本 不是关于对方的记忆`() {
        val section = ToolCatalog.capabilityPrompt()
        val diary = contractOf(section, ToolCatalog.DIARY)
        assertTrue("diary 段要写明是自己的备忘：$diary", diary.contains("备忘"))
        assertTrue("要和 remember 区分开：$diary", diary.contains("remember"))
    }

    @Test
    fun `update_persona 只能有限地改人设`() {
        val section = ToolCatalog.capabilityPrompt()
        val persona = contractOf(section, ToolCatalog.UPDATE_PERSONA)
        assertTrue("人设工具要有「有限制」的说法：$persona", persona.contains("改不了"))
        assertTrue("不能让人以为它能重写用户手写的内容：$persona", persona.contains("不会动"))
    }

    @Test
    fun `工具失败时诚实说不知道 不假装做过`() {
        val section = ToolCatalog.capabilityPrompt()
        assertTrue(section.contains("不要假装做过"))
    }

    @Test
    fun `不提不存在的能力 也不写表情贴纸这类没有注册的东西口语清单除外`() {
        // 这一条防的是「提示词里提到一个注册表里没有的工具」——模型会调用它然后每次都失败。
        val section = ToolCatalog.capabilityPrompt()
        listOf("sticker", "persona_patch", "diary_notes", "set_presence").forEach { fabricated ->
            assertFalse("段里不该出现未注册的工具名：$fabricated", namesIn(section).contains(fabricated))
        }
    }

    @Test
    fun `工具段折进系统提示词时位于关系段之后与边界之前`() {
        val section = ToolCatalog.capabilityPrompt()
        val text = PromptBuilder.system(
            PromptBuilder.Inputs(
                name = "阿深",
                now = 1_791_000_000_000L,
                capabilitiesSection = section
            )
        )
        val relationshipAt = text.indexOf(PromptBuilder.Scaffold.SECTION_RELATIONSHIP)
        val capabilitiesAt = text.indexOf("# 你能做什么")
        val boundariesAt = text.indexOf(PromptBuilder.Scaffold.SECTION_BOUNDARIES)
        assertTrue("关系段、工具段、边界段都要在", relationshipAt >= 0 && capabilitiesAt >= 0 && boundariesAt >= 0)
        assertTrue("工具段要在关系段之后", capabilitiesAt > relationshipAt)
        assertTrue("工具段要在边界之前（边界永远是最后一段）", capabilitiesAt < boundariesAt)
    }

    /** 某个工具那一行 + 它下面的「· 区别」行（最多两行，够断言了）。 */
    private fun contractOf(section: String, tool: String): String {
        val lines = section.lines()
        val index = lines.indexOfFirst { it.trimStart().startsWith("- $tool：") }
        assertTrue("段里缺少工具 $tool", index >= 0)
        return lines.drop(index).take(2).joinToString("\n")
    }
}
