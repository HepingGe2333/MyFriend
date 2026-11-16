package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 话风（规范 §3）。
 *
 * 覆盖：样例 JSON、渲染时常用语最多 6 条、缺字段 → 空数组、坏 JSON → null，
 * 以及无 API 时的本地兜底（去昵称前缀、频次 ≥2、平均句长/感叹号习惯、通用 avoid）。
 */
class SpeechStyleTest {

    private val sample = """
        {
          "tone": "简短直接，偏冷淡务实，常带一点烦躁",
          "phrases": ["在忙，晚点说", "别想太多", "你先睡吧", "嗯，知道了", "我到了", "烦"],
          "habits": ["句子很短", "少用感叹号"],
          "avoid": ["长篇大论", "书面化表达"]
        }
    """.trimIndent()

    @Test
    fun `样例能读出来`() {
        val style = SpeechStyleCodec.decode(sample) ?: error("样例读不出来")
        assertEquals("简短直接，偏冷淡务实，常带一点烦躁", style.tone)
        assertEquals(6, style.phrases.size)
        assertEquals(listOf("长篇大论", "书面化表达"), style.avoid)
        assertFalse(style.isEmpty())
    }

    @Test
    fun `渲染时常用语最多 6 条且明确不要每句都套`() {
        val style = SpeechStyleCodec.decode(sample)!!.copy(
            phrases = (1..8).map { "口头禅$it" }
        )
        val text = style.renderForPrompt()
        assertTrue(text.contains("最多带 1 处，不要每句都套"))
        assertTrue("常用语要带「」", text.contains("「口头禅1」"))
        assertTrue(text.contains("「口头禅6」"))
        assertFalse("第 7 条不该出现", text.contains("口头禅7"))
    }

    @Test
    fun `渲染出的每一行都来自脚手架`() {
        val style = SpeechStyleCodec.decode(sample)!!
        val scaffold = PromptBuilder.Scaffold
        val lines = style.renderForPrompt().lines()
        assertEquals("开头是脚手架的 voice_rules", scaffold.VOICE_RULES, lines.take(scaffold.VOICE_RULES.size))
        assertEquals("结尾是脚手架的 voice_no_narration", scaffold.VOICE_NO_NARRATION, lines.last())
        assertTrue(lines.contains(scaffold.VOICE_TONE.replace("{tone}", style.tone)))
        assertTrue(lines.contains(scaffold.VOICE_PHRASES.replace(
            "{phrases}",
            style.phrases.joinToString("、") { "「$it」" }
        )))
    }

    @Test
    fun `习惯与避免清单不进提示词 脚手架没有对应行`() {
        val style = SpeechStyleCodec.decode(sample)!!
        val text = style.renderForPrompt()
        assertFalse("不能自己加「习惯：」这种规范外的行", text.contains("习惯："))
        assertFalse("不能自己加「- 不要：」这种规范外的行", text.contains("- 不要："))
        // 数据本身仍然保存着，编辑器里能改。
        assertEquals(listOf("句子很短", "少用感叹号"), style.habits)
        assertEquals(listOf("长篇大论", "书面化表达"), style.avoid)
    }

    @Test
    fun `没有 tone 与常用语时只留脚手架规则`() {
        val text = SpeechStyle().renderForPrompt()
        val scaffold = PromptBuilder.Scaffold
        assertEquals(
            (scaffold.VOICE_RULES + scaffold.VOICE_NO_NARRATION).joinToString("\n"),
            text
        )
    }

    @Test
    fun `缺字段当空数组 坏 JSON 返回 null`() {
        val style = SpeechStyleCodec.decode("""{"tone":"简短"}""")!!
        assertEquals("简短", style.tone)
        assertTrue(style.phrases.isEmpty())
        assertTrue(style.habits.isEmpty())
        assertTrue(style.avoid.isEmpty())
        assertNull(SpeechStyleCodec.decode("bad"))
        assertNull(SpeechStyleCodec.decode(null))
        assertNull(SpeechStyleCodec.decodeStrict("""{"phrases":"not array"}"""))
    }

    @Test
    fun `编解码往返保留字段`() {
        val original = SpeechStyleCodec.decode(sample)!!
        assertEquals(original, SpeechStyleCodec.decode(SpeechStyleCodec.encode(original)))
    }

    // ------------------------------------------------------------ 本地兜底

    @Test
    fun `本地兜底去掉昵称前缀 频次至少两次才进常用语`() {
        val raw = """
            林深：在忙，晚点说
            林深：在忙，晚点说
            林深：你先睡吧
            林深：嗯，知道了
            林深：嗯，知道了
            我：好的
        """.trimIndent()
        val style = SpeechStyleHeuristic.summarize(raw)
        assertEquals("简短、口语", style.tone)
        assertEquals(listOf("在忙，晚点说", "嗯，知道了"), style.phrases)
        assertTrue(style.habits.size == 2)
        assertEquals(listOf("长篇大论", "书面化表达", "夸张语气"), style.avoid)
        assertFalse(style.isEmpty())
        // 不能把「林深」这个昵称带进常用语。
        assertTrue(style.phrases.none { it.contains("林深") })
    }

    @Test
    fun `本地兜底最多 12 条常用语`() {
        val raw = (1..20).joinToString("\n") { "林深：嗯$it" }
        val style = SpeechStyleHeuristic.summarize(raw)
        assertTrue("最多 12 条，实际 ${style.phrases.size}", style.phrases.size <= 12)
    }

    @Test
    fun `本地兜底对空输入也给出可用占位`() {
        val style = SpeechStyleHeuristic.summarize("")
        assertEquals("简短、口语", style.tone)
        assertTrue(style.phrases.isEmpty())
        assertEquals(3, style.avoid.size)
    }

    @Test
    fun `剥昵称前缀只认冒号前是称呼的行`() {
        assertEquals("在忙，晚点说", SpeechStyleHeuristic.stripSpeaker("林深：在忙，晚点说"))
        assertEquals("晚点说", SpeechStyleHeuristic.stripSpeaker("在忙：晚点说"))
        assertEquals("https://example.com", SpeechStyleHeuristic.stripSpeaker("https://example.com"))
        assertEquals("没有昵称", SpeechStyleHeuristic.stripSpeaker("没有昵称"))
    }
}
