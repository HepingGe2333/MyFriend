package com.yap.agentd.store

import com.yap.app.core.json.JsonValue
import java.io.OutputStream
import java.io.Reader

/**
 * 备份导入用的**最小流式 JSON 读取器**（零第三方依赖，`agentd` 内部使用）。
 *
 * 为什么不能用 `com.yap.app.core.json.Json.parse`：它要求整份文本先变成 `String`，再变成整棵
 * `JsonValue` 树；一份含 128 MiB 媒体的 v5 备份会因此占用几百 MB 堆（base64 文本 + 解码后的数组 +
 * 树的节点）。0.12.1 要求「导入不把整份备份放内存」，所以这里做成**边读边处理**：
 *
 * - 顶层对象/数组用 [beginObject] / [nextKey] / [beginArray] / [hasNextElement] 逐个元素推进，
 *   每个元素自己 [readValue]（一个对象，几百字节到几 KB）；
 * - 媒体 `data` 字段用 [readBase64To] **边读边解码**写入文件，不产生 base64 字符串、也不产生
 *   整块 `ByteArray`；
 * - 不认识的字段用 [skipValue] 跳过，不建对象。
 *
 * 与 `agentcore` 的 `Json` 解析器保持同样的**严格度**（坏 JSON 一律抛 [BackupJsonException]，
 * 不静默纠正）：重复键、尾逗号、单引号、控制字符、超长字符串/数字、超深嵌套都拒绝。
 * 差异只有上限本身：这里允许单个字符串到 4 MiB（备份里的 `content` 可能很长），
 * 数组元素允许到 100 万（10 万条消息的备份是正常输入）；对象键仍限 4096。
 *
 * 它不做 schema 校验：字段语义校验仍在 `FileStore` 的 `parseBackup*` 里（复用原有规则），
 * 这样「旧整份 JSON 导入」和「流式导入」走**同一套校验代码**，不会出现两套行为。
 */
internal class BackupJsonException(message: String) : Exception(message)

internal class BackupJsonReader(private val input: Reader) {

    companion object {
        /** 嵌套层数上限（与 `Json.MAX_DEPTH` 一致，防止恶意输入打爆栈）。 */
        const val MAX_DEPTH = 64

        /** 单个字符串（转义解码后）的字符上限。 */
        const val MAX_STRING_CHARS = 4 * 1024 * 1024

        /** 数字字面量位数上限。 */
        const val MAX_NUMBER_CHARS = 128

        /** 单个对象的键上限。 */
        const val MAX_OBJECT_ENTRIES = 4_096

        /** 单个数组的元素上限（10 万条消息的备份是正常输入）。 */
        const val MAX_ARRAY_ENTRIES = 1_000_000

        private const val NONE = -2

        /** 单条媒体上限（与 `FileStore.MAX_BACKUP_MEDIA_FILE_BYTES` 一致；只用于错误文案）。 */
        private const val MAX_BACKUP_MEDIA_FILE_BYTES = 8L * 1024 * 1024
    }

    private var lookahead: Int = NONE
    private var position: Long = 0

    /** 驱动层（[beginObject]/[nextKey]/[beginArray]/[hasNextElement]）的逗号状态。 */
    private class Frame(val isObject: Boolean) {
        var count: Int = 0
    }

    private val frames = ArrayDeque<Frame>()

    // ------------------------------------------------------------------ 底层字符

    private fun read(): Int {
        if (lookahead != NONE) {
            val ch = lookahead
            lookahead = NONE
            return ch
        }
        val ch = input.read()
        if (ch >= 0) position += 1
        return ch
    }

    private fun peek(): Int {
        if (lookahead == NONE) {
            lookahead = input.read()
            if (lookahead >= 0) position += 1
        }
        return lookahead
    }

    private fun fail(message: String): Nothing =
        throw BackupJsonException("$message（位置 $position）")

    fun skipWhitespace() {
        while (true) {
            val ch = peek()
            if (ch < 0) return
            if (ch == ' '.code || ch == '\n'.code || ch == '\r'.code || ch == '\t'.code) {
                read()
            } else {
                return
            }
        }
    }

