package com.yap.agentd.llm

import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue

/**
 * 兼容「模型不走 function calling、改在正文里写 XML 工具标签」的兜底解析
 * （0.15 评测发现的真实问题：DeepSeek 基线会输出 `<tool_call>…</tool_call>` /
 * `<tool_code>` / `<tool>` 三种标签，服务端此前只认 `tool_calls` 字段，于是：
 * 标签被当成正文发给用户、工具没执行、模型下一轮还在等结果）。
 *
 * 三条处理规则：
 * 1. **流式也要吞掉标签**：标签可能被拆在多个 delta 里（`<tool_` + `call>`），
 *    所以 [StreamFilter] 用「待定尾巴」缓冲，只在确认不是标签前缀时才把文字发出去；
 * 2. **三种标签都认**：`<tool_call>`、`<tool_code>`、`<tool>`（含 `<tool name="x" …>` 属性写法）；
 * 3. **归一化**：解析结果变成普通的 [LlmToolCall]（带 `id` 与 JSON 字符串参数），
 *    交给 `LlmReplyGenerator` 走同一条工具回路——模型用哪种写法都落到同一套执行/回灌逻辑。
 *
 * 支持的内容形状（同一个标签里出现哪种都行）：
 * - `{"name":"remember","arguments":{"content":"…"}}`（也认 `tool` / `tool_name` / `args` / `parameters`）；
 * - `[{"name":…}, {"name":…}]`（一次要调多个）；
 * - `remember({"content":"…"})`（函数调用写法）；
 * - `remember(content="…", importance=4)`（Python 关键字参数写法）。
 *
 * 刻意不做的：**不猜语义**。标签里既没有名字也没有合法参数时直接丢弃（当成模型的坏输出），
 * 不把一段散文硬塞成工具调用。
 */
object XmlToolCalls {

    /** 三种标签名（评测里出现的三种写法）。 */
    val TAG_NAMES: List<String> = listOf("tool_call", "tool_code", "tool")

