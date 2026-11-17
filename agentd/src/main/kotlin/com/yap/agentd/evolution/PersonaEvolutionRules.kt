package com.yap.agentd.evolution

import com.yap.agentd.persona.PersonaFields
import com.yap.agentd.store.Mood
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonParseException
import com.yap.app.core.json.JsonValue
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 人格进化评估的**规则层**（服务端 0.13 移植，语义与客户端 `PersonaEvolution` 一致）。
 *
 * 模型只被允许说四件事：性格（traits）、喜欢的事（likes）、习惯（behaviors）、设定（quirks）。
 * 它给的东西一律不可信：条数、字段名、操作符、置信度、value 长度、重复、是否存在于当前人格，
 * 全部由纯函数过滤（[parse] → [sanitize] → [apply]）；坏 JSON 绝不抛异常，只退化成
 * 「能用的最大子集」。
 *
 * 与客户端的两点差异（都是"服务端权威"的直接结果）：
 * 1. 规则实现放在 `:agentd`（不能依赖 `:app`），但阈值逐条相同：
 *    一次 ≤[MAX_CHANGES] 条、add ≤[MAX_ADDS]、remove ≤[MAX_REMOVES]、置信度 ≥[MIN_CONFIDENCE]、
 *    value ≤[VALUE_MAX_CHARS] 字、列表上限 6/8/6、quirks 300 字；
 * 2. [userPrompt] 多一段「当前情绪」：服务端持有心情，评估时把多维情绪一起给模型
 *    （`spec/mood/README.md` §2「结果写库并作为下一轮提示词的一部分」）。
 *
 * 纯函数：不读库、不联网、不看时钟（时间由调用方拼好传进来）。
 */
object PersonaEvolutionRules {

    /** 可被增删的四组字段；`wireValue` 是模型输出、补丁 JSON 里使用的字符串。 */
    enum class Field(val wireValue: String) {
        TRAITS("traits"),
        LIKES("likes"),
        BEHAVIORS("behaviors"),
        QUIRKS("quirks");

        companion object {
            fun fromWire(raw: String?): Field? {
                val cleaned = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
                if (cleaned.isEmpty()) return null
                return entries.firstOrNull { it.wireValue == cleaned }
            }
        }
    }

    enum class Op(val wireValue: String) {
        ADD("add"),
        REMOVE("remove");

        companion object {
            fun fromWire(raw: String?): Op? {
                val cleaned = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
                if (cleaned.isEmpty()) return null
                return entries.firstOrNull { it.wireValue == cleaned }
            }
        }
    }

    data class Change(
        val field: Field,
        val op: Op,
        val value: String,
        val reason: String = "",
        val confidence: Double = 0.0
    )

    const val MAX_CHANGES: Int = 3
    const val MAX_ADDS: Int = 2
    const val MAX_REMOVES: Int = 1
    const val MIN_CONFIDENCE: Double = 0.6
    const val VALUE_MAX_CHARS: Int = 40
    private const val REASON_MAX_CHARS = 120

    private const val NO_SUMMARY = "（没有可用的最近对话）"
    private const val EMPTY_PATCH = """{"changes":[]}"""

    private val whitespace = Regex("\\s+")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    private const val SENTENCE_ENDINGS = "。！？!?；;…"

    // ---------------------------------------------------------------- 解析

    /** 模型输出 → 变更（不套上限；上限在 [sanitize]）。坏数据返回空列表。 */
    fun parse(raw: String): List<Change> = changesFrom(parseRoot(raw))

    /** 待审补丁 JSON → 变更（接受时回放用；形状与模型输出相同，直接复用 [parse]）。 */
    fun decodePatch(patchJson: String): List<Change> = parse(patchJson)

    // ---------------------------------------------------------------- 规则

    /**
     * 字段白名单 + 上限 + 置信度 + 去重 + 与当前值比对；输出顺序稳定（先 add 后 remove）。
     *
     * 逐条判定、超限**跳过该条**而不是整体截断：后面低分的 remove 仍然可能挤进来。
     */
    fun sanitize(changes: List<Change>, current: PersonaFields.Current): List<Change> {
        if (changes.isEmpty()) return emptyList()
        val ordered = changes.mapNotNull(::qualify).sortedByDescending { it.confidence }
        val adds = mutableListOf<Change>()
        val removes = mutableListOf<Change>()
        val addedKeys = mutableSetOf<String>()
        val removedKeys = mutableSetOf<String>()
        for (change in ordered) {
            if (adds.size + removes.size >= MAX_CHANGES) break
            val key = "${change.field.wireValue}:${keyOf(change.value)}"
            when (change.op) {
                Op.ADD -> {
                    if (adds.size >= MAX_ADDS) continue
                    if (key in addedKeys || key in removedKeys) continue
                    if (existsIn(current, change.field, change.value)) continue
                    addedKeys += key
                    adds += change
                }

                Op.REMOVE -> {
                    if (removes.size >= MAX_REMOVES) continue
                    if (key in removedKeys || key in addedKeys) continue
                    if (!existsIn(current, change.field, change.value)) continue
                    removedKeys += key
                    removes += change
                }
            }
        }
        return adds + removes
    }