    /** 读完之后必须已经没有内容（挡住「合法 JSON + 尾巴」这种请求）。 */
    fun expectEnd() {
        skipWhitespace()
        if (peek() >= 0) fail("JSON 末尾还有多余内容")
    }

    private fun expect(ch: Char) {
        skipWhitespace()
        val actual = peek()
        if (actual != ch.code) fail("期望 '$ch'")
        read()
    }

    // ------------------------------------------------------------------ 分段读取

    /** 消费一个 `{`，之后用 [nextKey] 逐个取键。 */
    fun beginObject() {
        expect('{')
        frames.addLast(Frame(isObject = true))
    }

    /** 消费一个 `[`，之后用 [hasNextElement] 逐个取元素。 */
    fun beginArray() {
        expect('[')
        frames.addLast(Frame(isObject = false))
    }

    /**
     * 当前对象的下一个键（已消费 `:`）；对象结束时消费 `}` 并返回 `null`。
     *
     * 调用方必须完整消费每个值（[readValue] / [skipValue] / 自定义读取），否则下一次
     * [nextKey] 会读到值里的字符并报错。
     */
    fun nextKey(): String? {
        val frame = frames.lastOrNull() ?: fail("内部错误：nextKey 没有对应的对象")
        if (!frame.isObject) fail("内部错误：nextKey 用在数组上")
        skipWhitespace()
        if (peek() == '}'.code) {
            read()
            frames.removeLast()
            return null
        }
        if (frame.count > 0) expect(',')
        skipWhitespace()
        if (peek() != '"'.code) fail("对象的键必须是字符串")
        val key = readString()
        expect(':')
        frame.count += 1
        if (frame.count > MAX_OBJECT_ENTRIES) fail("JSON 对象的键过多（上限 $MAX_OBJECT_ENTRIES 个）")
        return key
    }

    /** 当前数组还有元素吗？没有时消费 `]` 并返回 false。返回 true 后调用方必须消费一个元素。 */
    fun hasNextElement(): Boolean {
        val frame = frames.lastOrNull() ?: fail("内部错误：hasNextElement 没有对应的数组")
        if (frame.isObject) fail("内部错误：hasNextElement 用在对象上")
        skipWhitespace()
        if (peek() == ']'.code) {
            read()
            frames.removeLast()
            return false
        }
        if (frame.count > 0) expect(',')
        frame.count += 1
        if (frame.count > MAX_ARRAY_ENTRIES) fail("JSON 数组的元素过多（上限 $MAX_ARRAY_ENTRIES 个）")
        return true
    }

    // ------------------------------------------------------------------ 读值

    /** 读一个完整的 JSON 值（小对象/标量；媒体 `data` 不要走这里，见 [readBase64To]）。 */
    fun readValue(): JsonValue = readValueAt(0)

    private fun readValueAt(depth: Int): JsonValue {
        if (depth > MAX_DEPTH) fail("JSON 嵌套过深（> $MAX_DEPTH 层）")
        skipWhitespace()
        return when (val ch = peek()) {
            '{'.code -> {
                read()
                readObject(depth)
            }

            '['.code -> {
                read()
                readArray(depth)
            }

            '"'.code -> JsonValue.Str(readString())
            't'.code -> {
                expectWord("true")
                JsonValue.Bool(true)
            }

            'f'.code -> {
                expectWord("false")
                JsonValue.Bool(false)
            }

            'n'.code -> {
                expectWord("null")
                JsonValue.Null
            }

            -1 -> fail("JSON 意外结束")
            else -> if (ch == '-'.code || (ch >= '0'.code && ch <= '9'.code)) {
                readNumber()
            } else {
                fail("无法识别的 JSON 值")
            }
        }
    }

