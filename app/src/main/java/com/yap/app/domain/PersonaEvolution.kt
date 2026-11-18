package com.yap.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 0.13 的「人格进化评估」：每 20 条用户消息或 24 小时，让模型看一次最近聊天，
 * 判断「他」这一段时间里有没有真的变了，产出一小组可审的字段变更（下称「补丁」）。
 *
 * 模型只被允许说四件事：性格（traits）、喜欢的事（likes）、习惯（behaviors）、设定（quirks）。
 * 它给的东西**一律不可信**：条数、字段名、操作符、置信度、value 长度、重复、是否存在于当前人格，
 * 全部由这里的纯函数过滤一遍（[parse] → [sanitize] → [apply]），坏 JSON 绝不抛异常，
 * 只退化成「能用的最大子集」。
 *
 * 两条刻意的设计：
 * 1. **解析与规则分开**。[parse] 只负责把模型输出挤成 [Change]，不套上限；
 *    [sanitize] 才套白名单/上限/置信度/去重，所以同一份补丁可以在不同时间点、
 *    对不同版本的人格反复 sanitize（例如待审补丁落库后又被接受）。
 * 2. **quirks 是文本，不是列表**。数据层里它是一整段自由文本（可多行、可多句），
 *    所以 add 是「追加一句」，remove 是「删掉包含该片段的整句/整行」，
 *    粒度判断放在 [apply] 里，而不是把它硬拆成列表再拼回去。
 *
 * 本文件不碰网络、数据库、Android API，也不引入除 kotlinx.serialization.json 以外的依赖。
 */
object PersonaEvolution {

    /** 可被增删的四组字段；[wireValue] 是模型输出、待审补丁 JSON 里使用的字符串。 */
    enum class Field(val wireValue: String) {
        TRAITS("traits"),
        LIKES("likes"),
        BEHAVIORS("behaviors"),
        QUIRKS("quirks");

        companion object {
            /** 只认四个 wire 值，大小写不敏感；中文字段名、近义词、别的单词一律返回 null。 */
            fun fromWire(raw: String?): Field? {
                val cleaned = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
                if (cleaned.isEmpty()) return null
                return entries.firstOrNull { it.wireValue == cleaned }
            }
        }
    }

    /** 一条变更只有两种操作；[wireValue] 同样用于模型输出与补丁 JSON。 */
    enum class Op(val wireValue: String) {
        ADD("add"),
        REMOVE("remove");

        companion object {
            /** 只认 add/remove，大小写不敏感；其它返回 null。 */
            fun fromWire(raw: String?): Op? {
                val cleaned = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
                if (cleaned.isEmpty()) return null
                return entries.firstOrNull { it.wireValue == cleaned }
            }
        }
    }

    /** 一条待审的人格变更；[confidence] 是模型自评（0..1），[reason] 只给人看。 */
    data class Change(
        val field: Field,
        val op: Op,
        val value: String,
        val reason: String = "",
        val confidence: Double = 0.0
    )

    /** 人格当前的四组值；quirks 是一整段文本（可空），其余是列表。 */
    data class Current(
        val traits: List<String> = emptyList(),
        val likes: List<String> = emptyList(),
        val behaviors: List<String> = emptyList(),
        val quirks: String = ""
    )

    /** 一次评估最多留几条变更。 */
    const val MAX_CHANGES: Int = 3

    /** 一次评估最多加几条（人格是长出来的，不是一次刷出来的）。 */
    const val MAX_ADDS: Int = 2

    /** 一次评估最多删几条（删错了很难补回来，所以只给一条额度）。 */
    const val MAX_REMOVES: Int = 1

    /** 低于这个置信度的变更直接丢掉：宁可这次不改。 */
    const val MIN_CONFIDENCE: Double = 0.6

    /** 一条 value 最多多少字（与 [PersonaProfile.sanitizeList] 的 40 字保持一致）。 */
    const val VALUE_MAX_CHARS: Int = 40

    /** reason 只进待审卡片，不进人格，限长度防止模型写小作文。 */
    private const val REASON_MAX_CHARS = 120

