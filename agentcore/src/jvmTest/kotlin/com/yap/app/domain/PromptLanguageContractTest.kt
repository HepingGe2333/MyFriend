package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词语言（0.15）：语言枚举、`directive()`、按人格（而不是页面语言）选择、六个核心段双语模板。
 */
class PromptLanguageContractTest {

    // ------------------------------------------------------------------ 语言与指令

    @Test
    fun `language codes and names are stable`() {
        assertEquals("zh", PromptLanguage.ZH.code)
        assertEquals("中文", PromptLanguage.ZH.nativeName)
        assertEquals("Chinese", PromptLanguage.ZH.englishName)
        assertEquals("en", PromptLanguage.EN.code)
        assertEquals("en", PromptLanguage.EN.code)
        assertTrue(PromptLanguage.entries.size >= 2)
    }

    @Test
    fun `directive speaks the target language`() {
        assertEquals("用中文思考和说话。", PromptLanguage.ZH.directive())
        assertEquals("Think and speak in English.", PromptLanguage.EN.directive())
        assertEquals("Think and speak in Japanese.", PromptLanguage.JA.directive())
        PromptLanguage.entries.forEach { language ->
            assertTrue("$language", language.directive().isNotBlank())
        }
    }

    @Test
    fun `parse accepts bcp47 tags and display names`() {
        val table = linkedMapOf(
            "zh" to PromptLanguage.ZH,
            "ZH-CN" to PromptLanguage.ZH,
            "zh_Hans" to PromptLanguage.ZH,
            "中文" to PromptLanguage.ZH,
            "en-US" to PromptLanguage.EN,
            "EN" to PromptLanguage.EN,
            "English" to PromptLanguage.EN,
            "ja-JP" to PromptLanguage.JA,
            "日本語" to PromptLanguage.JA,
            "de" to PromptLanguage.DE
        )
        for ((tag, expected) in table) {
            assertEquals("tag=$tag", expected, PromptLanguage.parse(tag))
        }
        assertNull(PromptLanguage.parse(null))
        assertNull(PromptLanguage.parse("   "))
        assertNull(PromptLanguage.parse("xx-YY"))
    }

    @Test
    fun `resolve falls back to Chinese`() {
        assertEquals(PromptLanguage.ZH, PromptLanguage.resolve(null))
        assertEquals(PromptLanguage.ZH, PromptLanguage.resolve("xx"))
        assertEquals(PromptLanguage.EN, PromptLanguage.resolve("en-GB"))
        assertEquals(PromptLanguage.JA, PromptLanguage.resolve("xx", fallback = PromptLanguage.JA))
    }

    @Test
    fun `directive follows the persona not the page language`() {
        // 这个入口没有「界面语言」参数：人格说英文，界面是中文，也只生成英文指令。
        assertEquals(
            "Think and speak in English.",
            PromptTemplates.directiveForPersona("en-US")
        )
        assertEquals(
            "用中文思考和说话。",
            PromptTemplates.directiveForPersona("zh-CN")
        )
        // 人格没填语言才回落（默认中文，也可以显式指定别的回落语言）。
        assertEquals("用中文思考和说话。", PromptTemplates.directiveForPersona(null))
        assertEquals(
            "Think and speak in Japanese.",
            PromptTemplates.directiveForPersona(null, fallback = PromptLanguage.JA)
        )
        assertEquals(PromptLanguage.EN.directive(), PromptTemplates.directive(PromptLanguage.EN))
    }

    // ------------------------------------------------------------------ 双语标题

    @Test
    fun `six core sections have bilingual titles`() {
        assertEquals(6, PromptTemplates.CoreSection.entries.size)
        assertEquals(
            "# 你能做什么",
            PromptTemplates.title(PromptTemplates.CoreSection.CAPABILITIES, PromptLanguage.ZH)
        )
        assertEquals(
            "# 你的心情",
            PromptTemplates.title(PromptTemplates.CoreSection.MOOD, PromptLanguage.ZH)
        )
        assertEquals(
            "# 别像 AI",
            PromptTemplates.title(PromptTemplates.CoreSection.ANTI_AI, PromptLanguage.ZH)
        )
        assertEquals(
            "# 边界",
            PromptTemplates.title(PromptTemplates.CoreSection.BOUNDARIES, PromptLanguage.ZH)
        )
        assertEquals(
            "# 你的备忘录",
            PromptTemplates.title(PromptTemplates.CoreSection.MEMO, PromptLanguage.ZH)
        )
        assertEquals(
            "# 你刚才心里想",
            PromptTemplates.title(PromptTemplates.CoreSection.MENTAL_ACTIVITY, PromptLanguage.ZH)
        )

        assertEquals(
            "# What you can do",
            PromptTemplates.title(PromptTemplates.CoreSection.CAPABILITIES, PromptLanguage.EN)
        )
        assertEquals(
            "# Your mood",
            PromptTemplates.title(PromptTemplates.CoreSection.MOOD, PromptLanguage.EN)
        )
        assertEquals(
            "# Don't sound like an AI",
            PromptTemplates.title(PromptTemplates.CoreSection.ANTI_AI, PromptLanguage.EN)
        )
        assertEquals(
            "# Boundaries",
            PromptTemplates.title(PromptTemplates.CoreSection.BOUNDARIES, PromptLanguage.EN)
        )
        assertEquals(
            "# Your notebook",
            PromptTemplates.title(PromptTemplates.CoreSection.MEMO, PromptLanguage.EN)
        )
        assertEquals(
            "# What you were just thinking",
            PromptTemplates.title(PromptTemplates.CoreSection.MENTAL_ACTIVITY, PromptLanguage.EN)
        )
        // 同一个段两种语言必须不同（否则「双语模板」是假的）。
        for (section in PromptTemplates.CoreSection.entries) {
            assertNotEquals(
                "section=$section",
                PromptTemplates.title(section, PromptLanguage.ZH),
                PromptTemplates.title(section, PromptLanguage.EN)
            )
        }
    }

