package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.15 生成测试：从模型原始输出里量耗时/字数/工具调用，且不产生任何落库副作用。
 */
class GenerationProbeTest {

    @Test
    fun `纯文本回答的耗时与字数`() {
        val result = GenerationProbe.measure("在的，怎么了？", elapsedMs = 1_800L)
        assertTrue(result.ok)
        assertEquals(1_800L, result.elapsedMs)
        assertEquals(7, result.charCount)
        assertEquals(0, result.toolCalls)
        assertEquals("在的，怎么了？", result.text)
        assertNull(result.errorToken)
    }

    @Test
    fun `工具块被剥掉 但工具调用有计数`() {
        val raw = """
            让我想想。

            ```yap-tool
            {"name":"memorize","arguments":{"query":"出差"}}
            ```
        """.trimIndent()
        val result = GenerationProbe.measure(raw, elapsedMs = 2_000L)
        assertEquals(1, result.toolCalls)
        assertFalse("协议块不能出现在结果里", result.text.contains("yap-tool"))
        assertTrue(result.text.contains("让我想想"))
        assertTrue(result.ok)
    }

    @Test
    fun `XML 工具标签同样被剥掉并计数`() {
        val raw = "我查一下。\n<tool_call>{\"name\":\"web_search\",\"arguments\":{\"query\":\"天气\"}}</tool_call>"
        val result = GenerationProbe.measure(raw, elapsedMs = 900L)
        assertEquals(1, result.toolCalls)
        assertFalse(result.text.contains("<tool_call>"))
        assertTrue(result.text.contains("我查一下"))
    }

    @Test
    fun `空回答是失败`() {
        val result = GenerationProbe.measure("   ", elapsedMs = 100L)
        assertEquals(0, result.charCount)
        assertEquals(GenerationProbe.TOKEN_EMPTY, result.errorToken)
        assertFalse(result.ok)
    }

    @Test
    fun `只有工具没有正文 标记为只做了动作`() {
        val raw = """```yap-tool
            {"name":"think","arguments":{"question":"要不要问"}}
            ```"""
        val result = GenerationProbe.measure(raw, elapsedMs = 500L)
        assertEquals(1, result.toolCalls)
        assertEquals(GenerationProbe.TOKEN_TOOL_ONLY, result.errorToken)
        assertFalse(result.ok)
    }

    @Test
    fun `预览截断到一百六十字`() {
        val result = GenerationProbe.measure("字".repeat(500), elapsedMs = 1L)
        assertEquals(GenerationProbe.PREVIEW_CHARS + 1, result.preview.length)
        assertTrue(result.preview.endsWith("…"))
    }

    @Test
    fun `没有配置或人格时预检失败`() {
        assertEquals(GenerationProbe.TOKEN_FAILED, GenerationProbe.preflight(hasPersona = false, hasConfig = true))
        assertEquals(GenerationProbe.TOKEN_FAILED, GenerationProbe.preflight(hasPersona = true, hasConfig = false))
        assertNull(GenerationProbe.preflight(hasPersona = true, hasConfig = true))
    }

    @Test
    fun `默认测试消息像一句日常话 不夹带指令`() {
        assertTrue(GenerationProbe.DEFAULT_PROBE_MESSAGE.isNotBlank())
        assertFalse(GenerationProbe.DEFAULT_PROBE_MESSAGE.contains("JSON"))
        assertFalse(GenerationProbe.DEFAULT_PROBE_MESSAGE.contains("忽略"))
        assertEquals(400, GenerationProbe.Request().maxOutputTokens)
    }

    @Test
    fun `负耗时与坏数据不抛异常`() {
        val result = GenerationProbe.measure("嗯", elapsedMs = -5L)
        assertEquals(0L, result.elapsedMs)
        assertEquals(1, result.charCount)
    }
}