    // ---------------------------------------------------------------- 应用

    /**
     * 把变更应用到 [current]（纯函数）。
     *
     * 上限由 [PersonaFields] 的四个常量兜底（6 / 8 / 6 / 300）：超了就**丢掉新值**，
     * 绝不截断已有值。列表去重保留首次出现；quirks 的 add 追加一句（换行分隔、已含则幂等），
     * remove 删掉包含该片段的整句/整行。
     */
    fun apply(current: PersonaFields.Current, changes: List<Change>): PersonaFields.Current {
        var result = current
        for (change in changes) {
            val value = normalizeValue(change.value)
            if (value.isBlank()) continue
            result = when (change.field) {
                Field.TRAITS -> result.copy(
                    traits = updatedList(result.traits, change.op, value, PersonaFields.MAX_TRAITS)
                )

                Field.LIKES -> result.copy(
                    likes = updatedList(result.likes, change.op, value, PersonaFields.MAX_LIKES)
                )

                Field.BEHAVIORS -> result.copy(
                    behaviors = updatedList(result.behaviors, change.op, value, PersonaFields.MAX_BEHAVIORS)
                )

                Field.QUIRKS -> result.copy(quirks = updatedQuirks(result.quirks, change.op, value))
            }
        }
        return result
    }

    // ---------------------------------------------------------------- 序列化 / 文案

    /**
     * 变更 → 待审补丁 JSON：`{"changes":[{"field":"traits","op":"add","value":"…","reason":"…"}]}`。
     *
     * 只写四个键，不写 confidence：补丁是给人审的，[decodePatch] 会把缺失的 confidence
     * 读成 0.0，不影响 [apply]。
     */
    fun encodePatch(changes: List<Change>): String = runCatching {
        val items = changes.mapTo(mutableListOf<JsonValue>()) { change ->
            JsonValue.Obj(
                linkedMapOf(
                    "field" to JsonValue.Str(change.field.wireValue),
                    "op" to JsonValue.Str(change.op.wireValue),
                    "value" to JsonValue.Str(normalizeValue(change.value)),
                    "reason" to JsonValue.Str(oneLine(change.reason).take(REASON_MAX_CHARS))
                )
            )
        }
        Json.stringify(JsonValue.Obj(linkedMapOf("changes" to JsonValue.Arr(items))))
    }.getOrDefault(EMPTY_PATCH)

    /** 给用户看的一行中文摘要，例如「性格 +嘴硬心软；喜欢的事 −夜里散步」；无变更返回空串。 */
    fun summary(changes: List<Change>): String = changes.mapNotNull { change ->
        val value = normalizeValue(change.value)
        if (value.isBlank()) return@mapNotNull null
        "${labelOf(change.field)} ${symbolOf(change.op)}$value"
    }.joinToString("；")

    // ---------------------------------------------------------------- 提示词

    /** 评估请求的 system 提示词（与客户端 `PersonaEvolution.systemPrompt` 同一套约束）。 */
    fun systemPrompt(): String = """
        你是「人格进化评估器」。你会看到某个 AI 联系人（下称「他」）当前的人格四组值、
        当前情绪，以及最近一段对话的摘要。你的任务：判断他在这一段时间里有没有真的变了。
        只输出一个 JSON 对象，不要解释、不要客套、不要 markdown 代码块。

        输出格式（字段名必须完全一样）：
        {"changes":[{"field":"traits","op":"add","value":"嘴硬心软","reason":"最近几次都先顶一句再软下来","confidence":0.8}]}

        字段白名单（只有这四个，写别的整条作废）：
        - traits：性格，例如「慢热」「嘴硬心软」
        - likes：喜欢的事，例如「夜里散步」「雨天在家看书」
        - behaviors：习惯，例如「先听完再给建议」
        - quirks：设定（怪癖、口头禅、雷点），一段自由文本，一句话一条

        op 只能是 add 或 remove：
        - add：加一条人格里还没有的新东西；
        - remove：删一条人格里已经有的东西，value 必须和原文对得上。

        硬约束（违反会被直接丢掉）：
        - 一次最多 3 条变更：add 最多 2 条，remove 最多 1 条。
        - confidence 是 0 到 1 之间的小数，低于 0.6 的会被丢弃；拿不准就写低一点，宁可少改。
        - value 一句话、最多 40 字，不要引号、不要编号、不要换行。
        - 已经有的值（意思相同也算）不要重复 add；不存在于当前人格的值不要 remove。
        - 没有值得改的就输出 {"changes":[]}。空补丁永远比乱改好。

        反例（这些都不要做）：
        - 不因一次争吵就删掉「温柔」：一次情绪不是人格变化，要最近多次都这样才算。
        - 不把「今天加班到很晚」当成人格：临时状态不是 traits。
        - 不把同一件事拆成三条 add 凑数。
        - 不因为对方夸了一句就加「喜欢被夸」。
    """.trimIndent()