    private val OPEN_REGEX = Regex("<\\s*(tool_call|tool_code|tool)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val CLOSE_REGEX = Regex("</\\s*(tool_call|tool_code|tool)\\s*>", RegexOption.IGNORE_CASE)
    private val NAME_ATTRIBUTE = Regex("name\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    /** 一个标签里最多攒多少字符（防「模型一直不闭合」把内存吃满）。 */
    const val MAX_BLOCK_CHARS = 8_192

    /** 一次回复里最多认几个 XML 工具调用（与工具回路的上限同量级）。 */
    const val MAX_CALLS = 15

    /** 一段文字里是否有工具标签（测试/日志用；不改变文本）。 */
    fun containsToolTag(text: String): Boolean = OPEN_REGEX.containsMatchIn(text)

    /** 一次性解析（非流式响应 / 已收集的完整文本）。 */
    fun extract(text: String): Parsed {
        if (text.isBlank()) return Parsed(text, emptyList())
        val calls = ArrayList<LlmToolCall>()
        val cleaned = StringBuilder()
        var cursor = 0
        while (true) {
            val open = OPEN_REGEX.find(text, cursor) ?: break
            cleaned.append(text, cursor, open.range.first)
            val close = CLOSE_REGEX.find(text, open.range.last + 1)
            if (close == null) {
                // 没有闭合：整段当坏输出丢掉（不把标签内容发给用户）。
                cursor = text.length
                break
            }
            val body = text.substring(open.range.last + 1, close.range.first)
            calls += parseBlock(open.value, body)
            cursor = close.range.last + 1
        }
        if (cursor < text.length) cleaned.append(text, cursor, text.length)
        return Parsed(cleaned.toString().trim(), calls.take(MAX_CALLS))
    }

    /** 解析结果：`text` 是**去掉标签之后**的正文，`calls` 是归一化后的工具调用。 */
    data class Parsed(val text: String, val calls: List<LlmToolCall>)

    /**
     * 流式过滤器：`accept(delta)` 只把「确认不是工具标签」的文字转给 [emit]，
     * `finish()` 返回这一轮从标签里解析出来的工具调用。
     *
     * 有意的取舍：标签前缀最多让它多缓冲 15 个字符（`</tool_call>` 的长度），
     * 这点延迟用户感知不到，但换来「标签一个字符都不会流到客户端」。
     */
    class StreamFilter(private val emit: (String) -> Unit) {

        private val pending = StringBuilder()
        private val blocks = ArrayList<Pair<String, String>>()
        private val output = StringBuilder()
        private var openTag: String? = null

        /** 到目前为止已经 emit 出去的（去标签后的）正文。 */
        val text: String get() = output.toString()

        fun accept(delta: String) {
            if (delta.isEmpty()) return
            pending.append(delta)
            drain(end = false)
        }

        fun finish(): List<LlmToolCall> {
            drain(end = true)
            val calls = ArrayList<LlmToolCall>()
            for ((tag, body) in blocks) {
                calls += parseBlock(tag, body)
                if (calls.size >= MAX_CALLS) break
            }
            return calls.take(MAX_CALLS)
        }

        private fun drain(end: Boolean) {
            while (true) {
                val text = pending.toString()
                if (openTag == null) {
                    val match = OPEN_REGEX.find(text)
                    val partial = partialOpenIndex(text)
                    if (match != null && (partial < 0 || match.range.first <= partial)) {
                        emitText(text.substring(0, match.range.first))
                        pending.delete(0, match.range.last + 1)
                        openTag = match.value
                        continue
                    }
                    if (partial > 0) {
                        emitText(text.substring(0, partial))
                        pending.delete(0, partial)
                        if (end) {
                            emitText(pending.toString())
                            pending.clear()
                        }
                        return
                    }
                    if (partial == 0 && !end) return
                    emitText(text)
                    pending.clear()
                    return
                }
                // 已经在一个工具标签里：等闭合标签。
                val close = CLOSE_REGEX.find(text)
                if (close != null) {
                    val body = text.substring(0, close.range.first)
                    blocks += (openTag ?: "") to body
                    pending.delete(0, close.range.last + 1)
                    openTag = null
                    continue
                }
                if (end || text.length > MAX_BLOCK_CHARS) {
                    // 一直没闭合：这一块当坏输出丢掉（不把内容发给用户）。
                    pending.clear()
                    openTag = null
                }
                return
            }
        }

        private fun emitText(chunk: String) {
            if (chunk.isEmpty()) return
            output.append(chunk)
            emit(chunk)
        }

        /** 末尾「可能是某个开标签前缀」的起始下标；没有返回 -1。 */
        private fun partialOpenIndex(text: String): Int {
            val max = minOf(text.length, MAX_OPEN_TAG_PREFIX)
            for (length in max downTo 1) {
                val suffix = text.substring(text.length - length)
                if (suffix.first() != '<') continue
                if (TAG_NAMES.any { tag -> ("<" + tag).startsWith(suffix, ignoreCase = true) || suffix.startsWith("<" + tag, ignoreCase = true) }) {
                    return text.length - length
                }
            }
            return -1
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 解析标签内容；解析不出来返回空列表（坏输出不该变成工具调用）。 */
    internal fun parseBlock(tag: String, rawBody: String): List<LlmToolCall> {
        val body = rawBody.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (body.isEmpty()) return emptyList()
        val attributeName = NAME_ATTRIBUTE.find(tag)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        val json = runCatching { Json.parse(body) }.getOrNull()
        when (json) {
            is JsonValue.Obj -> callFromJson(json, attributeName)?.let { return listOf(it) }
            is JsonValue.Arr -> {
                val calls = json.items.mapNotNull { item -> (item as? JsonValue.Obj)?.let { callFromJson(it, attributeName) } }
                if (calls.isNotEmpty()) return calls
            }

            else -> Unit
        }

        functionCall(body, attributeName)?.let { return listOf(it) }

        // 兜底：正文里先给一个名字，后面跟一段 JSON 参数（例如 `remember {"content":"…"}`）。
        val brace = body.indexOf('{')
        if (brace > 0) {
            val name = body.substring(0, brace).trim().trimEnd(':', '：', '(', ' ').trim()
            val argsText = body.substring(brace).trim().trimEnd(')', ';', ' ')
            if (isToolName(name)) {
                val args = runCatching { Json.parse(argsText) }.getOrNull() as? JsonValue.Obj
                if (args != null) return listOf(call(name, argsText))
            }
        }
        return emptyList()
    }

    private fun callFromJson(obj: JsonValue.Obj, attributeName: String): LlmToolCall? {
        val function = obj.fields["function"] as? JsonValue.Obj
        val explicitName = (obj.fields["name"] as? JsonValue.Str)?.value
            ?: (obj.fields["tool"] as? JsonValue.Str)?.value
            ?: (obj.fields["tool_name"] as? JsonValue.Str)?.value
            ?: (function?.fields?.get("name") as? JsonValue.Str)?.value
        if (explicitName == null) {
            // `<tool name="recall">{"query":"…"}</tool>`：标签属性给名字，标签内容**就是参数**。
            if (attributeName.isBlank() || !isToolName(attributeName)) return null
            return call(attributeName, Json.stringify(obj))
        }
        if (!isToolName(explicitName)) return null
        val arguments: JsonValue? = obj.fields["arguments"]
            ?: obj.fields["args"]
            ?: obj.fields["parameters"]
            ?: obj.fields["input"]
            ?: function?.fields?.get("arguments")
        val argsText = when (arguments) {
            null, JsonValue.Null -> "{}"
            is JsonValue.Str -> arguments.value.trim().ifEmpty { "{}" }
            else -> Json.stringify(arguments)
        }
        return call(explicitName, argsText)
    }

    private val FUNCTION_CALL = Regex("^([A-Za-z_][A-Za-z0-9_.\\-]*)\\s*\\((.*)\\)\\s*;?$", RegexOption.DOT_MATCHES_ALL)

    private fun functionCall(body: String, attributeName: String): LlmToolCall? {
        val match = FUNCTION_CALL.find(body) ?: return null
        var name = match.groupValues[1].trim()
        var argsRaw = match.groupValues[2].trim()
        if (!isToolName(name)) {
            // `print(remember(content="…"))` 这种：取最内层的工具名。
            val inner = FUNCTION_CALL.find(argsRaw) ?: return null
            name = inner.groupValues[1].trim()
            argsRaw = inner.groupValues[2].trim()
            if (!isToolName(name)) return null
        }
        if (argsRaw.isEmpty()) return call(name, "{}")
        val asJson = runCatching { Json.parse(argsRaw) }.getOrNull()
        if (asJson is JsonValue.Obj) return call(name, argsRaw)
        val kwargs = kwargsToJson(argsRaw)
        return call(name, kwargs)
    }

    /**
     * Python 关键字参数 → JSON：`content="明天带伞", importance=4, tags=["a"]`。
     *
     * 只支持「标量 + 数组 + 嵌套对象」这一层：模型写 XML 工具调用时不会真的写代码，
     * 够用即可；解析不了就返回 `{}`（工具自己会回一条「缺字段」，模型下一轮能纠正）。
     */
    internal fun kwargsToJson(raw: String): String {
        val fields = LinkedHashMap<String, JsonValue>()
        var index = 0
        while (index < raw.length) {
            while (index < raw.length && (raw[index] == ',' || raw[index].isWhitespace())) index += 1
            if (index >= raw.length) break
            val keyStart = index
            while (index < raw.length && (raw[index].isLetterOrDigit() || raw[index] == '_' || raw[index] == '-')) index += 1
            val key = raw.substring(keyStart, index).trim()
            while (index < raw.length && raw[index].isWhitespace()) index += 1
            if (key.isEmpty() || index >= raw.length || raw[index] != '=') break
            index += 1
            while (index < raw.length && raw[index].isWhitespace()) index += 1
            val valueStart = index
            var depth = 0
            var inString = false
            var quote = ' '
            while (index < raw.length) {
                val ch = raw[index]
                if (inString) {
                    if (ch == '\\') {
                        index += 2
                        continue
                    }
                    if (ch == quote) inString = false
                } else {
                    when (ch) {
                        '"', '\'' -> {
                            inString = true
                            quote = ch
                        }

                        '(', '[', '{' -> depth += 1
                        ')', ']', '}' -> depth -= 1
                        ',' -> if (depth == 0) break
                    }
                }
                index += 1
            }
            val valueText = raw.substring(valueStart, index).trim()
            fields[key] = scalar(valueText)
        }
        return if (fields.isEmpty()) "{}" else Json.stringify(JsonValue.Obj(fields))
    }

    private fun scalar(raw: String): JsonValue {
        val text = raw.trim()
        if (text.isEmpty()) return JsonValue.Null
        if (text == "None" || text == "null") return JsonValue.Null
        if (text == "True" || text == "true") return JsonValue.Bool(true)
        if (text == "False" || text == "false") return JsonValue.Bool(false)
        if (text.startsWith("'") && text.endsWith("'") && text.length >= 2) {
            return JsonValue.Str(text.substring(1, text.length - 1).replace("\\'", "'"))
        }
        if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
            return JsonValue.Str(text.substring(1, text.length - 1).replace("\\\"", "\""))
        }
        if (text.first() == '[' || text.first() == '{') {
            return runCatching { Json.parse(text) }.getOrNull() ?: JsonValue.Str(text)
        }
        text.toLongOrNull()?.let { return JsonValue.Num(it.toString()) }
        text.toDoubleOrNull()?.let { return JsonValue.Num(it.toString()) }
        return JsonValue.Str(text)
    }

    private fun call(name: String, arguments: String): LlmToolCall =
        LlmToolCall(id = "xml_" + nextId(), name = name.trim(), arguments = arguments.ifBlank { "{}" })

    private var counter = 0

    @Synchronized
    private fun nextId(): Int {
        counter += 1
        return counter
    }

    private fun isToolName(name: String): Boolean =
        name.isNotBlank() && name.length <= 64 && name.matches(TOOL_NAME)

    private val TOOL_NAME = Regex("[A-Za-z_][A-Za-z0-9_.\\-]*")

    /** 开标签前缀的最大长度（`<tool_call` = 10，留余量）。 */
    private const val MAX_OPEN_TAG_PREFIX = 12
}
