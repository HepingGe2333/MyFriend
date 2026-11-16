package com.yap.app.core.json

import kotlin.math.floor

/**
 * 一个够用的 JSON 值 / 解析器 / 序列化器。
 *
 * 为什么自己写：`:agentcore` 的硬约束是**零第三方依赖**（只用 Kotlin 标准库 + JDK），
 * 不能引 kotlinx-serialization。而 `AgentCore` 的 HTTP 契约（`:agentd` 收请求、
 * Android 的远程客户端发请求/读响应）都需要 JSON，所以这里放一个 ~250 行的递归下降解析器：
 * 只支持标准 JSON，遇到坏输入抛 [JsonParseException]，由调用方翻成 400 / 回退进程内实现，
 * 不会静默吞掉。
 *
 * 不做的事：不支持注释、单引号、尾逗号（宁可比宽松解析器更严格，坏请求直接 400，
 * 免得客户端把「格式错误」当成「服务端行为异常」）。
 *
 * 0.11.1 的加固（安全审计 P2-1 / 编解码健壮性）：解析器是唯一会把不可信字节变成对象的入口，
 * 所以每个维度都有上限，超限一律抛 [JsonParseException]（带「哪个上限、多少」的明确文案）：
 * - [MAX_DEPTH] 嵌套层数（已有，防止递归打爆栈）；
 * - [MAX_STRING_LENGTH] 单个字符串字段（防止一个字段就把堆吃掉）；
 * - [MAX_NUMBER_LENGTH] 数字字面量位数，且必须能表示为有限 double（`1e999` 这类直接拒）；
 * - [MAX_CONTAINER_ENTRIES] 单个对象/数组的条目数；
 * - 重复键直接拒（不静默「后者覆盖前者」：那会让不同解析器对同一份 JSON 得出不同结果）。
 * 这些上限对真实请求/响应都有很大余量（HTTP 层还有 1 MiB 的请求体上限）。
 */
sealed interface JsonValue {
    data class Obj(val fields: LinkedHashMap<String, JsonValue> = LinkedHashMap()) : JsonValue

    data class Arr(val items: MutableList<JsonValue> = mutableListOf()) : JsonValue

    data class Str(val value: String) : JsonValue

