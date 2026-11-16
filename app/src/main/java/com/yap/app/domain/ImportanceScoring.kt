package com.yap.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 整理器能下的五种指令。
 */
enum class MemoryOp {
    /** 留着：模型明确说这条重要，加固一点分量。 */
    KEEP,

    /** 合并：把几条零碎记忆并成一条，保留最老的那条，其余归档。 */
    MERGE,

    /** 归档：移出上下文，但数据还留着。 */
    ARCHIVE,

    /** 忘记：真的删掉。 */
    FORGET,

    /** 提权：这条以后会更常用，重要度往上调。 */
    RAISE
}

/**
 * 一条整理指令。`ids` 里是模型抄回来的记忆编号（也可以是真实 id，整理器会自己解析）。
 */
data class MemoryOperation(
    val op: MemoryOp,
    val ids: List<String> = emptyList(),
    /** merge 用的合并后正文；其它指令一般没有。 */
    val content: String? = null,
    /** raise 用的新重要度（1..5）。 */
    val importance: Int? = null,
    /** 模型给的中文理由，只用于留痕/日志，不落库到记忆本身。 */
    val reason: String = ""
)

/** 一次整理的完整计划。模型什么都没给时是 [isEmpty] 的空计划，调用方按「什么都不做」处理。 */
data class ConsolidationPlan(
    val operations: List<MemoryOperation> = emptyList(),
    val summary: String = ""
) {
    val isEmpty: Boolean get() = operations.isEmpty()
}

/**
 * 一次「这东西值不值得记」的判断结果。
 * [drawer] 表示记到哪个抽屉：SELF 关于他自己 / USER 关于对方 / EVENT 共同经历。
 */
data class SearchScore(
    val important: Boolean,
    val importance: Int,
    val takeaway: String,
    val drawer: MemoryDrawer = MemoryDrawer.USER
)

/**
 * 重要性打分器：整理器和「联网搜索打分」共用同一套「值得记吗」的判据与解析。
 *
 * 全部是纯函数，不碰 Android、不碰数据库；坏 JSON、缺字段、越界数值都不会抛异常，
 * 一律降级成「什么都不做」——整理器和打分器都跑在后台，它们挂掉不该影响聊天。
 */
object ImportanceScoring {

    /** 重要度门槛：3 分才是「以后可能用得上」，低于它的搜索结果不值得写进记忆。 */
    const val REMEMBER_FLOOR = 3

    const val MIN_IMPORTANCE = 1
    const val MAX_IMPORTANCE = 5

    /** 一条记忆正文的上限（和 MemoryRepository 的 2000 字限制保持余量）。 */
    private const val MAX_CONTENT_CHARS = 800
    private const val MAX_SUMMARY_CHARS = 200
    private const val MAX_TAKEAWAY_CHARS = 200
    private const val MAX_REASON_CHARS = 120

    /** 单次整理最多认多少条指令：模型偶尔会把同一批记忆反复列，截断比全量执行安全。 */
    const val MAX_OPERATIONS = 12

    /** 单条指令最多带多少个 id（merge 也够用了）。 */
    const val MAX_IDS_PER_OPERATION = 8

    /**
     * 「值得记吗」的共享判据。
     *
     * 整理器（每日整理）和打分器（联网搜索）都用这一份，保证「他愿意记住什么」是同一个标准：
     * 否则会出现「聊天里不记、上网却记」的分裂人格。
     */
    const val SHARED_CRITERIA: String =
        "值得记：稳定的身份与生活背景、长期偏好与忌讳、明确的约定/承诺/纪念日、" +
            "重要的人际关系、能影响以后相处的事实、他自己真正在意的观点。\n" +
            "不值得记：一次性的情绪、寒暄客套、随口闲聊、天气式的天气、模型的猜测与推测、" +
            "已经在记忆里写过的事。\n" +
            "重要度：5 = 身份级（名字、家人、重大约定）；4 = 长期偏好与忌讳；" +
            "3 = 以后可能用得上；2 = 只是近况；1 = 几乎没用。"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    // ---------------------------------------------------------------- 公开：提示词