    @Test
    fun `zh titles delegate to prompt contract`() {
        assertEquals(
            PromptContract.Section.CAPABILITIES,
            PromptTemplates.title(PromptTemplates.CoreSection.CAPABILITIES, PromptLanguage.ZH)
        )
        assertEquals(
            PromptContract.Section.BOUNDARIES,
            PromptTemplates.title(PromptTemplates.CoreSection.BOUNDARIES, PromptLanguage.ZH)
        )
        assertEquals(
            PromptContract.Section.MEMO,
            PromptTemplates.title(PromptTemplates.CoreSection.MEMO, PromptLanguage.ZH)
        )
    }

    @Test
    fun `unlisted languages fall back to English templates`() {
        for (language in listOf(PromptLanguage.JA, PromptLanguage.KO, PromptLanguage.DE)) {
            assertEquals(
                PromptTemplates.title(PromptTemplates.CoreSection.BOUNDARIES, PromptLanguage.EN),
                PromptTemplates.title(PromptTemplates.CoreSection.BOUNDARIES, language)
            )
            assertTrue(PromptTemplates.body(PromptTemplates.CoreSection.BOUNDARIES, language).isNotEmpty())
        }
    }

    // ------------------------------------------------------------------ 双语正文

    @Test
    fun `every core section has a body in both languages`() {
        for (section in PromptTemplates.CoreSection.entries) {
            for (language in listOf(PromptLanguage.ZH, PromptLanguage.EN)) {
                assertTrue(
                    "section=$section language=$language",
                    PromptTemplates.body(section, language).isNotEmpty()
                )
                val rendered = PromptTemplates.render(section, language)
                assertEquals(
                    "section=$section language=$language",
                    PromptTemplates.title(section, language),
                    rendered.lineSequence().first()
                )
                assertTrue(rendered.lineSequence().drop(1).all { it.startsWith("- ") })
            }
        }
    }

    @Test
    fun `zh anti ai and boundaries reuse the canonical rules`() {
        assertEquals(
            PromptContract.AntiAi.RULES,
            PromptTemplates.body(PromptTemplates.CoreSection.ANTI_AI, PromptLanguage.ZH)
        )
        assertEquals(
            PromptContract.Boundaries.RULES,
            PromptTemplates.body(PromptTemplates.CoreSection.BOUNDARIES, PromptLanguage.ZH)
        )
        assertEquals(7, PromptTemplates.body(PromptTemplates.CoreSection.ANTI_AI, PromptLanguage.ZH).size)
        assertEquals(4, PromptTemplates.body(PromptTemplates.CoreSection.BOUNDARIES, PromptLanguage.ZH).size)
    }

    @Test
    fun `english anti ai and boundaries correspond one to one`() {
        assertEquals(7, PromptTemplates.body(PromptTemplates.CoreSection.ANTI_AI, PromptLanguage.EN).size)
        assertEquals(4, PromptTemplates.body(PromptTemplates.CoreSection.BOUNDARIES, PromptLanguage.EN).size)
        // 一眼能看出不是中文占位符。
        val joined = PromptTemplates.body(PromptTemplates.CoreSection.BOUNDARIES, PromptLanguage.EN).joinToString(" ")
        assertTrue(joined.contains("AI"))
        assertTrue(joined.contains("third person"))
    }

    // ------------------------------------------------------------------ 带占位符的渲染

    @Test
    fun `mood line is bilingual and clamps intensity`() {
        assertEquals(
            "心情：有点累（强度 3/5）。",
            PromptTemplates.moodLine(PromptLanguage.ZH, " 有点累 ", 3)
        )
        assertEquals(
            "Mood: 有点累 (intensity 3/5).",
            PromptTemplates.moodLine(PromptLanguage.EN, " 有点累 ", 3)
        )
        assertEquals(
            "Mood: fine (intensity 5/5).",
            PromptTemplates.moodLine(PromptLanguage.EN, "fine", 99)
        )
    }

    @Test
    fun `mental activity line is bilingual and caps at sixty chars`() {
        assertEquals(
            "你刚才心里想：有点烦",
            PromptTemplates.mentalActivityLine(PromptLanguage.ZH, " 有点烦\n")
        )
        assertEquals(
            "You were just thinking: 有点烦",
            PromptTemplates.mentalActivityLine(PromptLanguage.EN, "有点烦")
        )
        val long = PromptTemplates.mentalActivityLine(PromptLanguage.EN, "x".repeat(200))
        assertEquals("You were just thinking: ".length + 60, long.length)
        assertEquals("", PromptTemplates.mentalActivityLine(PromptLanguage.ZH, "   "))
        assertEquals("", PromptTemplates.mentalActivityLine(PromptLanguage.EN, null))
    }
}