    private const val NO_SUMMARY = "（没有可用的最近对话）"
    private const val EMPTY_PATCH = """{"changes":[]}"""

    /** 与 [PersonaProfile]/[ToolProtocol] 一致的宽松 Json：模型输出经常带多余字段或轻微格式问题。 */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val whitespace = Regex("\\s+")

    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    /** 人设卡（[PersonaCard]）那一套顶层字段：出现任何一个就说明这不是进化补丁。 */
    private val CARD_FIELDS = setOf(
        "identity", "voice", "traits", "likes", "behaviors", "quirks",
        "values", "boundaries", "life", "bond", "speechstyle", "name", "age",
        "role", "relation", "description", "avatar", "patch", "persona"
    )

    // ---------------------------------------------------------------- 解析

    /**
     * 从模型输出里挤出 JSON 对象并解析 changes；坏数据返回 emptyList()，绝不抛。
     *
     * 容忍：markdown 代码块包裹、前后有解释文字（取第一个 `{` 到最后一个 `}`）、
     * changes 不是数组（当作没有变更）、field/op 大小写变体（`Traits`/`ADD` 也认）、
     * confidence 缺失（按 0 处理，随后会被 [sanitize] 丢掉）或写成字符串数字。
     *
     * 这里**不套**条数与去重上限——那是 [sanitize] 的职责，见类注释。
     */
    fun parse(raw: String): List<Change> = changesFrom(parseRoot(raw))

    /**
     * 待审补丁 JSON → 变更（供接受时回放）；坏数据 emptyList()。
     *
     * 补丁 JSON 与模型原始输出是同一种形状（[encodePatch] 产生的就是它），
     * 所以这里直接复用 [parse]；落库再读回不会因为多字段/代码块而失败。
     */
    fun decodePatch(patchJson: String): List<Change> = parse(patchJson)

    /**
     * 是不是一条「进化补丁」：顶层有 changes 数组，且没有人设卡字段（identity/voice/traits…）。
     *
     * 两者都会落到同一条 persona patch 记录里，接受前必须分清楚：
     * 人设卡补丁要走 [PersonaCardCodec]，进化补丁要走 [apply]。
     */
    fun isEvolutionPatch(patchJson: String): Boolean {
        val root = parseRoot(patchJson) as? JsonObject ?: return false
        if (root["changes"] !is JsonArray) return false
        return root.keys.none { it.trim().lowercase(Locale.ROOT) in CARD_FIELDS }
    }

    // ---------------------------------------------------------------- 规则

