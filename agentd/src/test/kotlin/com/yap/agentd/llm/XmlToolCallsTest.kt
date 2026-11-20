package com.yap.agentd.llm

import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型把工具调用写成正文 XML 标签时的兜底解析（0.15 评测真实问题）。
 *
 * DeepSeek 基线会输出 `<tool_call>…</tool_call>` / `<tool_code>` / `<tool>` 三种标签；
 * 服务端此前只认 `tool_calls` 字段，于是标签被当成正文发给用户、工具根本没执行。
 * 这里钉住四件事：
 * 1. 三种标签都认，且**标签不会流到客户端**（流式分片被拆开也一样）；
 * 2. 三种内容形状都归一成普通工具调用（JSON / 函数调用 / Python 关键字参数）；
 * 3. 解析不出来就丢掉，不瞎猜；
 * 4. 没有标签的正文一个字节都不改。
 */
class XmlToolCallsTest {

    private fun args(call: LlmToolCall): JsonValue.Obj {
        assertEquals("参数必须是 JSON 字符串", true, call.arguments.trim().startsWith("{"))
        return Json.parse(call.arguments) as JsonValue.Obj
    }

    @Test
    fun `extract three tag shapes and strip them from the text`() {
        val text = "好，我先记一下。" +
            """<tool_call>{"name":"remember","arguments":{"content":"他怕冷"}}</tool_call>""" +
            "另外，" +
            """<tool_code>remember({"content":"下周去哈尔滨"})</tool_code>""" +
            """<tool>remember(content="他喜欢夜里散步", importance=4)</tool>""" +
            "记住了。"
        val parsed = XmlToolCalls.extract(text)

        assertEquals("三段标签都要解析出来", 3, parsed.calls.size)
        assertEquals("remember", parsed.calls[0].name)
        assertEquals("他怕冷", (args(parsed.calls[0]).fields["content"] as JsonValue.Str).value)
        assertEquals("下周去哈尔滨", (args(parsed.calls[1]).fields["content"] as JsonValue.Str).value)
        assertEquals("他喜欢夜里散步", (args(parsed.calls[2]).fields["content"] as JsonValue.Str).value)
        assertEquals("4", (args(parsed.calls[2]).fields["importance"] as JsonValue.Num).raw)

        assertFalse("标签不能留在正文里", parsed.text.contains("tool_call"))
        assertFalse(parsed.text.contains("tool_code"))
        assertFalse(parsed.text.contains("<tool"))
        assertTrue(parsed.text.contains("好，我先记一下。"))
    }

    @Test
    fun `plain text is passed through unchanged`() {
        val text = "他沉默了一会儿，然后说了句「走吧」。"
        val parsed = XmlToolCalls.extract(text)
        assertEquals(text, parsed.text)
        assertTrue(parsed.calls.isEmpty())
        assertFalse(XmlToolCalls.containsToolTag(text))
    }

    @Test
    fun `a json array of calls is expanded`() {
        val parsed = XmlToolCalls.extract(
            """<tool_call>[{"name":"diary","arguments":{"action":"list"}},{"name":"set_mood","arguments":{"label":"平静"}}]</tool_call>"""
        )
        assertEquals(2, parsed.calls.size)
        assertEquals("diary", parsed.calls[0].name)
        assertEquals("set_mood", parsed.calls[1].name)
    }

    @Test
    fun `tool name may come from an attribute`() {
        val parsed = XmlToolCalls.extract("""<tool name="recall">{"query":"怕冷"}</tool>""")
        assertEquals(1, parsed.calls.size)
        assertEquals("recall", parsed.calls.single().name)
        assertEquals("怕冷", (args(parsed.calls.single()).fields["query"] as JsonValue.Str).value)
    }

    @Test
    fun `an unparsable tag is dropped instead of guessed`() {
        val parsed = XmlToolCalls.extract("<tool_call>今天天气不错</tool_call>")
        assertTrue(parsed.calls.isEmpty())
        assertEquals("坏标签按坏输出丢掉，不把内容发给用户", "", parsed.text)
    }

    @Test
    fun `streaming filter hides a tag split across deltas`() {
        val emitted = StringBuilder()
        val filter = XmlToolCalls.StreamFilter { emitted.append(it) }
        filter.accept("好，我记一下。")
        filter.accept("<tool_")
        filter.accept("call>{\"name\":\"remember\",\"arguments\":")
        filter.accept("{\"content\":\"他怕冷\"}}")
        filter.accept("</tool_call>")
        filter.accept("记住了。")
        val calls = filter.finish()

        assertEquals(1, calls.size)
        assertEquals("remember", calls.single().name)
        assertEquals("好，我记一下。记住了。", emitted.toString())
        assertEquals(emitted.toString(), filter.text)
    }

    @Test
    fun `streaming filter does not eat a normal smaller than sign`() {
        val emitted = StringBuilder()
        val filter = XmlToolCalls.StreamFilter { emitted.append(it) }
        filter.accept("3 < 5，")
        filter.accept("这不算标签。")
        assertTrue(filter.finish().isEmpty())
        assertEquals("3 < 5，这不算标签。", emitted.toString())
    }

    @Test
    fun `python keyword arguments become json`() {
        val json = XmlToolCalls.kwargsToJson("""content='明天带伞', importance=4, tags=["a","b"], ok=True""")
        val obj = Json.parse(json) as JsonValue.Obj
        assertEquals("明天带伞", (obj.fields["content"] as JsonValue.Str).value)
        assertEquals("4", (obj.fields["importance"] as JsonValue.Num).raw)
        assertEquals(2, (obj.fields["tags"] as JsonValue.Arr).items.size)
        assertEquals(true, (obj.fields["ok"] as JsonValue.Bool).value)
    }
}