    /**
     * 评估请求的 user 提示词：人格四组值 + 当前情绪 + 最近对话摘要 + 现在时间。
     *
     * @param now 毫秒时刻（0 = 不写时间行）；时区跟随服务端
     */
    fun userPrompt(
        personaName: String,
        current: PersonaFields.Current,
        recentSummary: String,
        mood: Mood? = null,
        now: Long = 0L,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val name = oneLine(personaName).ifBlank { "他" }
        val lines = mutableListOf<String>()
        lines += "【人格进化评估】"
        lines += "联系人：$name"
        if (now > 0L) nowStamp(now, zone)?.let { lines += "现在时间：$it" }
        lines += ""
        lines += "## 当前人格"
        lines += groupLine("性格", Field.TRAITS, current.traits)
        lines += groupLine("喜欢的事", Field.LIKES, current.likes)
        lines += groupLine("习惯", Field.BEHAVIORS, current.behaviors)
        lines += if (current.quirks.isBlank()) {
            groupLine("设定", Field.QUIRKS, emptyList())
        } else {
            "设定（quirks）：${current.quirks}"
        }
        lines += ""
        lines += "## 当前情绪"
        lines += moodLine(mood)
        lines += ""
        lines += "## 最近对话摘要"
        lines += recentSummary.trim().ifBlank { NO_SUMMARY }
        lines += ""
        lines += """请按 system 说明的格式只输出一个 JSON 对象；没有值得改的就输出 {"changes":[]}。"""
        return lines.joinToString("\n")
    }

    /** 「label（强度 x/5），愉快度 v/2、唤醒度 a/5、掌控感 d/5」；没有记录时给一句占位。 */
    fun moodLine(mood: Mood?): String {
        if (mood == null || mood.label.isBlank()) return "（没有记录；按平静处理）"
        return "${mood.label}（强度 ${mood.intensity.coerceIn(1, 5)}/5），" +
            "愉快度 ${mood.valence} / 唤醒度 ${mood.arousal}/5 / 掌控感 ${mood.dominance}/5"
    }

    // ---------------------------------------------------------------- 解析细节

