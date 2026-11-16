package com.yap.app.data.ai

import com.yap.app.domain.SpeechStyle
import com.yap.app.domain.SpeechStyleCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 话风总结（规范 §3 / §6.1）。
 *
 * 用假 LLM 注入，把「正常 JSON / markdown 代码块 / 缺字段 / 坏 JSON / 没配置」逐条跑出来。
 * 另外两条契约：
 * 1. 原文只进这一次请求，**不落库、不写日志、不进备份**（源码里不能出现 DAO/Entity/Log）；
 * 2. 失败时走 §3.2 的本地兜底，并明确标记 `heuristic`。
 */
class SpeechStyleSummarizerTest {

    private class FakeLlm(private val reply: String?) : JsonLlmClient {
        var lastUser: String? = null
        var calls: Int = 0
        override suspend fun completeJson(
            system: String,
            user: String,
            configId: String?,
            kind: String,
            temperature: Double,
            maxOutputTokens: Int
        ): String? {
            calls++
            lastUser = user
            return reply
        }
    }

    private val sample = """
        林深：在忙，晚点说
        林深：这个方案又被打回来了，烦
        林深：你先睡吧，不用等我
        林深：嗯，知道了
        林深：别想太多，明天再说
        林深：我到了，楼下买了点吃的
    """.trimIndent()

    private fun summarize(reply: String?): SpeechStyleResult = runBlocking {
        SpeechStyleSummarizer(FakeLlm(reply)).summarize(sample)
    }

    @Test
    fun `正常 JSON 走模型结果`() {
        val json = """
            {"tone":"简短直接，偏冷淡务实","phrases":["在忙，晚点说","嗯，知道了"],
             "habits":["句子很短"],"avoid":["长篇大论"]}
        """.trimIndent()
        val result = summarize(json)
        assertEquals(SpeechStyleSource.LLM, result.source)
        assertEquals("简短直接，偏冷淡务实", result.style.tone)
        assertEquals(2, result.style.phrases.size)
        assertFalse(result.isHeuristic)
    }

    @Test
    fun `带 markdown 代码块的回复也能解析`() {
        val reply = """
            好的，这是总结：
            ```json
            {"tone":"冷","phrases":["嗯"],"habits":["短"],"avoid":["长"]}
            ```
        """.trimIndent()
        val result = summarize(reply)
        assertEquals(SpeechStyleSource.LLM, result.source)
        assertEquals("冷", result.style.tone)
        assertEquals(listOf("嗯"), result.style.phrases)
    }

    @Test
    fun `缺字段按空数组处理`() {
        val result = summarize("""{"tone":"简短"}""")
        assertEquals(SpeechStyleSource.LLM, result.source)
        assertTrue(result.style.phrases.isEmpty())
        assertTrue(result.style.habits.isEmpty())
        assertTrue(result.style.avoid.isEmpty())
    }

    @Test
    fun `坏 JSON 走本地兜底并标记 heuristic`() {
        val result = summarize("这不是 JSON")
        assertEquals(SpeechStyleSource.HEURISTIC, result.source)
        assertEquals(SpeechStyleHeuristicTone, result.style.tone)
        assertEquals(3, result.style.avoid.size)
        assertTrue(result.isHeuristic)
    }

    @Test
    fun `没有配置时走本地兜底`() {
        val result = summarize(null)
        assertEquals(SpeechStyleSource.HEURISTIC, result.source)
        assertEquals(SpeechStyleHeuristicTone, result.style.tone)
        assertTrue(result.style.avoid.isNotEmpty())
    }

    @Test
    fun `空输入不调用模型`() {
        val fake = FakeLlm("""{"tone":"x"}""")
        val result = runBlocking { SpeechStyleSummarizer(fake).summarize("   ") }
        assertEquals(SpeechStyleSource.HEURISTIC, result.source)
        assertEquals(0, fake.calls)
    }

    @Test
    fun `模型拿到的是原文 结果里不会把原文当成风格存下来`() {
        val fake = FakeLlm("""{"tone":"简短","phrases":[],"habits":[],"avoid":[]}""")
        runBlocking { SpeechStyleSummarizer(fake).summarize(sample) }
        assertTrue(fake.lastUser!!.contains("在忙，晚点说"))
        // 结果只包含总结后的风格，不包含聊天原文的整行。
        val result = summarize("""{"tone":"简短","phrases":[],"habits":[],"avoid":[]}""")
        assertFalse(result.toString().contains("楼下买了点吃的"))
    }

    @Test
    fun `原文不落库不写日志不进备份 源码契约`() {
        val source = File("src/main/java/com/yap/app/data/ai/SpeechStyleSummarizer.kt").let {
            if (it.exists()) it else File("../src/main/java/com/yap/app/data/ai/SpeechStyleSummarizer.kt")
        }
        assertTrue("找不到 SpeechStyleSummarizer.kt", source.exists())
        val text = source.readText()
        listOf("Log.", "println", "Dao", "Entity", "Backup", "Preferences").forEach { marker ->
            assertFalse("总结器里不该出现 $marker（原文只用于这一次请求）", text.contains(marker))
        }
        // 结果结构里只有风格字段，没有「原文」字段。
        assertFalse(SpeechStyleCodec.encode(SpeechStyle(tone = "简短")).contains("sample"))
    }

    private companion object {
        val SpeechStyleHeuristicTone = com.yap.app.domain.SpeechStyleHeuristic.TONE
    }
}