    private fun readObject(depth: Int): JsonValue.Obj {
        val obj = JsonValue.Obj()
        skipWhitespace()
        if (peek() == '}'.code) {
            read()
            return obj
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"'.code) fail("对象的键必须是字符串")
            val key = readString()
            expect(':')
            val value = readValueAt(depth + 1)
            if (obj.fields.containsKey(key)) fail("JSON 对象里有重复键")
            obj.fields[key] = value
            if (obj.fields.size > MAX_OBJECT_ENTRIES) fail("JSON 对象的键过多（上限 $MAX_OBJECT_ENTRIES 个）")
            skipWhitespace()
            when (peek()) {
                ','.code -> read()
                '}'.code -> {
                    read()
                    return obj
                }

                else -> fail("对象里期望 ',' 或 '}'")
            }
        }
    }

    private fun readArray(depth: Int): JsonValue.Arr {
        val arr = JsonValue.Arr()
        skipWhitespace()
        if (peek() == ']'.code) {
            read()
            return arr
        }
        while (true) {
            arr.items += readValueAt(depth + 1)
            if (arr.items.size > MAX_ARRAY_ENTRIES) fail("JSON 数组的元素过多（上限 $MAX_ARRAY_ENTRIES）")
            skipWhitespace()
            when (peek()) {
                ','.code -> read()
                ']'.code -> {
                    read()
                    return arr
                }

                else -> fail("数组里期望 ',' 或 ']'")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            val ch = read()
            if (ch < 0) fail("字符串没有闭合")
            when (ch) {
                '"'.code -> return out.toString()
                '\\'.code -> {
                    val escaped = read()
                    if (escaped < 0) fail("字符串转义没有写完")
                    when (escaped) {
                        '"'.code -> out.append('"')
                        '\\'.code -> out.append('\\')
                        '/'.code -> out.append('/')
                        'b'.code -> out.append('\b')
                        'f'.code -> out.append('\u000C')
                        'n'.code -> out.append('\n')
                        'r'.code -> out.append('\r')
                        't'.code -> out.append('\t')
                        'u'.code -> {
                            var code = 0
                            repeat(4) {
                                val hex = read()
                                if (hex < 0) fail("\\u 转义不完整")
                                val digit = Character.digit(hex.toChar(), 16)
                                if (digit < 0) fail("\\u 转义不是十六进制")
                                code = code * 16 + digit
                            }
                            out.append(code.toChar())
                        }

                        else -> fail("不支持的转义 '\\${escaped.toChar()}'")
                    }
                }

                else -> {
                    if (ch < 0x20) fail("字符串里有未转义的控制字符")
                    out.append(ch.toChar())
                }
            }
            if (out.length > MAX_STRING_CHARS) fail("字符串过长（上限 $MAX_STRING_CHARS 个字符）")
        }
    }

    private fun readNumber(): JsonValue.Num {
        val out = StringBuilder()
        fun take(): Int {
            val ch = read()
            if (ch < 0) fail("数字没有写完")
            out.append(ch.toChar())
            return ch
        }

        if (peek() == '-'.code) take()
        val first = peek()
        when {
            first == '0'.code -> take()
            first in '1'.code..'9'.code -> while (peek() in '0'.code..'9'.code) take()
            else -> fail("数字格式不对")
        }
        if (peek() == '.'.code) {
            take()
            if (peek() !in '0'.code..'9'.code) fail("小数点后缺少数字")
            while (peek() in '0'.code..'9'.code) take()
        }
        if (peek() == 'e'.code || peek() == 'E'.code) {
            take()
            if (peek() == '+'.code || peek() == '-'.code) take()
            if (peek() !in '0'.code..'9'.code) fail("指数部分缺少数字")
            while (peek() in '0'.code..'9'.code) take()
        }
        val raw = out.toString()
        if (raw.length > MAX_NUMBER_CHARS) fail("数字过长（上限 $MAX_NUMBER_CHARS 位）")
        val asDouble = raw.toDoubleOrNull()
        if (asDouble != null && !asDouble.isFinite()) fail("数字超出可表示范围")
        return JsonValue.Num(raw)
    }

    private fun expectWord(word: String) {
        for (ch in word) {
            if (read() != ch.code) fail("无法识别的 JSON 值")
        }
    }

    /** 跳过任意值（不建对象、不保留字符串）。未知字段用它，保证「多出来的字段」不占内存。 */
    fun skipValue() = skipValueAt(0)

    private fun skipValueAt(depth: Int) {
        if (depth > MAX_DEPTH) fail("JSON 嵌套过深（> $MAX_DEPTH 层）")
        skipWhitespace()
        when (val ch = peek()) {
            '{'.code -> {
                read()
                skipWhitespace()
                if (peek() == '}'.code) {
                    read()
                    return
                }
                while (true) {
                    skipWhitespace()
                    if (peek() != '"'.code) fail("对象的键必须是字符串")
                    readString()
                    expect(':')
                    skipValueAt(depth + 1)
                    skipWhitespace()
                    when (peek()) {
                        ','.code -> read()
                        '}'.code -> {
                            read()
                            return
                        }

                        else -> fail("对象里期望 ',' 或 '}'")
                    }
                }
            }

            '['.code -> {
                read()
                skipWhitespace()
                if (peek() == ']'.code) {
                    read()
                    return
                }
                while (true) {
                    skipValueAt(depth + 1)
                    skipWhitespace()
                    when (peek()) {
                        ','.code -> read()
                        ']'.code -> {
                            read()
                            return
                        }

                        else -> fail("数组里期望 ',' 或 ']'")
                    }
                }
            }

            '"'.code -> readString()
            't'.code -> expectWord("true")
            'f'.code -> expectWord("false")
            'n'.code -> expectWord("null")
            -1 -> fail("JSON 意外结束")
            else -> if (ch == '-'.code || (ch >= '0'.code && ch <= '9'.code)) readNumber() else fail("无法识别的 JSON 值")
        }
    }

    // ------------------------------------------------------------------ 流式 base64

    /**
     * 把当前字符串值当作 **base64** 读出来，边解码边写入 [dest]，返回解码后的字节数。
     *
     * 内存占用只有一个 3 字节的四位组缓冲：8 MiB 的图片不会变成 11 MiB 的 `String` +
     * 8 MiB 的 `ByteArray`。超过 [maxBytes] 立刻失败（不把超大 `data` 落满磁盘）。
     */
    fun readBase64To(dest: OutputStream, maxBytes: Long, what: String): Long {
        expect('"')
        val quartet = IntArray(4)
        var filled = 0
        var padding = 0
        var total = 0L
        val triple = ByteArray(3)
        while (true) {
            val ch = read()
            if (ch < 0) fail("$what 的 base64 字符串没有闭合")
            if (ch == '"'.code) break
            val value = base64Value(ch)
            if (value == -1) fail("$what 不是合法 base64（非法字符）")
            if (value == -2) {
                if (filled < 2) fail("$what 的 base64 填充位置不对")
                padding += 1
                if (padding > 2) fail("$what 的 base64 填充过多")
            } else {
                if (padding > 0) fail("$what 的 base64 填充后面还有数据")
            }
            quartet[filled] = value
            filled += 1
            if (filled == 4) {
                triple[0] = (((quartet[0] shl 2) or (quartet[1] shr 4)) and 0xff).toByte()
                triple[1] = (((quartet[1] shl 4) or (quartet[2] shr 2)) and 0xff).toByte()
                triple[2] = (((quartet[2] shl 6) or quartet[3]) and 0xff).toByte()
                val bytes = 3 - padding
                dest.write(triple, 0, bytes)
                total += bytes
                if (total > maxBytes) {
                    throw BackupValidationException(
                        "$what 的媒体超过 ${MAX_BACKUP_MEDIA_FILE_BYTES / (1024 * 1024)} MiB 单文件上限"
                    )
                }
                filled = 0
                if (padding > 0) {
                    // 填充之后只允许结束（标准 base64 里 padding 只出现在最后一组）。
                    val next = read()
                    if (next != '"'.code) {
                        if (next < 0) fail("$what 的 base64 字符串没有闭合")
                        fail("$what 的 base64 填充后面还有数据")
                    }
                    return total
                }
            }
        }
        if (filled != 0) fail("$what 的 base64 长度不是 4 的倍数")
        if (padding != 0) fail("$what 的 base64 不完整")
        return total
    }

    private fun base64Value(ch: Int): Int {
        val value = ch.toChar()
        return when {
            value in 'A'..'Z' -> value - 'A'
            value in 'a'..'z' -> value - 'a' + 26
            value in '0'..'9' -> value - '0' + 52
            value == '+' -> 62
            value == '/' -> 63
            value == '=' -> -2
            else -> -1
        }
    }
}

/**
 * 备份导出用的**流式 JSON 写入器**。
 *
 * 为什么不用 `Json.stringify`：它要求整棵树先构造出来、再变成一个 `String`。这里直接往
 * `Appendable`（生产是 socket 上的 chunked writer）写，内存只有每一条消息大小的小字符串。
 *
 * 转义规则与 `agentcore` 的 `Json.writeString` **逐字节一致**（否则「导出→导入→再导出」的
 * 文本比对会漂）：只转义 `"` `\` 与 < 0x20 的控制字符，其余原样输出（UTF-8）。
 */
internal class JsonStreamWriter(private val out: Appendable) {

