package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.15 工具协议兼容：模型经常不用 ```yap-tool，而是写 XML 标签。
 *
 * 评测平台实测出现过 `<tool_call>…</tool_call>` / `<tool_code>` / `<tool>` 三种；
 * 解析器要把它们当成同一种协议块，并且**标签本身绝不进正文**（用户不该看到 XML）。
 */
class ToolXmlCompatTest {

    @Test
    fun `解析 tool_call 标签`() {
        val raw = """
            我先查一下。

            <tool_call>{"name":"web_search","arguments":{"query":"今天上海天气"}}</tool_call>
        """.trimIndent()
        val (text, calls) = ToolProtocol.extract(raw)
        assertEquals("我先查一下。", text)
        assertFalse(text.contains("tool_call"))
        assertEquals(1, calls.size)
        assertEquals(ToolCatalog.WEB_SEARCH, calls.single().name)
        assertEquals("今天上海天气", calls.single().argument("query"))
    }

    @Test
    fun `解析 tool_code 标签里的 fenced json`() {
        val raw = """
            好。

            <tool_code>
            ```json
            {"name":"memorize","arguments":{"query":"出差"}}
            ```
            </tool_code>
        """.trimIndent()
        val (text, calls) = ToolProtocol.extract(raw)
        assertEquals("好。", text)
        assertEquals(ToolCatalog.MEMORIZE, calls.single().name)
        assertEquals("出差", calls.single().argument("query"))
    }

    @Test
    fun `解析 tool 标签里先写名字再写参数`() {
        val raw = "等等我。<tool>remember\n{\"content\":\"他怕冷\"}</tool>"
        val (text, calls) = ToolProtocol.extract(raw)
        assertEquals("等等我。", text)
        assertEquals(ToolCatalog.REMEMBER, calls.single().name)
        assertEquals("他怕冷", calls.single().argument("content"))
    }

    @Test
    fun `函数式写法也认`() {
        val raw = "<tool_code>think({\"question\":\"要不要主动\"})</tool_code>"
        val (text, calls) = ToolProtocol.extract(raw)
        assertEquals("", text)
        assertEquals(ToolCatalog.THINK, calls.single().name)
        assertEquals("要不要主动", calls.single().argument("question"))
    }

    @Test
    fun `标签大小写与属性都认`() {
        val raw = "<TOOL_CALL name=\"x\">{\"tool\":\"set_mood\",\"args\":{\"mood\":\"累\"}}</TOOL_CALL>"
        val (_, calls) = ToolProtocol.extract(raw)
        assertEquals(ToolCatalog.SET_MOOD, calls.single().name)
        assertEquals("累", calls.single().argument("mood"))
    }

    @Test
    fun `多个标签与代码块混用时按出现顺序解析`() {
        val raw = """
            一。

            ```yap-tool
            {"name":"think","arguments":{"question":"a"}}
            ```

            <tool_call>{"name":"recall","arguments":{"query":"b"}}</tool_call>
        """.trimIndent()
        val (text, calls) = ToolProtocol.extract(raw)
        assertEquals(listOf(ToolCatalog.THINK, ToolCatalog.RECALL), calls.map { it.name })
        assertFalse(text.contains("yap-tool"))
        assertFalse(text.contains("<tool_call>"))
    }

    @Test
    fun `坏标签被丢弃 但正文仍然干净`() {
        val raw = "先这样。<tool_call>这不是 JSON</tool_call>"
        val (text, calls) = ToolProtocol.extract(raw)
        assertEquals("先这样。", text)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `流式时 未闭合的 XML 标签也不露出来`() {
        assertEquals("我先看看。", ToolProtocol.visiblePrefix("我先看看。\n<tool_call>{\"name\":"))
        // 正常正文里出现 <tool 但没闭合的情况同样截断，宁可少显示一个词也不露出协议。
        assertEquals("", ToolProtocol.visiblePrefix("<tool_code>"))
        // 普通尖括号文本不受影响。
        assertEquals("1 < 2 是对的", ToolProtocol.visiblePrefix("1 < 2 是对的"))
    }

    @Test
    fun `能力段要求必须调用 remember 与 memorize`() {
        val prompt = ToolCatalog.capabilityPrompt()
        assertTrue(prompt.contains("remember"))
        assertTrue(prompt.contains("memorize"))
        assertTrue("要强调不能只在正文说记住了", prompt.contains("只在正文"))
        assertTrue("工具描述也要写清必须调用", ToolCatalog.byName(ToolCatalog.MEMORIZE)!!.description.contains("必须调用"))
    }

    @Test
    fun `反 AI 段禁止旁白与舞台说明`() {
        val text = PromptBuilder.antiAiSection()
        assertTrue(text.contains("旁白"))
        assertTrue(text.contains("舞台说明"))
        assertTrue(text.contains("沉默了一会儿"))
    }
}
