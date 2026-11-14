package com.myfriend.app.core.json

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
                if (!d.isFinite() || d != Math.floor(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE) return null
                return d.toLong()
            }

        val asInt: Int? get() = asLong?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

class JsonParseException(message: String) : Exception(message)

object Json {

    private const val MAX_DEPTH = 64

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
                obj.fields[key] = value
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
                when (val ch = text[index++]) {
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
            return JsonValue.Num(text.substring(start, index))
        }

        private fun parseKeyword(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, index)) fail("无法识别的 JSON 值")
            index += word.length
            return value
        }
    }

    private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\n' || this == '\r' || this == '\t'
}