    /**
     * 整理器的 system 提示词。整理跑在一个「随风叉、用完即弃」的会话里：
     * 没有历史、没有工具、没有消息表，只靠这段判据 + 下面那份编号清单工作。
     */
    fun consolidationSystemPrompt(personaSummary: String): String {
        val who = personaSummary.trim().ifBlank { "他" }
        return """
            你是「$who」的长期记忆整理器。你不是在聊天：你在给自己整理抽屉。
            把这段日子里记住的事过一遍：留下真正重要的，把零碎重复的并起来，把不重要的挪出抽屉。

            判据：
            $SHARED_CRITERIA

            抽屉语义：
            - SELF：关于「你自己」的（性格、习惯、说话方式、你在意什么）
            - USER：关于「对方」的（身份、偏好、忌讳、身边的人）
            - EVENT：你们之间的经历、约定、纪念日

            清单里的「状态」：还在用 / 已经沉下去 / 已归档。
            已经沉下去和已归档的都不再进上下文；对它们下 keep 等于把它重新拿回来，请谨慎。

            清单里可能还会有两列，它们是你自己当初留下的线索：
            - 「标签」：这条记忆挂着的短词（人 / 事 / 物 / 情绪，如 妹妹、失眠、杭州），用来在以后被一句话勾出来；
            - 「发生在」：这件事**发生**的时间（不是记下来的时间）。人回忆时会说「去年冬天」，靠的就是它。
            两列都可能没有；没有就是当时没判断出来。

            只输出 JSON，不要 Markdown 代码块，不要解释。格式：
            {"operations":[{"op":"keep|merge|archive|forget|raise","ids":["清单里的编号"],"content":"可选：合并后的正文","importance":1到5的整数,"reason":"一句中文理由"}],"summary":"一句话说这次整理做了什么"}

            规则：
            - 一条记忆最多出现在一条指令里；没把握就别下指令，它会自己慢慢变淡。
            - merge 至少带 2 个编号，保留最老的那条，content 写合并后的正文（不超过 300 字）。
              **合并时不许丢掉时间和标签**：正文里要写清原来的发生时间（「去年冬天」这种说法就行），
              被并起来那几条的标签要合在一起带过来（最多 5 个）。
            - raise 必须带 importance；forget 只给「半年后肯定想不起来」的事，谨慎再用。
            - 最多 $MAX_OPERATIONS 条指令。
        """.trimIndent()
    }

    /**
     * 打分器的 system 提示词：判断「他刚上网看到的这些东西值不值得记住」。
     * 和整理器共用 [SHARED_CRITERIA]，只是输出格式不同。
     */
    fun searchScoringSystemPrompt(personaSummary: String): String {
        val who = personaSummary.trim().ifBlank { "他" }
        return """
            你负责替「$who」判断：他刚上网看到的这些东西，值不值得记住。

            判据：
            $SHARED_CRITERIA

            只输出 JSON，不要 Markdown 代码块，不要解释。格式：
            {"important":true或false,"importance":1到5的整数,"takeaway":"一句话，用第三人称写成一条记忆","drawer":"SELF|USER|EVENT"}
            规则：
            - important 为 false 时 importance 填 1。
            - takeaway 只在 important 为 true 时才有意义，不超过 80 字，不要写「搜索结果显示」这类废话。
            - drawer：SELF 关于他自己、USER 关于对方、EVENT 你们之间的事。
        """.trimIndent()
    }

    // ---------------------------------------------------------------- 公开：解析

    fun clampImportance(value: Int): Int = value.coerceIn(MIN_IMPORTANCE, MAX_IMPORTANCE)