    private class Frame(val isObject: Boolean) {
        var count: Int = 0
    }

    private val frames = ArrayDeque<Frame>()

    fun startObject() {
        out.append('{')
        frames.addLast(Frame(isObject = true))
    }

    fun endObject() {
        out.append('}')
        frames.removeLast()
    }

    fun startArray() {
        out.append('[')
        frames.addLast(Frame(isObject = false))
    }

    fun endArray() {
        out.append(']')
        frames.removeLast()
    }

    /** 写一个键（自动补逗号）。 */
    fun key(name: String) {
        val frame = frames.last()
        if (frame.count > 0) out.append(',')
        frame.count += 1
        appendEscaped(name, out)
        out.append(':')
    }

    /** 写一个数组元素的前缀（自动补逗号）；之后写值本身。 */
    fun element() {
        val frame = frames.last()
        if (frame.count > 0) out.append(',')
        frame.count += 1
    }

    /** 写任意 `JsonValue`（递归写，不构造中间字符串）。 */
    fun value(value: JsonValue) {
        when (value) {
            is JsonValue.Obj -> {
                out.append('{')
                var first = true
                for ((key, child) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    appendEscaped(key, out)
                    out.append(':')
                    value(child)
                }
                out.append('}')
            }

            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, child ->
                    if (index > 0) out.append(',')
                    value(child)
                }
                out.append(']')
            }