    private fun parseRoot(raw: String?): JsonValue? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        val candidates = listOfNotNull(
            text,
            bracketSlice(text, '{', '}'),
            bracketSlice(text, '[', ']')
        ).distinct()
        for (candidate in candidates) {
            val parsed = runCatching { Json.parse(candidate) }.getOrNull()
            if (parsed is JsonValue.Obj || parsed is JsonValue.Arr) return parsed
        }
        return null
    }

    private fun bracketSlice(text: String, open: Char, close: Char): String? {
        val start = text.indexOf(open)
        if (start < 0) return null
        val end = text.lastIndexOf(close)
        if (end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun changesFrom(root: JsonValue?): List<Change> = when (root) {
        is JsonValue.Arr -> root.items.mapNotNull { changeFrom(it as? JsonValue.Obj) }
        is JsonValue.Obj -> (root.fields["changes"] as? JsonValue.Arr)
            ?.items
            ?.mapNotNull { changeFrom(it as? JsonValue.Obj) }
            ?: emptyList()

        else -> emptyList()
    }

    private fun changeFrom(raw: JsonValue.Obj?): Change? {
        if (raw == null) return null
        val field = Field.fromWire(stringOf(raw.fields["field"])) ?: return null
        val op = Op.fromWire(stringOf(raw.fields["op"])) ?: return null
        val value = stringOf(raw.fields["value"])?.let(::oneLine).orEmpty()
        if (value.isBlank()) return null
        return Change(
            field = field,
            op = op,
            value = value,
            reason = stringOf(raw.fields["reason"])?.let(::oneLine)?.take(REASON_MAX_CHARS).orEmpty(),
            confidence = confidenceOf(raw.fields["confidence"])
        )
    }

    private fun stringOf(value: JsonValue?): String? = (value as? JsonValue.Str)?.value

    private fun confidenceOf(value: JsonValue?): Double {
        val raw = when (value) {
            is JsonValue.Num -> value.raw
            is JsonValue.Str -> value.value
            else -> return 0.0
        }.trim()
        val parsed = raw.toDoubleOrNull() ?: return 0.0
        if (!parsed.isFinite()) return 0.0
        return parsed.coerceIn(0.0, 1.0)
    }

    // ---------------------------------------------------------------- 规则细节

    private fun qualify(change: Change): Change? {
        if (!(change.confidence >= MIN_CONFIDENCE)) return null
        val value = normalizeValue(change.value)
        if (value.isBlank()) return null
        return change.copy(
            value = value,
            reason = oneLine(change.reason).take(REASON_MAX_CHARS),
            confidence = change.confidence.coerceIn(0.0, 1.0)
        )
    }

    private fun existsIn(current: PersonaFields.Current, field: Field, value: String): Boolean {
        val key = keyOf(value)
        if (key.isBlank()) return false
        return when (field) {
            Field.TRAITS -> current.traits.any { keyOf(it) == key }
            Field.LIKES -> current.likes.any { keyOf(it) == key }
            Field.BEHAVIORS -> current.behaviors.any { keyOf(it) == key }
            Field.QUIRKS -> quirkSegments(current.quirks).any { keyOf(it).contains(key) }
        }
    }

    private fun updatedList(items: List<String>, op: Op, value: String, max: Int): List<String> {
        val kept = items.mapNotNull { raw -> oneLine(raw).takeIf { it.isNotBlank() } }
            .distinctBy { keyOf(it) }
        val key = keyOf(value)
        return when (op) {
            Op.ADD -> if (kept.size >= max || kept.any { keyOf(it) == key }) kept else kept + value
            Op.REMOVE -> kept.filterNot { keyOf(it) == key }
        }
    }

    private fun updatedQuirks(text: String, op: Op, value: String): String {
        val existing = text.trim()
        return when (op) {
            Op.ADD -> when {
                existing.isBlank() -> value
                quirkSegments(existing).any { keyOf(it).contains(keyOf(value)) } -> existing
                else -> {
                    val merged = existing + "\n" + value
                    if (merged.length > PersonaFields.MAX_QUIRKS) existing else merged
                }
            }

            Op.REMOVE -> removeQuirkFragment(existing, value)
        }
    }

    private fun removeQuirkFragment(text: String, fragment: String): String {
        if (text.isBlank()) return text
        val needle = keyOf(fragment)
        if (needle.isBlank()) return text
        val keptLines = text.split('\n').mapNotNull { line ->
            splitSentences(line)
                .filterNot { keyOf(it).contains(needle) }
                .joinToString("")
                .trim()
                .ifBlank { null }
        }
        return keptLines.joinToString("\n").trim()
    }

    private fun splitSentences(line: String): List<String> {
        if (line.isEmpty()) return emptyList()
        val sentences = mutableListOf<String>()
        val buffer = StringBuilder()
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            buffer.append(ch)
            index++
            if (ch in SENTENCE_ENDINGS) {
                while (index < line.length && ch == '…' && line[index] == '…') {
                    buffer.append(line[index])
                    index++
                }
                sentences += buffer.toString()
                buffer.clear()
            }
        }
        if (buffer.isNotBlank()) sentences += buffer.toString()
        return sentences
    }

    private fun quirkSegments(text: String): List<String> =
        text.split('\n')
            .flatMap { splitSentences(it) }
            .mapNotNull { oneLine(it).takeIf { cleaned -> cleaned.isNotBlank() } }

    // ---------------------------------------------------------------- 文本工具

    private fun oneLine(raw: String): String =
        raw.replace('\u3000', ' ').replace('\u00A0', ' ').trim().replace(whitespace, " ")

    private fun normalizeValue(raw: String): String = oneLine(raw).take(VALUE_MAX_CHARS)

    private fun keyOf(raw: String): String = oneLine(raw).lowercase(Locale.ROOT)

    private fun labelOf(field: Field): String = when (field) {
        Field.TRAITS -> "性格"
        Field.LIKES -> "喜欢的事"
        Field.BEHAVIORS -> "习惯"
        Field.QUIRKS -> "设定"
    }

    private fun symbolOf(op: Op): String = if (op == Op.ADD) "+" else "\u2212"

    private fun groupLine(label: String, field: Field, values: List<String>): String =
        if (values.isEmpty()) {
            "$label（${field.wireValue}）：（暂无）"
        } else {
            "$label（${field.wireValue}）：" + values.joinToString("、")
        }

    private fun nowStamp(now: Long, zone: ZoneId): String? = runCatching {
        ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone).format(timeFormat)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
