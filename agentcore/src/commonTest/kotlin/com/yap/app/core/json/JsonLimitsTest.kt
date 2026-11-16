package com.yap.app.core.json

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * JSON 解析器的上限与边界（0.11.1 编解码健壮性加固）。
 *
 * 解析器是唯一把「不可信字节」变成对象的入口，所以每个维度都要有明确上限 + 明确错误：
 * 嵌套深度、单个字符串长度、数字长度/可表示性、容器条目数、重复键。
 * 这些上限对真实请求/响应都有很大余量（HTTP 层另有 1 MiB 请求体上限）。
 */
class JsonLimitsTest {

    private fun fails(text: String): JsonParseException {
        try {
            Json.parse(text)
        } catch (expected: JsonParseException) {
            return expected
        }
        throw AssertionError("预期抛 JsonParseException，但解析成功了：${text.take(64)}…")
    }

    @Test
    fun `hundred level nesting fails with a clear message`() {
        val error = fails("[".repeat(100) + "]".repeat(100))
        assertTrue(error.message.orEmpty().contains("嵌套过深"), "错误文案：${error.message}")
    }

    @Test
    fun `normal depth still parses`() {
        val value = Json.parse("[".repeat(32) + "]".repeat(32))
        assertTrue(value is JsonValue.Arr)
    }

    @Test
    fun `long string field fails with a clear message`() {
        val text = "\"" + "x".repeat(Json.MAX_STRING_LENGTH + 1) + "\""
        val error = fails(text)
        assertTrue(error.message.orEmpty().contains("字符串过长"), "错误文案：${error.message}")
    }

    @Test
    fun `string at the limit is still accepted`() {
        val text = "\"" + "x".repeat(Json.MAX_STRING_LENGTH) + "\""
        val value = Json.parse(text) as JsonValue.Str
        assertEquals(Json.MAX_STRING_LENGTH, value.value.length)
    }

    @Test
    fun `one e 308 is accepted but huge exponents are rejected`() {
        // 语法合法、double 可表示：保留原始文本（调用方按需取整数）。
        val value = Json.parse("""{"n":1e308}""") as JsonValue.Obj
        val number = value.fields["n"] as JsonValue.Num
        assertEquals(null, number.asInt)
        assertTrue(number.asDouble.isFinite())

        // 1e999 会变成 Infinity：解析层就拒，不允许它流进决策层。
        val error = fails("""{"n":1e999}""")
        assertTrue(error.message.orEmpty().contains("数字超出可表示范围"), "错误文案：${error.message}")
    }

    @Test
    fun `over long number token is rejected`() {
        val digits = "1" + "0".repeat(Json.MAX_NUMBER_LENGTH)
        val error = fails("""{"n":$digits}""")
        assertTrue(error.message.orEmpty().contains("数字过长"), "错误文案：${error.message}")
    }

    @Test
    fun `NaN and Infinity literals are rejected`() {
        // JSON 没有这三个字面量；宽松解析会把非法输入放进决策层，所以必须抛。
        fails("""{"n":NaN}""")
        fails("""{"n":Infinity}""")
        fails("""{"n":-Infinity}""")
    }

    @Test
    fun `duplicate keys are rejected`() {
        val error = fails("""{"a":1,"a":2}""")
        assertTrue(error.message.orEmpty().contains("重复键"), "错误文案：${error.message}")
    }

    @Test
    fun `too many object keys are rejected`() {
        val fields = (0..Json.MAX_CONTAINER_ENTRIES).joinToString(",") { "\"k$it\":0" }
        val error = fails("{$fields}")
        assertTrue(error.message.orEmpty().contains("键过多"), "错误文案：${error.message}")
    }

    @Test
    fun `too many array items are rejected`() {
        val items = (0..Json.MAX_CONTAINER_ENTRIES).joinToString(",") { "0" }
        val error = fails("[$items]")
        assertTrue(error.message.orEmpty().contains("元素过多"), "错误文案：${error.message}")
    }

    @Test
    fun `ordinary json with escapes and unicode still round trips`() {
        val text = """{"s":"中文\u4e2d\n\"引号\"","n":-12.5e-3,"b":true,"z":null,"a":[1,2,3]}"""
        val parsed = Json.parse(text)
        val json = Json.stringify(parsed)
        assertEquals(parsed, Json.parse(json))
        assertEquals("中文中\n\"引号\"", ((parsed as JsonValue.Obj).fields["s"] as JsonValue.Str).value)
    }
}