    /** 数字保留原始文本：回显时不把 `5` 变成 `5.0`，避免客户端看到类型漂移。 */
    data class Num(val raw: String) : JsonValue {
        val asDouble: Double get() = raw.toDoubleOrNull() ?: Double.NaN
        val asLong: Long?
            get() {
                raw.toLongOrNull()?.let { return it }
                val d = raw.toDoubleOrNull() ?: return null
                if (!d.isFinite() || d != floor(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE) return null
                return d.toLong()
            }

        val asInt: Int? get() = asLong?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

class JsonParseException(message: String) : Exception(message)

object Json {

    /** 最大嵌套层数（对象/数组各算一层）。1000 层输入会得到 400 而不是 `StackOverflowError`。 */
    const val MAX_DEPTH = 64

    /** 单个字符串字段的最大字符数（转义解码后的长度）。 */
    const val MAX_STRING_LENGTH = 256 * 1024

    /** 数字字面量的最大字符数；超过或无法表示为有限 double 都拒。 */
    const val MAX_NUMBER_LENGTH = 128

    /** 单个对象/数组的最大条目数。 */
    const val MAX_CONTAINER_ENTRIES = 16_384

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.parseValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonParseException("JSON 末尾还有多余内容（位置 ${parser.index}）")
        return value
    }

    fun stringify(value: JsonValue): String = buildString { write(value, this) }

    // ------------------------------------------------------------------ 写

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.Obj -> {
                out.append('{')
                var first = true
                for ((key, child) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    writeString(key, out)
                    out.append(':')
                    write(child, out)
                }
                out.append('}')
            }

            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, child ->
                    if (index > 0) out.append(',')
                    write(child, out)
                }
                out.append(']')
            }

            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Num -> out.append(value.raw)
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            JsonValue.Null -> out.append("null")
        }
    }

    private fun writeString(text: String, out: StringBuilder) {
        out.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') {
                    out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }

    // ------------------------------------------------------------------ 读

    private class Parser(private val text: String) {
        var index: Int = 0
            private set

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isJsonWhitespace()) index++
        }

        private fun fail(message: String): Nothing =
            throw JsonParseException("$message（位置 $index）")

        private fun peek(): Char {
            if (atEnd()) fail("JSON 意外结束")
            return text[index]
        }

        private fun expect(ch: Char) {
            if (atEnd() || text[index] != ch) fail("期望 '$ch'")
            index++
        }

        fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("JSON 嵌套过深（> $MAX_DEPTH 层）")
            skipWhitespace()
            val ch = peek()
            return when (ch) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonValue.Str(parseString())
                't' -> parseKeyword("true", JsonValue.Bool(true))
                'f' -> parseKeyword("false", JsonValue.Bool(false))
                'n' -> parseKeyword("null", JsonValue.Null)
                else -> if (ch == '-' || ch in '0'..'9') parseNumber() else fail("无法识别的 JSON 值")
            }
        }

        private fun parseObject(depth: Int): JsonValue.Obj {
            expect('{')
            val obj = JsonValue.Obj()
            skipWhitespace()
            if (!atEnd() && peek() == '}') {
                index++
                return obj
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || peek() != '"') fail("对象的键必须是字符串")
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue(depth + 1)
                if (obj.fields.containsKey(key)) fail("JSON 对象里有重复键")
                obj.fields[key] = value
                if (obj.fields.size > MAX_CONTAINER_ENTRIES) {
                    fail("JSON 对象的键过多（上限 $MAX_CONTAINER_ENTRIES 个）")
                }
                skipWhitespace()
                if (atEnd()) fail("对象没有闭合")
                when (val ch = text[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return obj
                    }
                    else -> fail("对象里期望 ',' 或 '}'，实际 '$ch'")
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue.Arr {
            expect('[')
            val arr = JsonValue.Arr()
            skipWhitespace()
            if (!atEnd() && peek() == ']') {
                index++
                return arr
            }
            while (true) {
                arr.items += parseValue(depth + 1)
                if (arr.items.size > MAX_CONTAINER_ENTRIES) {
                    fail("JSON 数组的元素过多（上限 $MAX_CONTAINER_ENTRIES 个）")
                }
                skipWhitespace()
                if (atEnd()) fail("数组没有闭合")
                when (val ch = text[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return arr
                    }
                    else -> fail("数组里期望 ',' 或 ']'，实际 '$ch'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("字符串没有闭合")
                val ch = text[index++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) fail("字符串转义没有写完")
                        when (val escaped = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) fail("\\u 转义不完整")
                                val hex = text.substring(index, index + 4)
                                val code = hex.toIntOrNull(16) ?: fail("\\u 转义不是十六进制")
                                out.append(code.toChar())
                                index += 4
                            }

                            else -> fail("不支持的转义 '\\$escaped'")
                        }
                    }

                    else -> {
                        if (ch < ' ') fail("字符串里有未转义的控制字符")
                        out.append(ch)
                    }
                }
                if (out.length > MAX_STRING_LENGTH) {
                    fail("字符串过长（上限 $MAX_STRING_LENGTH 个字符）")
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = index
            if (!atEnd() && text[index] == '-') index++
            if (atEnd()) fail("数字没有写完")
            if (text[index] == '0') {
                index++
            } else if (text[index] in '1'..'9') {
                while (!atEnd() && text[index] in '0'..'9') index++
            } else {
                fail("数字格式不对")
            }
            if (!atEnd() && text[index] == '.') {
                index++
                if (atEnd() || text[index] !in '0'..'9') fail("小数点后缺少数字")
                while (!atEnd() && text[index] in '0'..'9') index++
            }
            if (!atEnd() && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (!atEnd() && (text[index] == '+' || text[index] == '-')) index++
                if (atEnd() || text[index] !in '0'..'9') fail("指数部分缺少数字")
                while (!atEnd() && text[index] in '0'..'9') index++
            }
            val raw = text.substring(start, index)
            if (raw.length > MAX_NUMBER_LENGTH) {
                fail("数字过长（上限 $MAX_NUMBER_LENGTH 位）")
            }
            // `1e999` / 300 位整数：语法上是 JSON 数字，但落不进 double（会变 Infinity）。
            // 放它过去只会在下游变成 NaN/Infinity 或静默截断，所以在解析层就拒。
            val asDouble = raw.toDoubleOrNull()
            if (asDouble != null && !asDouble.isFinite()) fail("数字超出可表示范围")
            return JsonValue.Num(raw)
        }

        private fun parseKeyword(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, index)) fail("无法识别的 JSON 值")
            index += word.length
            return value
        }
    }

    private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\n' || this == '\r' || this == '\t'
}
