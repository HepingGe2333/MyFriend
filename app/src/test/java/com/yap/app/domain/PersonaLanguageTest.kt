package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.15 人格语言：提示词的语言由**人格**决定，与 App 界面语言无关。
 */
class PersonaLanguageTest {

    private fun inputs(language: PromptLanguage) = PromptBuilder.Inputs(
        name = "阿澈",
        language = language,
        languageSection = "\n\n" + PromptBuilder.languageSectionFor(language.code)
    )

    @Test
    fun `语言标签宽容解析`() {
        assertEquals(PromptLanguage.ZH, PromptLanguage.parse("zh-CN"))
        assertEquals(PromptLanguage.ZH, PromptLanguage.parse("zh_Hans"))
        assertEquals(PromptLanguage.EN, PromptLanguage.parse("en-US"))
        assertEquals(PromptLanguage.JA, PromptLanguage.parse("ja"))
        assertEquals(PromptLanguage.ZH, PromptLanguage.parse("中文"))
        assertEquals(PromptLanguage.EN, PromptLanguage.parse("English"))
        assertEquals(PromptLanguage.EN, PromptLanguage.parse("EN"))
        assertEquals(null, PromptLanguage.parse("klingon"))
        assertEquals("认不出时回落中文", PromptLanguage.ZH, PromptLanguage.resolve("klingon"))
    }

    @Test
    fun `中文人格的指令是中文`() {
        val section = PromptBuilder.languageSectionFor(null)
        assertTrue(section.startsWith(PromptContract.Section.LANGUAGE))
        assertTrue(section.contains("用中文思考和说话。"))
    }

    @Test
    fun `英文人格的指令与标题都是英文`() {
        val section = PromptBuilder.languageSectionFor("en")
        assertEquals("## Language", section.lineSequence().first())
        assertTrue(section.contains("Think and speak in English."))
        assertFalse(section.contains("中文"))
    }

    @Test
    fun `系统提示词的规则段跟随人格语言`() {
        val zh = PromptBuilder.system(inputs(PromptLanguage.ZH))
        val en = PromptBuilder.system(inputs(PromptLanguage.EN))
        assertTrue(zh.contains(PromptBuilder.SECTION_ANTI_AI))
        assertTrue(zh.contains(PromptContract.Boundaries.TITLE))
        assertTrue(en.contains("# Don't sound like an AI"))
        assertTrue(en.contains("# Boundaries"))
        assertFalse("英文人格不该出现中文边界标题", en.contains(PromptContract.Boundaries.TITLE))
        // 身份/世界这些数据段仍是用户写的中文（翻译缓存的职责，不在这里偷换）。
        assertTrue(en.contains("# 你是谁"))
    }

    @Test
    fun `工具能力段按人格语言生成`() {
        val zh = ToolCatalog.capabilityPrompt(language = PromptLanguage.ZH)
        val en = ToolCatalog.capabilityPrompt(language = PromptLanguage.EN)
        assertTrue(zh.startsWith(PromptContract.ToolCapability.TITLE))
        assertTrue(en.startsWith("# What you can do"))
        // 工具清单一个都不能少（只是描述换语言）。
        ToolCatalog.all.forEach { spec ->
            assertTrue("英文能力段缺少 ${spec.name}", en.contains(spec.name))
        }
        assertTrue(en.contains("remember"))
        assertTrue(en.contains("memorize"))
    }

    @Test
    fun `提示词装配读的是人格字段 而不是界面语言`() {
        val source = File("src/main/java/com/yap/app/data/repo/ChatRepository.kt").readText()
        assertTrue("ChatRepository 必须按人格语言拼提示词", source.contains("PromptLanguage.resolve(persona.languageTag)"))
        assertTrue(source.contains("PromptBuilder.languageSectionFor(persona.languageTag)"))
        // 旧路径（跟随界面语言）不能再被用来拼聊天提示词。
        assertFalse(source.contains("prompt_language_directive"))
        assertFalse(source.contains("LanguageDirective.section("))
    }

    @Test
    fun `英文人格的段标题也本地化 但正文不翻译`() {
        val text = listOf(
            PromptBuilder.Scaffold.SECTION_IDENTITY,
            "你是阿澈本人。",
            PromptContract.Section.STATE,
            "现在是周五。",
            MemoryContract.Injection.TITLE,
            MemoryContract.Injection.LONG_TERM_TITLE,
            "- 他怕冷"
        ).joinToString("\n")
        val en = PromptBuilder.localizeSectionTitles(text, PromptLanguage.EN)
        assertTrue(en.contains("# Who you are"))
        assertTrue(en.contains("# Your state right now"))
        assertTrue(en.contains("# Your notebook"))
        assertTrue(en.contains("## Long-term memory"))
        // 正文（用户写的中文）保持原样。
        assertTrue(en.contains("你是阿澈本人。"))
        assertTrue(en.contains("- 他怕冷"))
        // 中文人格不做任何替换。
        assertEquals(text, PromptBuilder.localizeSectionTitles(text, PromptLanguage.ZH))
        // 只换整行标题，正文里的同名字样不误伤。
        val inline = "他在想「# 你是谁」这个问题"
        assertTrue(PromptBuilder.localizeSectionTitles(inline, PromptLanguage.EN).contains("# 你是谁"))
    }

    @Test
    fun `人格实体与保存链路都带 languageTag`() {
        val entity = File("src/main/java/com/yap/app/data/local/Entities.kt").readText()
        assertTrue(entity.contains("val languageTag: String? = null"))
        val repository = File("src/main/java/com/yap/app/data/repo/PersonaRepository.kt").readText()
        assertTrue(repository.contains("languageTag = com.yap.app.domain.PromptLanguage.parse(draft.languageTag)?.code"))
    }
}