    /**
     * 宽松解析模型给的 tags：认 JSON 数组（`["妹妹","失眠"]`）、单个字符串、逗号/顿号分隔的散串。
     *
     * 结果交给 [MemoryTags] 清洗：去空白、去重、单个标签 ≤ 12 字、最多 5 个。
     * 坏数据（`null`、数字、乱七八糟的括号）一律当「没有标签」，不抛异常——
     * 标签只是查记忆的辅助，不能因为它把整条记忆丢了。
     */
    fun parseTags(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) return emptyList()
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
        val pieces: List<String> = when (parsed) {
            is JsonArray -> parsed.mapNotNull { element ->
                runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
            }

            is JsonPrimitive -> listOfNotNull(parsed.contentOrNull)

            else -> listOf(text)
        }
        val split = pieces
            .flatMap { it.split(',', '，', '、', ';', '；', '/', '|', '\n') }
            .map { it.trim().trim('"', '\'', '[', ']') }
            .filter { it.isNotEmpty() }
        return MemoryTags.sanitize(split)
    }

    /**
     * 把模型写的「昨天 / 上周三 / 去年冬天 / 上个月」换算成毫秒；算不出来返回 null（= 不写这个字段）。
     *
     * 换算规则：拿到的是那段时间的**区间**，取中点当作「这件事发生的时间」。
     * 只精确到区间是诚实的——人本来也只记得「去年冬天」，不会记得具体哪一天；
     * 取中点既不会把「去年冬天」错算成「今年春天」，也不会让时间指代加权落空。
     *
     * 如果模型直接给了毫秒时间戳（≥ 10 位数字），就原样采用；给年份（如 2025）不算时间戳。
     */
    fun parseRelativeTime(raw: String?, now: Long): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) return null
        if (text.length >= 10) {
            text.toLongOrNull()?.let { millis -> if (millis > 0L) return millis }
        }
        val span = ChineseTerms.timeSpanOf(text, now) ?: return null
        return span.center
    }

    /** 这条搜索命中值不值得落库：模型说重要，并且重要度到 3 分。 */
    fun shouldRemember(score: SearchScore): Boolean =
        score.important && score.importance >= REMEMBER_FLOOR

    /**
     * 宽松解析整理器输出。
     *
     * 能容忍：```json 代码块、前后多余的解释文字、字段名大小写/别名（op/action、ids/id）、
     * 编号是数字还是字符串、重要度是数字还是字符串、未知 op、空 ids。
     * 不能容忍的（坏 JSON、不是对象、完全没有 operations）→ 返回空计划，让调用方什么都不做。
     */
    fun parseConsolidation(raw: String): ConsolidationPlan {
        val root = extractObject(raw) ?: return ConsolidationPlan()
        val summary = stringOf(root, "summary", "一句话", "note")
            .take(MAX_SUMMARY_CHARS)
        val array = (root["operations"] ?: root["ops"] ?: root["actions"]) as? JsonArray
            ?: return ConsolidationPlan(operations = emptyList(), summary = summary)
        val operations = array.mapNotNull { element ->
            runCatching { element.jsonObject }.getOrNull()?.let { parseOperation(it) }
        }.take(MAX_OPERATIONS)
        return ConsolidationPlan(operations = operations, summary = summary)
    }

    /**
     * 宽松解析打分器输出；一个对象都抠不出来时返回 null（= 这次不记）。
     *
     * 缺字段时的降级：
     * - 没有 important：按 importance ≥ [REMEMBER_FLOOR] 推断；
     * - 没有 importance：important 为真按 3 分，否则 1 分；
     * - 没有 drawer / drawer 不认识：按 USER；
     * - 没有 takeaway：空串（调用方不会用它写记忆）。
     */
    fun parseSearchScore(raw: String): SearchScore? {
        val root = extractObject(raw) ?: return null
        val importanceRaw = intOf(root, "importance", "weight", "score")
            ?: intOf(root, "level")
        val importantRaw = booleanOf(root, "important", "worth", "remember")
        val importance = when {
            importanceRaw != null -> clampImportance(importanceRaw)
            importantRaw == true -> REMEMBER_FLOOR
            else -> MIN_IMPORTANCE
        }
        val important = importantRaw ?: (importance >= REMEMBER_FLOOR)
        val takeaway = stringOf(root, "takeaway", "summary", "content", "memory")
            .take(MAX_TAKEAWAY_CHARS)
        val drawer = drawerOf(stringOf(root, "drawer", "type", "category"))
        return SearchScore(
            important = important,
            importance = importance,
            takeaway = takeaway,
            drawer = drawer
        )
    }

    // ---------------------------------------------------------------- 内部：解析

    private fun parseOperation(obj: JsonObject): MemoryOperation? {
        val op = opOf(stringOf(obj, "op", "operation", "action", "type")) ?: return null
        val ids = idsOf(obj)
        if (ids.isEmpty()) return null
        val importance = intOf(obj, "importance", "weight", "score")
            ?.let { clampImportance(it) }
        val content = stringOf(obj, "content", "text", "merged", "body")
            .take(MAX_CONTENT_CHARS)
            .takeIf { it.isNotBlank() }
        val reason = stringOf(obj, "reason", "why", "note").take(MAX_REASON_CHARS)

        // 各指令的最低要求：merge 至少要两条才叫合并；raise 没有目标重要度就没意义。
        val valid = when (op) {
            MemoryOp.MERGE -> ids.size >= 2
            MemoryOp.RAISE -> importance != null
            else -> true
        }
        if (!valid) return null
        return MemoryOperation(
            op = op,
            ids = ids.take(MAX_IDS_PER_OPERATION),
            content = content,
            importance = importance,
            reason = reason
        )
    }

    private fun idsOf(obj: JsonObject): List<String> {
        val raw = obj["ids"] ?: obj["id"] ?: obj["memoryIds"] ?: return emptyList()
        val collected = when (raw) {
            is JsonArray -> raw.mapNotNull { element ->
                runCatching { element.jsonPrimitive }.getOrNull()?.let { primitiveToString(it) }
            }

            is JsonPrimitive -> listOf(primitiveToString(raw))
            else -> emptyList()
        }
        return collected
            .flatMap { it.split(',', '，', '、', ';', '；', ' ') }
            .map { it.trim().trim('"', '\'', '[', ']') }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun opOf(raw: String): MemoryOp? {
        val key = raw.trim().uppercase()
        if (key.isEmpty()) return null
        return when {
            key == "KEEP" || key.contains("保留") || key.contains("留着") -> MemoryOp.KEEP
            key == "MERGE" || key.contains("合并") || key.contains("并起") -> MemoryOp.MERGE
            key == "ARCHIVE" || key.contains("归档") || key.contains("沉下去") -> MemoryOp.ARCHIVE
            key == "FORGET" || key.contains("忘记") || key.contains("遗忘") || key.contains("删除") ->
                MemoryOp.FORGET
            key == "RAISE" || key.contains("提权") || key.contains("提升") || key.contains("上调") ->
                MemoryOp.RAISE
            else -> null
        }
    }

    private fun drawerOf(raw: String): MemoryDrawer {
        val key = raw.trim().uppercase()
        MemoryDrawer.entries.firstOrNull { it.name == key }?.let { return it }
        // 认不出来就交给统一映射（它会把 FACT/PREFERENCE 之类归到 USER）。
        return if (key.isEmpty()) MemoryDrawer.USER else MemoryScreening.drawerOf(key)
    }

    /** 从一段可能带 ``` 代码块或前后解释的文字里抠出第一个 JSON 对象。 */
    private fun extractObject(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val slice = cleaned.substring(start, end + 1)
        return runCatching { json.parseToJsonElement(slice) as? JsonObject }.getOrNull()
    }

    private fun stringOf(obj: JsonObject, vararg keys: String): String {
        keys.forEach { key ->
            val element = obj[key] ?: return@forEach
            val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return@forEach
            val text = primitiveToString(primitive)
            if (text.isNotBlank()) return text
        }
        return ""
    }

    private fun intOf(obj: JsonObject, vararg keys: String): Int? {
        keys.forEach { key ->
            val element = obj[key] ?: return@forEach
            val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return@forEach
            primitive.intOrNull?.let { return it }
            primitiveToString(primitive).trim().toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun booleanOf(obj: JsonObject, vararg keys: String): Boolean? {
        keys.forEach { key ->
            val element = obj[key] ?: return@forEach
            val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return@forEach
            primitive.booleanOrNull?.let { return it }
            when (primitiveToString(primitive).trim().lowercase()) {
                "true", "1", "yes", "y", "是", "重要", "值得" -> return true
                "false", "0", "no", "n", "否", "不重要", "不值得" -> return false
            }
        }
        return null
    }

    private fun primitiveToString(primitive: JsonPrimitive): String =
        if (primitive is kotlinx.serialization.json.JsonNull) "" else primitive.contentOrNull.orEmpty()
}
