package com.myfriend.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProtocolTest {

    @Test
    fun extractSingleBlockCleansTextAndParsesArguments() {
        val raw = """
            好，我去查一下。

            ```myfriend-tool
            {"name":"web_search","arguments":{"query":"今天上海天气","count":3}}
            ```
        """.trimIndent()

        val (text, calls) = ToolProtocol.extract(raw)

        assertEquals("好，我去查一下。", text)
        assertFalse(text.contains("myfriend-tool"))
        assertEquals(1, calls.size)

        val call = calls.single()
        assertEquals(ToolCatalog.WEB_SEARCH, call.name)
        assertEquals("今天上海天气", call.argument("query"))
        assertEquals(3, call.intArgument("count", 5))
        assertTrue(call.id.isNotBlank())
        // 类型不对 / 缺失都返回 null，整数走 intArgument 的默认值
        assertNull(call.argument("count"))
        assertNull(call.argument("missing"))
        assertEquals(5, call.intArgument("missing", 5))
    }

    @Test
    fun extractMultipleBlocksDropsBrokenBlockButKeepsOthers() {
        val raw = """
            先想一下。

            ```myfriend-tool
            {"tool":"think","args":{"question":"要不要主动找他"}}
            ```

            再搜。

            ```myfriend-tool
            {"name":"web_search","arguments":"{\"query\":\"kotlin 2.0\"}"}
            ```

            下面这个是坏块：

            ```myfriend-tool
            这不是 JSON 对象，只是个坏块
            ```
        """.trimIndent()

        val (text, calls) = ToolProtocol.extract(raw)

        assertEquals(2, calls.size)
        assertEquals(listOf(ToolCatalog.THINK, ToolCatalog.WEB_SEARCH), calls.map { it.name })
        // {tool, args} 形状
        assertEquals("要不要主动找他", calls[0].argument("question"))
        // arguments 为字符串时原样用
        assertEquals("{\"query\":\"kotlin 2.0\"}", calls[1].argumentsJson)
        assertEquals("kotlin 2.0", calls[1].argument("query"))
        // 坏块被丢弃，但代码块整体从正文里移除
        assertFalse(text.contains(ToolProtocol.BLOCK_LANGUAGE))
        assertFalse(text.contains("只是个坏块"))
        assertTrue(text.startsWith("先想一下。"))
        assertTrue(text.contains("再搜。"))
    }

    @Test
    fun extractToleratesUppercaseLanguageAndSameLinePayload() {
        val raw = "我想想。```MYFRIEND-TOOL {\"name\":\"think\",\"arguments\":{\"question\":\"x\"}}```"

        val (text, calls) = ToolProtocol.extract(raw)

        assertEquals("我想想。", text)
        assertEquals(1, calls.size)
        assertEquals(ToolCatalog.THINK, calls.single().name)
        assertTrue(ToolProtocol.extract("").first.isEmpty())
        assertTrue(ToolProtocol.extract("没有代码块的普通回复").second.isEmpty())
        assertEquals("没有代码块的普通回复", ToolProtocol.extract("  没有代码块的普通回复  ").first)
    }

    @Test
    fun parseNativeCallsHandlesStandardShapeObjectArgumentsAndGarbage() {
        val standard = """
            [{"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"x\"}"}}]
        """.trimIndent()
        val calls = ToolProtocol.parseNativeCalls(standard)
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("web_search", calls[0].name)
        assertEquals("x", calls[0].argument("query"))

        val objectArguments = """
            [{"id":"call_2","type":"function","function":{"name":"remember","arguments":{"content":"你怕冷","drawer":"USER","importance":4}}}]
        """.trimIndent()
        val remember = ToolProtocol.parseNativeCalls(objectArguments).single()
        assertEquals("你怕冷", remember.argument("content"))
        assertEquals(4, remember.intArgument("importance", 1))

        assertTrue(ToolProtocol.parseNativeCalls(null).isEmpty())
        assertTrue(ToolProtocol.parseNativeCalls("").isEmpty())
        assertTrue(ToolProtocol.parseNativeCalls("   ").isEmpty())
        assertTrue(ToolProtocol.parseNativeCalls("不是 JSON").isEmpty())
        assertTrue(ToolProtocol.parseNativeCalls("{\"foo\":1}").isEmpty())

        // 缺 name 的条目跳过，不让一条坏数据拖垮整轮
        val mixed = """
            [{"id":"a"},{"id":"b","function":{"name":"think","arguments":{}}}]
        """.trimIndent()
        val kept = ToolProtocol.parseNativeCalls(mixed)
        assertEquals(1, kept.size)
        assertEquals(ToolCatalog.THINK, kept.single().name)
        assertNotNull(kept.single().id)
    }

    @Test
    fun toOpenAiToolsJsonContainsAllFunctionsWithObjectSchemas() {
        val tools = Json.parseToJsonElement(ToolCatalog.toOpenAiToolsJson()).jsonArray

        assertEquals(ToolCatalog.all.size, tools.size)
        assertEquals(
            ToolCatalog.all.map { it.name },
            tools.map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }
        )

        val thinkParameters = tools
            .map { it.jsonObject.getValue("function").jsonObject }
            .first { it.getValue("name").jsonPrimitive.content == ToolCatalog.THINK }
            .getValue("parameters")
            .jsonObject
        assertEquals("object", thinkParameters.getValue("type").jsonPrimitive.content)
        assertTrue(thinkParameters.getValue("properties").jsonObject.containsKey("question"))

        tools.forEach { element ->
            assertEquals("function", element.jsonObject.getValue("type").jsonPrimitive.content)
            val function = element.jsonObject.getValue("function").jsonObject
            assertEquals("object", function.getValue("parameters").jsonObject.getValue("type").jsonPrimitive.content)
        }
        // 每个 schema 单独看也必须是合法 JSON
        ToolCatalog.all.forEach { spec ->
            assertEquals("object", Json.parseToJsonElement(spec.parametersJson).jsonObject.getValue("type").jsonPrimitive.content)
        }
    }

    @Test
    fun capabilityPromptIsShortAndMentionsBlockProtocol() {
        val prompt = ToolCatalog.capabilityPrompt()

        assertTrue(prompt.isNotBlank())
        assertTrue("提示词应保持简短（≤ 700 字），实际 ${prompt.length}", prompt.length <= 700)
        assertTrue(prompt.contains(ToolProtocol.BLOCK_LANGUAGE))
        assertTrue(prompt.contains(ToolCatalog.THINK))
        // 新增的回忆工具必须出现在提示词里，否则模型根本不会想到它
        assertTrue(prompt.contains("${ToolCatalog.RECALL}("))
        ToolCatalog.all.forEach { spec ->
            assertTrue("提示词应提到 ${spec.name}", prompt.contains(spec.name))
        }
    }

    @Test
    fun renderResultsForModelIncludesEveryToolNameAndSummary() {
        val results = listOf(
            ToolResult(
                invocation = ToolInvocation(
                    id = "call_1",
                    name = ToolCatalog.WEB_SEARCH,
                    argumentsJson = """{"query":"今天上海天气"}"""
                ),
                ok = true,
                summary = "查了 3 个网页",
                contentForModel = "上海今天 18~24 度，多云。"
            ),
            ToolResult(
                invocation = ToolInvocation(
                    id = "call_2",
                    name = ToolCatalog.REMEMBER,
                    argumentsJson = """{"content":"你怕冷"}"""
                ),
                ok = true,
                summary = "记住了：你怕冷",
                contentForModel = "已写入 USER 抽屉。"
            ),
            ToolResult(
                invocation = ToolInvocation(
                    id = "call_3",
                    name = ToolCatalog.WEB_FETCH,
                    argumentsJson = """{"url":"https://example.com"}"""
                ),
                ok = false,
                summary = "网页没读成",
                contentForModel = "",
                errorMessage = "链接打不开"
            )
        )

        val rendered = ToolProtocol.renderResultsForModel(results)

        results.forEach { result ->
            assertTrue("缺少工具名 ${result.invocation.name}", rendered.contains(result.invocation.name))
            assertTrue("缺少摘要 ${result.summary}", rendered.contains(result.summary))
        }
        assertTrue(rendered.contains("今天上海天气"))
        assertTrue(rendered.contains("https://example.com"))
        assertTrue(rendered.contains("链接打不开"))
        assertTrue(rendered.contains("【工具结果】"))
        assertTrue(ToolProtocol.renderResultsForModel(emptyList()).isEmpty())
    }

    @Test
    fun describeGivesChineseTitles() {
        val search = ToolResult(
            invocation = ToolInvocation(id = "call_1", name = ToolCatalog.WEB_SEARCH),
            ok = true,
            summary = "查了 3 个网页",
            contentForModel = "略"
        )
        assertEquals("联网搜索" to "查了 3 个网页", ToolProtocol.describe(search))

        val think = ToolResult(
            invocation = ToolInvocation(id = "call_2", name = ToolCatalog.THINK),
            ok = true,
            summary = "想了想",
            contentForModel = "略"
        )
        val (thinkTitle, thinkSubtitle) = ToolProtocol.describe(think)
        assertEquals("想了想", thinkTitle)
        assertNull(thinkSubtitle)

        val remember = ToolResult(
            invocation = ToolInvocation(id = "call_3", name = ToolCatalog.REMEMBER),
            ok = true,
            summary = "记住了：你怕冷",
            contentForModel = "略"
        )
        assertEquals("记住了一件事" to "记住了：你怕冷", ToolProtocol.describe(remember))

        val persona = ToolResult(
            invocation = ToolInvocation(id = "call_4", name = ToolCatalog.UPDATE_PERSONA),
            ok = true,
            summary = "话更少了一些",
            contentForModel = "略"
        )
        assertEquals("调整了自己的人设" to "话更少了一些", ToolProtocol.describe(persona))

        val quietHours = ToolResult(
            invocation = ToolInvocation(id = "call_5", name = ToolCatalog.SET_QUIET_HOURS),
            ok = true,
            summary = "23:00 睡，07:00 醒",
            contentForModel = "略"
        )
        assertEquals("安排了自己的作息" to "23:00 睡，07:00 醒", ToolProtocol.describe(quietHours))

        val recall = ToolResult(
            invocation = ToolInvocation(id = "call_7", name = ToolCatalog.RECALL),
            ok = true,
            summary = "翻了翻记忆 · 2 条",
            contentForModel = "略"
        )
        assertEquals("回想了一下" to "翻了翻记忆 · 2 条", ToolProtocol.describe(recall))

        // 失败时副标题给出原因，标题不变
        val failedFetch = ToolResult(
            invocation = ToolInvocation(id = "call_6", name = ToolCatalog.WEB_FETCH),
            ok = false,
            summary = "网页没读成",
            contentForModel = "",
            errorMessage = "链接打不开"
        )
        assertEquals("读了网页" to "链接打不开", ToolProtocol.describe(failedFetch))
    }

    @Test
    fun toolMessageJsonUsesToolRoleShape() {
        val result = ToolResult(
            invocation = ToolInvocation(
                id = "call_9",
                name = ToolCatalog.WEB_SEARCH,
                argumentsJson = """{"query":"x"}"""
            ),
            ok = true,
            summary = "查了 1 个网页",
            contentForModel = "结果正文"
        )

        val message = Json.parseToJsonElement(ToolProtocol.toolMessageJson(result)).jsonObject

        assertEquals("tool", message.getValue("role").jsonPrimitive.content)
        assertEquals("call_9", message.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("结果正文", message.getValue("content").jsonPrimitive.content)
        // 代码块解析出来的调用没有 id，必须自己兜底一个，否则上游会拒收
        val blockCall = ToolResult(
            invocation = ToolInvocation(name = ToolCatalog.THINK, argumentsJson = """{"question":"x"}"""),
            ok = true,
            summary = "想了想",
            contentForModel = "略"
        )
        val fallback = Json.parseToJsonElement(ToolProtocol.toolMessageJson(blockCall)).jsonObject
        assertTrue(fallback.getValue("tool_call_id").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun catalogExposesSpecsByName() {
        assertEquals(9, ToolCatalog.all.size)
        assertEquals(ToolCatalog.WEB_SEARCH, ToolCatalog.byName(" web_search ").let { it?.name })
        assertEquals(ToolCatalog.RECALL, ToolCatalog.byName(ToolCatalog.RECALL)?.name)
        assertNull(ToolCatalog.byName("no_such_tool"))
        assertTrue(ToolCatalog.all.all { it.description.isNotBlank() })
        // recall 的参数：query 必填，depth 选填（宽松解析在 ToolExecutor 里做）
        val recallSpec = ToolCatalog.all.first { it.name == ToolCatalog.RECALL }
        assertTrue(recallSpec.parametersJson.contains("\"query\""))
        assertTrue(recallSpec.parametersJson.contains("\"depth\""))
        assertTrue(recallSpec.parametersJson.contains("\"required\": [\"query\"]"))
    }

    @Test
    fun visiblePrefixHidesUnterminatedToolBlock() {
        // 流式过程中工具块可能只写了一半：这一半绝不能被推到界面上。
        val partial = "我先看看。\n\n```myfriend-tool\n{\"name\":\"web_search\""
        assertEquals("我先看看。", ToolProtocol.visiblePrefix(partial))
        // 普通代码块不受影响
        val code = "给你一段：\n```kotlin\nval x = 1\n```"
        assertEquals(code, ToolProtocol.visiblePrefix(code))
        assertEquals("", ToolProtocol.visiblePrefix(""))
        assertEquals("一句话", ToolProtocol.visiblePrefix("一句话"))
    }

    @Test
    fun capabilityPromptOmitsWebToolsWhenOffline() {
        val online = ToolCatalog.capabilityPrompt(webEnabled = true)
        val offline = ToolCatalog.capabilityPrompt(webEnabled = false)
        assertTrue(online.contains("web_search"))
        assertTrue(!offline.contains("web_search("))
        assertTrue(offline.contains("没有联网能力"))
        assertTrue(offline.length <= 700)
    }
}
