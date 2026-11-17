package com.yap.app.domain

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

            ```yap-tool
            {"name":"web_search","arguments":{"query":"今天上海天气","count":3}}
            ```
        """.trimIndent()

        val (text, calls) = ToolProtocol.extract(raw)

        assertEquals("好，我去查一下。", text)
        assertFalse(text.contains("yap-tool"))
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

            ```yap-tool
            {"tool":"think","args":{"question":"要不要主动找他"}}
            ```

            再搜。

            ```yap-tool
            {"name":"web_search","arguments":"{\"query\":\"kotlin 2.0\"}"}
            ```

            下面这个是坏块：

            ```yap-tool
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
        val raw = "我想想。```YAP-TOOL {\"name\":\"think\",\"arguments\":{\"question\":\"x\"}}```"

        val (text, calls) = ToolProtocol.extract(raw)

        assertEquals("我想想。", text)
        assertEquals(1, calls.size)
        assertEquals(ToolCatalog.THINK, calls.single().name)
        assertTrue(ToolProtocol.extract("").first.isEmpty())
        assertTrue(ToolProtocol.extract("没有代码块的普通回复").second.isEmpty())
        assertEquals("没有代码块的普通回复", ToolProtocol.extract("  没有代码块的普通回复  ").first)
    }

    @Test
    fun extractAcceptsLegacyBlockLanguage() {
        // 兼容 0.12 更名前的旧模型输出 / 尚未更名的跨端客户端。
        val raw = "我想想。\n\n```myfriend-tool\n{\"name\":\"think\",\"arguments\":{\"question\":\"x\"}}\n```"
        val (text, calls) = ToolProtocol.extract(raw)
        assertEquals("我想想。", text)
        assertEquals(1, calls.size)
        assertEquals(ToolCatalog.THINK, calls.single().name)
        assertEquals("我想想。", ToolProtocol.visiblePrefix("我想想。\n\n```myfriend-tool"))
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
        assertTrue("提示词是「段」而不是手册：≤ 3200 字，实际 ${prompt.length}", prompt.length <= 3200)
        assertTrue(prompt.contains(ToolProtocol.BLOCK_LANGUAGE))
        assertTrue(prompt.contains(ToolCatalog.THINK))
        // 新增的回忆工具必须出现在提示词里，否则模型根本不会想到它
        assertTrue(prompt.contains(ToolCatalog.RECALL))
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

    /**
     * 回灌给模型的整段工具结果仍然逐字是中文（提示词脚手架不翻译）。
     *
     * 0.10.2 把「给用户看的标题/副标题」搬去了 `data/repo/ToolTraceText`（走 `R.string.tool_trace_*`），
     * 这条测试钉住另一半：模型侧一个字都不许变，免得顺手把提示词也翻译了。
     */
    @Test
    fun renderResultsForModelStaysChinese() {
        val results = listOf(
            ToolResult(
                invocation = ToolInvocation(
                    id = "call_1",
                    name = ToolCatalog.WEB_SEARCH,
                    argumentsJson = """{"query":"今天上海天气"}"""
                ),
                ok = true,
                summary = "今天上海天气",
                contentForModel = "1. 上海天气：晴"
            ),
            ToolResult(
                invocation = ToolInvocation(
                    id = "call_2",
                    name = ToolCatalog.REMEMBER,
                    argumentsJson = """{"content":"你怕冷","drawer":"USER","importance":4}"""
                ),
                ok = true,
                summary = "你怕冷",
                contentForModel = ""
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

        assertEquals(
            """
            【工具结果】web_search
            查询：今天上海天气
            摘要：今天上海天气
            结果：1. 上海天气：晴

            【工具结果】remember
            内容：你怕冷
            摘要：你怕冷

            【工具结果】web_fetch
            链接：https://example.com
            摘要：网页没读成
            失败：链接打不开
            """.trimIndent(),
            rendered
        )
    }

    /** 工具结果为空时的兜底文本也还是中文（`toolMessageContent` → role=tool 的 content）。 */
    @Test
    fun toolMessageContentFallsBackToChinese() {
        val failed = ToolResult(
            invocation = ToolInvocation(id = "call_5", name = ToolCatalog.THINK),
            ok = false,
            summary = "执行失败",
            contentForModel = ""
        )
        val message = Json.parseToJsonElement(ToolProtocol.toolMessageJson(failed)).jsonObject
        assertEquals("执行失败", message.getValue("content").jsonPrimitive.content)
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
        assertEquals(14, ToolCatalog.all.size)
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
        val partial = "我先看看。\n\n```yap-tool\n{\"name\":\"web_search\""
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
        assertTrue("关掉联网后不能再出现这个工具行", !offline.contains("- ${ToolCatalog.WEB_SEARCH}："))
        assertTrue(offline.contains("没有联网能力"))
        // 这一段是「模型该知道的手」，不是手册：≤ 2600 字（内容由工具注册表生成，见 ToolCapabilitySectionTest）。
        assertTrue(offline.length <= 2600)
    }
}
