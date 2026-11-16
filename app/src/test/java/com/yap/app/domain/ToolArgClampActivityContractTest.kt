package com.yap.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `set_activity` 的参数夹取（`spec/agent-core/cases/tool-args.json` 的 `activity` 段）。
 *
 * 逐条跑规范里的用例，而不是自己另写一套期望值：Android 与 `agentd` / iOS 用的是同一份
 * 契约文件，谁改规则都要先让这里红。数值用 [ToolArgClamp.clampActivityMinutes]，
 * `"abc"` 这种非数字走字符串重载（默认 60），kind / availability 走归一化函数。
 */
class ToolArgClampActivityContractTest {

    private val spec: File by lazy {
        listOf("../spec/agent-core/cases/tool-args.json", "spec/agent-core/cases/tool-args.json")
            .map(::File)
            .firstOrNull { it.exists() }
            ?: error("找不到 tool-args.json（工作目录=${File("").absolutePath}）")
    }

    private val activity by lazy {
        Json.parseToJsonElement(spec.readText()).jsonObject["activity"]!!.jsonObject
    }

    @Test
    fun `契约文件里的符号就是 ToolArgClamp`() {
        val kotlin = activity["kotlin"]!!.jsonObject
        assertEquals("agentcore/src/commonMain/kotlin/com/yap/app/domain/ToolArgClamp.kt", kotlin["file"]!!.jsonPrimitive.content)
        assertEquals("ToolArgClamp.clampActivityMinutes", kotlin["symbol"]!!.jsonPrimitive.content)
    }

    @Test
    fun `逐条跑 activity 用例`() {
        val cases = activity["cases"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertTrue("契约里至少要有几条用例", cases.isNotEmpty())
        cases.forEach { element ->
            val case = element.jsonObject
            val input = case["input"]!!.jsonObject
            val expect = case["expect"]!!.jsonObject
            when {
                input.containsKey("minutes") -> {
                    val raw = input.getValue("minutes").jsonPrimitive
                    val expected = expect.getValue("minutes").jsonPrimitive.int
                    val actual = if (raw.isString) {
                        ToolArgClamp.clampActivityMinutes(raw.content)
                    } else {
                        ToolArgClamp.clampActivityMinutes(raw.int)
                    }
                    assertEquals("minutes 用例 $input", expected, actual)
                }
                input.containsKey("kind") -> {
                    val expected = expect.getValue("kind").jsonPrimitive.content
                    assertEquals(
                        "kind 用例 $input",
                        expected,
                        ToolArgClamp.normalizeActivityKind(input.getValue("kind").jsonPrimitive.content)
                    )
                }
                input.containsKey("availability") -> {
                    val expected = expect.getValue("availability").jsonPrimitive.content
                    assertEquals(
                        "availability 用例 $input",
                        expected,
                        ToolArgClamp.normalizeActivityAvailability(
                            input.getValue("availability").jsonPrimitive.content
                        )
                    )
                }
                else -> throw AssertionError("不认识的 activity 用例：$input")
            }
        }
    }

    @Test
    fun `默认值与枚举清单与契约一致`() {
        val rules = activity["rules"]!!.jsonObject
        val minutes = rules["minutes"]!!.jsonObject
        assertEquals(15, minutes.getValue("min").jsonPrimitive.int)
        assertEquals(480, minutes.getValue("max").jsonPrimitive.int)
        assertEquals(ToolArgClamp.ACTIVITY_MINUTES_DEFAULT, minutes.getValue("default").jsonPrimitive.int)
        val kinds = (rules["kind"]!!.jsonObject["values"]!! as kotlinx.serialization.json.JsonArray)
            .map { it.jsonPrimitive.content }
        assertEquals(ToolArgClamp.ACTIVITY_KINDS, kinds)
        val availability = (rules["availability"]!!.jsonObject["values"]!! as kotlinx.serialization.json.JsonArray)
            .map { it.jsonPrimitive.content }
        assertEquals(ToolArgClamp.ACTIVITY_AVAILABILITIES, availability)
    }
}