    /**
     * 字段白名单 + 上限 + 置信度 + 去重 + 与当前值比对；输出顺序稳定（先 add 后 remove）。
     *
     * 规则的确切顺序：
     * 1. 丢掉 confidence 不达标（`< 0.6`、NaN）或 value 空白的条目，value 折成一行并截到 40 字；
     * 2. 按 confidence 降序排（同分保持模型给的顺序）；
     * 3. 逐条判定，超限**跳过该条**而不是整体截断——后面低分的 remove 仍然可能挤进来；
     * 4. add 与 remove 的上限分别算（2 / 1），总数不超过 [MAX_CHANGES]；
     * 5. add 与当前值重复（列表按「折行+忽略大小写」比较，quirks 按「某一句里已含该片段」比较）
     *    或与本批已接受的条目重复 → 丢掉；remove 的对象在当前值里不存在 → 丢掉；
     * 6. 输出先排所有 add（保持 2 里的顺序），再排所有 remove。
     */
    fun sanitize(changes: List<Change>, current: Current): List<Change> {
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
     * 把变更应用到 [current]（纯函数，不改入参）。
     *
     * 上限由 [PersonaProfile] 的四个常量兜底（traits 6 / likes 8 / behaviors 6 / quirks 300）：
     * 超了就**丢掉新值**，绝不截断已有值。列表保持原顺序并在写回前去重（保留首次出现）；
     * quirks 的 add 追加一句（换行分隔、已含该片段时幂等），remove 删掉包含该片段的整句/整行。
     */
    fun apply(current: Current, changes: List<Change>): Current {
        var result = current
        for (change in changes) {
            val value = normalizeValue(change.value)
            if (value.isBlank()) continue
            result = when (change.field) {
                Field.TRAITS -> result.copy(
                    traits = updatedList(result.traits, change.op, value, PersonaProfile.MAX_TRAITS)
                )

                Field.LIKES -> result.copy(
                    likes = updatedList(result.likes, change.op, value, PersonaProfile.MAX_LIKES)
                )

                Field.BEHAVIORS -> result.copy(
                    behaviors = updatedList(result.behaviors, change.op, value, PersonaProfile.MAX_BEHAVIORS)
                )

                Field.QUIRKS -> result.copy(quirks = updatedQuirks(result.quirks, change.op, value))
            }
        }
        return result
    }

    // ---------------------------------------------------------------- 序列化

    /**
     * 变更 → 待审补丁 JSON（落库用）：
     * `{"changes":[{"field":"traits","op":"add","value":"…","reason":"…"}]}`
     *
     * 只写四个键，不写 confidence：补丁是给人审的，展示用不到模型自评，
     * 而 [decodePatch] 会把缺失的 confidence 读成 0.0，不影响 [apply]。
     */
    fun encodePatch(changes: List<Change>): String = runCatching {
        buildJsonObject {
            put(
                "changes",
                buildJsonArray {
                    changes.forEach { change ->
                        add(
                            buildJsonObject {
                                put("field", change.field.wireValue)
                                put("op", change.op.wireValue)
                                put("value", normalizeValue(change.value))
                                put("reason", oneLine(change.reason).take(REASON_MAX_CHARS))
                            }
                        )
                    }
                }
            )
        }.toString()
    }.getOrDefault(EMPTY_PATCH)

    // ---------------------------------------------------------------- 文案

    /**
     * 给用户看的一行中文摘要，例如「性格 +嘴硬心软；喜欢的事 −夜里散步」；无变更返回空串。
     *
     * 加号在 add 前、减号（U+2212）在 remove 前，多条目用「；」分隔；空白 value 跳过。
     */
    fun summary(changes: List<Change>): String =
        changes.mapNotNull { change ->
            val value = normalizeValue(change.value)
            if (value.isBlank()) return@mapNotNull null
            "${labelOf(change.field)} ${symbolOf(change.op)}$value"
        }.joinToString("；")

    /**
     * 评估请求的 system 提示词（中文）。
     *
     * 只做两件事：把输出格式钉死成一个 JSON 对象，再把客户端的硬约束提前说清楚
     * （四个字段名、条数上限、0.6 置信度、40 字、去重/存在性），最后给几个反例——
     * 「不因一次争吵就删温柔」这类提醒比规则本身更能压住模型的过度解读。
     */
    fun systemPrompt(): String = """
        你是「人格进化评估器」。你会看到某个 AI 联系人（下称「他」）当前的人格四组值，
        以及最近一段对话的摘要。你的任务：判断他在这一段时间里有没有真的变了。
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

        硬约束（违反会被客户端直接丢掉）：
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
     * 评估请求的 user 提示词：人格卡四组值 + 最近对话摘要 + 现在时间。
     *
     * 人格名/当前人格/摘要由调用方拼好传进来，这里只负责排版与兜底占位；
     * 时间取本机时钟（时区跟随系统），取不到时整行省略。
     */
    fun userPrompt(personaName: String, current: Current, recentSummary: String): String {
        val name = oneLine(personaName).ifBlank { "他" }
        val traits = PersonaProfile.sanitizeList(current.traits, PersonaProfile.MAX_TRAITS)
        val likes = PersonaProfile.sanitizeList(current.likes, PersonaProfile.MAX_LIKES)
        val behaviors = PersonaProfile.sanitizeList(current.behaviors, PersonaProfile.MAX_BEHAVIORS)
        val quirks = current.quirks.trim().take(PersonaProfile.MAX_QUIRKS)
        val now = nowStamp()

        val lines = mutableListOf<String>()
        lines += "【人格进化评估】"
        lines += "联系人：$name"
        if (now != null) lines += "现在时间：$now"
        lines += ""
        lines += "## 当前人格"
        lines += groupLine("性格", Field.TRAITS, traits)
        lines += groupLine("喜欢的事", Field.LIKES, likes)
        lines += groupLine("习惯", Field.BEHAVIORS, behaviors)
        lines += if (quirks.isBlank()) groupLine("设定", Field.QUIRKS, emptyList()) else "设定（quirks）：$quirks"
        lines += ""
        lines += "## 最近对话摘要"
        lines += recentSummary.trim().ifBlank { NO_SUMMARY }
        lines += ""
        lines += """请按 system 说明的格式只输出一个 JSON 对象；没有值得改的就输出 {"changes":[]}。"""
        return lines.joinToString("\n")
    }

    // ---------------------------------------------------------------- 解析细节

    /**
     * 把一段可能带解释文字、代码块围栏的输出挤成 JSON 对象/数组。
     *
     * 依次尝试「整段 → 第一个 `{` 到最后一个 `}` → 第一个 `[` 到最后一个 `]`」，
     * 第一个能解析成对象/数组的就用；都不行返回 null（调用方退化成空列表）。
     */
    private fun parseRoot(raw: String?): JsonElement? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        val candidates = listOfNotNull(
            text,
            bracketSlice(text, '{', '}'),
            bracketSlice(text, '[', ']')
        ).distinct()
        for (candidate in candidates) {
            val parsed = runCatching { json.parseToJsonElement(candidate) }.getOrNull()
            if (parsed is JsonObject || parsed is JsonArray) return parsed
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

    private fun changesFrom(root: JsonElement?): List<Change> = when (root) {
        is JsonArray -> root.mapNotNull { changeFrom(it as? JsonObject) }
        is JsonObject -> (root["changes"] as? JsonArray)
            ?.mapNotNull { changeFrom(it as? JsonObject) }
            ?: emptyList()

        else -> emptyList()
    }

    /** 单条变更的宽松解析：field/op/value 任一不合法就整条丢掉，reason/confidence 缺失走默认。 */
    private fun changeFrom(raw: JsonObject?): Change? {
        if (raw == null) return null
        val field = Field.fromWire(stringOf(raw["field"])) ?: return null
        val op = Op.fromWire(stringOf(raw["op"])) ?: return null
        val value = stringOf(raw["value"])?.let(::oneLine).orEmpty()
        if (value.isBlank()) return null
        return Change(
            field = field,
            op = op,
            value = value,
            reason = stringOf(raw["reason"])?.let(::oneLine)?.take(REASON_MAX_CHARS).orEmpty(),
            confidence = confidenceOf(raw["confidence"])
        )
    }

    /** 只接受 JSON 字符串；数字、布尔、对象、数组都当缺失。 */
    private fun stringOf(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** confidence 认数字与字符串数字；缺失/坏值按 0，超范围夹到 0..1（越界不抛异常）。 */
    private fun confidenceOf(element: JsonElement?): Double {
        val raw = (element as? JsonPrimitive)?.contentOrNull?.trim() ?: return 0.0
        val parsed = raw.toDoubleOrNull() ?: return 0.0
        if (!parsed.isFinite()) return 0.0
        return parsed.coerceIn(0.0, 1.0)
    }

    // ---------------------------------------------------------------- 规则细节

    /** 归一化一条候选变更；置信度不达标或 value 空白直接返回 null（丢掉这一条）。 */
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

    /** 这个值是否已经存在于人格的对应字段里（列表按归一化值比较，quirks 按句/行包含）。 */
    private fun existsIn(current: Current, field: Field, value: String): Boolean {
        val key = keyOf(value)
        if (key.isBlank()) return false
        return when (field) {
            Field.TRAITS -> current.traits.any { keyOf(it) == key }
            Field.LIKES -> current.likes.any { keyOf(it) == key }
            Field.BEHAVIORS -> current.behaviors.any { keyOf(it) == key }
            Field.QUIRKS -> quirkSegments(current.quirks).any { keyOf(it).contains(key) }
        }
    }

    // ---------------------------------------------------------------- 应用细节

    /**
     * 列表字段的增删：先去重（保留首次出现、保持顺序），再加/删；
     * add 在已满或已存在时**原样返回**（丢掉新值，不截断旧值）。
     */
    private fun updatedList(items: List<String>, op: Op, value: String, max: Int): List<String> {
        val kept = items.mapNotNull { raw -> oneLine(raw).takeIf { it.isNotBlank() } }
            .distinctBy { keyOf(it) }
        val key = keyOf(value)
        return when (op) {
            Op.ADD -> if (kept.size >= max || kept.any { keyOf(it) == key }) kept else kept + value
            Op.REMOVE -> kept.filterNot { keyOf(it) == key }
        }
    }

    /**
     * quirks（一整段文本）的增删。
     *
     * - add：现有文本非空时用换行追加一句；已含该片段或追加后超过 [PersonaProfile.MAX_QUIRKS] 时不动。
     * - remove：删掉包含该片段的整句/整行（同一行里只删命中那句，整行没了就整行去掉）。
     */
    private fun updatedQuirks(text: String, op: Op, value: String): String {
        val existing = text.trim()
        return when (op) {
            Op.ADD -> when {
                existing.isBlank() -> value
                quirkSegments(existing).any { keyOf(it).contains(keyOf(value)) } -> existing
                else -> {
                    val merged = existing + "\n" + value
                    if (merged.length > PersonaProfile.MAX_QUIRKS) existing else merged
                }
            }

            Op.REMOVE -> removeQuirkFragment(existing, value)
        }
    }

    /** 逐行切句，丢掉包含 fragment 的句子；整行被删空就整行不要。 */
    private fun removeQuirkFragment(text: String, fragment: String): String {
        if (text.isBlank()) return text
        val needle = keyOf(fragment)
        if (needle.isBlank()) return text
        val keptLines = text.split('\n').mapNotNull { line ->
            val rebuilt = splitSentences(line)
                .filterNot { keyOf(it).contains(needle) }
                .joinToString("")
                .trim()
            rebuilt.ifBlank { null }
        }
        return keptLines.joinToString("\n").trim()
    }

    /** 把一行切成「以句末标点结尾」的若干句；连续省略号算同一个句尾。 */
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

    /** quirks 的「句/行」视图：先按行切，再按句末标点切，空白片段丢掉。 */
    private fun quirkSegments(text: String): List<String> =
        text.split('\n')
            .flatMap { splitSentences(it) }
            .mapNotNull { oneLine(it).takeIf { cleaned -> cleaned.isNotBlank() } }

    // ---------------------------------------------------------------- 文本工具

    /** 折成一行：全角空格/不换行空格当普通空格，连续空白压成一个。 */
    private fun oneLine(raw: String): String =
        raw.replace('\u3000', ' ').replace('\u00A0', ' ').trim().replace(whitespace, " ")

    /** 规则用的 value：折行后截到 [VALUE_MAX_CHARS]。 */
    private fun normalizeValue(raw: String): String = oneLine(raw).take(VALUE_MAX_CHARS)

    /** 比较用的 key：折行 + 小写，让「Traits」和「traits」这种差异不影响去重。 */
    private fun keyOf(raw: String): String = oneLine(raw).lowercase(Locale.ROOT)

    private fun labelOf(field: Field): String = when (field) {
        Field.TRAITS -> "性格"
        Field.LIKES -> "喜欢的事"
        Field.BEHAVIORS -> "习惯"
        Field.QUIRKS -> "设定"
    }

    private fun symbolOf(op: Op): String = when (op) {
        Op.ADD -> "+"
        Op.REMOVE -> "\u2212"
    }

    private fun groupLine(label: String, field: Field, values: List<String>): String =
        if (values.isEmpty()) {
            "$label（${field.wireValue}）：（暂无）"
        } else {
            "$label（${field.wireValue}）：" + values.joinToString("、")
        }

    private fun nowStamp(): String? = runCatching {
        ZonedDateTime.now(ZoneId.systemDefault()).format(timeFormat)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private const val SENTENCE_ENDINGS = "。！？!?；;…"
}