            is JsonValue.Str -> appendEscaped(value.value, out)
            is JsonValue.Num -> out.append(value.raw)
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            JsonValue.Null -> out.append("null")
        }
    }

    /** 写一个字符串值（带引号与转义）。 */
    fun stringValue(text: String) {
        appendEscaped(text, out)
    }

    fun number(value: Long) {
        out.append(value.toString())
    }

    fun number(value: Int) {
        out.append(value.toString())
    }

    fun bool(value: Boolean) {
        out.append(if (value) "true" else "false")
    }

    fun nullValue() {
        out.append("null")
    }

    /** 开始一个很长的字符串值（媒体 base64）：调用方随后用 [stringChunk] 逐段写入。 */
    fun beginLongString() {
        out.append('"')
    }

    /** 往 [beginLongString] 打开的字符串里追加一段（只做转义，不加外层引号）。 */
    fun stringChunk(text: String) {
        appendEscapedContent(text, out)
    }

    fun endLongString() {
        out.append('"')
    }

    private fun appendEscaped(text: String, target: Appendable) {
        target.append('"')
        appendEscapedContent(text, target)
        target.append('"')
    }

    private fun appendEscapedContent(text: String, target: Appendable) {
        for (ch in text) {
            when (ch) {
                '"' -> target.append("\\\"")
                '\\' -> target.append("\\\\")
                '\n' -> target.append("\\n")
                '\r' -> target.append("\\r")
                '\t' -> target.append("\\t")
                '\b' -> target.append("\\b")
                '\u000C' -> target.append("\\f")
                else -> if (ch < ' ') {
                    target.append("\\u")
                    val hex = ch.code.toString(16)
                    repeat(4 - hex.length) { target.append('0') }
                    target.append(hex)
                } else {
                    target.append(ch)
                }
            }
        }
    }
}
